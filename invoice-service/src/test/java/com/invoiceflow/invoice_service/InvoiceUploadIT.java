package com.invoiceflow.invoice_service;

import com.invoiceflow.invoice_service.repository.InvoiceRepository;
import com.invoiceflow.invoice_service.storage.ObjectStorageService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class InvoiceUploadIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.8.0")
    );

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        registry.add("storage.bucket", () -> "invoices");
        registry.add("storage.endpoint", () -> "http://localhost:9000");
        registry.add("storage.access-key", () -> "test");
        registry.add("storage.secret-key", () -> "test");
        registry.add("storage.region", () -> "us-east-1");
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    InvoiceRepository invoiceRepository;

    @MockitoBean
    ObjectStorageService objectStorageService;

    @Test
    void uploadPdfCreatesInvoiceStoresObjectAndPublishesKafkaEvent() {
        byte[] pdfBytes = "%PDF- test invoice".getBytes();

        ByteArrayResource fileResource = new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                return "test.pdf";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/invoices",
                request,
                String.class
        );

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(invoiceRepository.count()).isEqualTo(1);

        var invoice = invoiceRepository.findAll().getFirst();

        assertThat(invoice.getStatus().name()).isEqualTo("RECEIVED");
        assertThat(invoice.getOriginalFilename()).isEqualTo("test.pdf");
        assertThat(invoice.getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(invoice.getSizeBytes()).isEqualTo((long) pdfBytes.length);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> contentTypeCaptor = ArgumentCaptor.forClass(String.class);

        verify(objectStorageService).putObject(
                keyCaptor.capture(),
                contentCaptor.capture(),
                contentTypeCaptor.capture()
        );

        assertThat(keyCaptor.getValue()).endsWith(invoice.getId() + ".pdf");
        assertThat(contentCaptor.getValue()).isEqualTo(pdfBytes);
        assertThat(contentTypeCaptor.getValue()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);

        ConsumerRecord<String, Object> record = readSingleKafkaRecord();

        assertThat(record.key()).isEqualTo(invoice.getId().toString());
        assertThat(record.value().toString()).contains("invoice.received");
        assertThat(record.value().toString()).contains(invoice.getId().toString());
    }

    private ConsumerRecord<String, Object> readSingleKafkaRecord() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                kafka.getBootstrapServers(),
                "invoice-upload-it",
                "true"
        );

        DefaultKafkaConsumerFactory<String, Object> consumerFactory =
                new DefaultKafkaConsumerFactory<>(consumerProps);

        try (Consumer<String, Object> consumer = consumerFactory.createConsumer()) {
            consumer.subscribe(java.util.List.of("invoice.received"));
            return KafkaTestUtils.getSingleRecord(
                    consumer,
                    "invoice.received",
                    Duration.ofSeconds(10)
            );
        }
    }
}
