package com.invoiceflow.invoice_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoiceflow.invoice_service.domain.Invoice;
import com.invoiceflow.invoice_service.domain.InvoiceStatus;
import com.invoiceflow.invoice_service.domain.Supplier;
import com.invoiceflow.invoice_service.messaging.EventEnvelope;
import com.invoiceflow.invoice_service.messaging.extracted.ExtractedField;
import com.invoiceflow.invoice_service.messaging.extracted.ExtractedPayload;
import com.invoiceflow.invoice_service.messaging.extracted.ExtractionFields;
import com.invoiceflow.invoice_service.repository.InvoiceRepository;
import com.invoiceflow.invoice_service.repository.SupplierRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class InvoiceProcessingIT {

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

    @Autowired
    InvoiceRepository invoiceRepository;

    @Autowired
    SupplierRepository supplierRepository;

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void extractedEventMovesInvoiceToPendingApprovalAndPublishesValidatedEvent() throws Exception {
        Invoice invoice = Invoice.receive(
                UUID.randomUUID(),
                "rechnung.pdf",
                "2026/07/rechnung.pdf",
                "application/pdf",
                100L
        );

        invoiceRepository.save(invoice);

        ExtractedPayload payload = validExtractedPayload(invoice.getId(), "ACME GmbH", "RE-2026-001");
        EventEnvelope<ExtractedPayload> envelope = EventEnvelope.of(
                "invoice.extracted",
                invoice.getId().toString(),
                payload
        );

        kafkaTemplate.send(
                new ProducerRecord<>(
                        "invoice.extracted",
                        invoice.getId().toString(),
                        objectMapper.writeValueAsString(envelope)
                )
        );

        await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    Invoice updated = invoiceRepository.findById(invoice.getId()).orElseThrow();

                    assertThat(updated.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
                    assertThat(updated.getSupplier().getName()).isEqualTo("ACME GmbH");
                    assertThat(updated.getInvoiceNumber()).isEqualTo("RE-2026-001");
                    assertThat(updated.getInvoiceDate()).isEqualTo(LocalDate.of(2026, 7, 10));
                    assertThat(updated.getCurrency()).isEqualTo("EUR");
                    assertThat(updated.getTotalNet()).isEqualByComparingTo("100.00");
                    assertThat(updated.getTotalGross()).isEqualByComparingTo("119.00");
                    assertThat(updated.getValidationError()).isNull();
                });

        ConsumerRecord<String, Object> record = readSingleValidatedRecord();

        assertThat(record.key()).isEqualTo(invoice.getId().toString());
        assertThat(record.value().toString()).contains("invoice.validated");
        assertThat(record.value().toString()).contains("VALIDATED");
        assertThat(record.value().toString()).contains("RE-2026-001");
    }

    @Test
    void duplicateInvoiceIsMarkedValidationFailed() throws Exception {
        Supplier supplier = supplierRepository.save(new Supplier("ACME GmbH"));

        Invoice existing = Invoice.receive(
                UUID.randomUUID(),
                "existing.pdf",
                "2026/07/existing.pdf",
                "application/pdf",
                100L
        );

        existing.applyExtraction(
                supplier,
                "RE-2026-001",
                LocalDate.of(2026, 7, 10),
                new BigDecimal("100.00"),
                new BigDecimal("119.00"),
                "EUR",
                "mongo-existing"
        );

        existing.markValidated();
        invoiceRepository.save(existing);

        Invoice duplicate = Invoice.receive(
                UUID.randomUUID(),
                "duplicate.pdf",
                "2026/07/duplicate.pdf",
                "application/pdf",
                100L
        );

        invoiceRepository.save(duplicate);

        ExtractedPayload payload = validExtractedPayload(duplicate.getId(), "ACME GmbH", "RE-2026-001");
        EventEnvelope<ExtractedPayload> envelope = EventEnvelope.of(
                "invoice.extracted",
                duplicate.getId().toString(),
                payload
        );

        kafkaTemplate.send(
                new ProducerRecord<>(
                        "invoice.extracted",
                        duplicate.getId().toString(),
                        objectMapper.writeValueAsString(envelope)
                )
        );

        await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    Invoice updated = invoiceRepository.findById(duplicate.getId()).orElseThrow();

                    assertThat(updated.getStatus()).isEqualTo(InvoiceStatus.VALIDATION_FAILED);
                    assertThat(updated.getValidationError())
                            .contains("Dublette: Lieferant + Nummer existiert");
                });
    }

    @Test
    void illegalTransitionThrowsException() {
        Invoice invoice = Invoice.receive(
                UUID.randomUUID(),
                "rechnung.pdf",
                "2026/07/rechnung.pdf",
                "application/pdf",
                100L
        );

        invoice.applyExtraction(
                new Supplier("ACME GmbH"),
                "RE-2026-001",
                LocalDate.of(2026, 7, 10),
                new BigDecimal("100.00"),
                new BigDecimal("119.00"),
                "EUR",
                "mongo-123"
        );

        invoice.markValidated();

        assertThatThrownBy(() -> invoice.applyExtraction(
                new Supplier("ACME GmbH"),
                "RE-2026-002",
                LocalDate.of(2026, 7, 11),
                new BigDecimal("200.00"),
                new BigDecimal("238.00"),
                "EUR",
                "mongo-456"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Illegal invoice status transition");
    }

    private ExtractedPayload validExtractedPayload(
            UUID invoiceId,
            String supplierName,
            String invoiceNumber
    ) {
        return new ExtractedPayload(
                invoiceId.toString(),
                "2026/07/" + invoiceId + ".pdf",
                "EXTRACTED",
                new ExtractionFields(
                        new ExtractedField(supplierName, 1.0),
                        new ExtractedField(invoiceNumber, 1.0),
                        new ExtractedField("2026-07-10", 1.0),
                        new ExtractedField("EUR", 1.0),
                        new ExtractedField("100.00", 1.0),
                        new ExtractedField("119.00", 1.0)
                ),
                "mongo-123",
                null
        );
    }

    private ConsumerRecord<String, Object> readSingleValidatedRecord() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                kafka.getBootstrapServers(),
                "invoice-processing-it",
                "true"
        );

        DefaultKafkaConsumerFactory<String, Object> consumerFactory =
                new DefaultKafkaConsumerFactory<>(consumerProps);

        Consumer<String, Object> consumer = consumerFactory.createConsumer();

        try {
            consumer.subscribe(List.of("invoice.validated"));
            return KafkaTestUtils.getSingleRecord(
                    consumer,
                    "invoice.validated",
                    Duration.ofSeconds(10)
            );
        } finally {
            consumer.close();
        }
    }
}
