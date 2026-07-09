package com.invoiceflow.invoice_service.messaging;

import com.invoiceflow.invoice_service.domain.Invoice;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InvoiceEventPublisherTest {

    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final InvoiceEventPublisher publisher = new InvoiceEventPublisher(kafkaTemplate);

    @Test
    void publishReceivedSendsEnvelopeWithInvoiceIdAsKey() {
        UUID invoiceId = UUID.randomUUID();

        Invoice invoice = Invoice.receive(
                invoiceId,
                "rechnung.pdf",
                "2026/07/" + invoiceId + ".pdf",
                "application/pdf",
                999L
        );

        publisher.publishReceived(invoice);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);

        verify(kafkaTemplate).send(
                topicCaptor.capture(),
                keyCaptor.capture(),
                valueCaptor.capture()
        );

        assertThat(topicCaptor.getValue()).isEqualTo("invoice.received");
        assertThat(keyCaptor.getValue()).isEqualTo(invoiceId.toString());

        Object value = valueCaptor.getValue();

        assertThat(value).isInstanceOf(EventEnvelope.class);

        EventEnvelope<?> envelope = (EventEnvelope<?>) value;

        assertThat(envelope.eventType()).isEqualTo("invoice.received");
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.correlationId()).isEqualTo(invoiceId.toString());
        assertThat(envelope.producer()).isEqualTo("invoice-service");
        assertThat(envelope.eventId()).isNotNull();
        assertThat(envelope.occurredAt()).isNotNull();

        assertThat(envelope.payload()).isInstanceOf(InvoiceReceivedPayload.class);

        InvoiceReceivedPayload payload = (InvoiceReceivedPayload) envelope.payload();

        assertThat(payload.invoiceId()).isEqualTo(invoiceId.toString());
        assertThat(payload.documentKey()).isEqualTo("2026/07/" + invoiceId + ".pdf");
        assertThat(payload.originalFilename()).isEqualTo("rechnung.pdf");
        assertThat(payload.contentType()).isEqualTo("application/pdf");
        assertThat(payload.sizeBytes()).isEqualTo(999L);
        assertThat(payload.receivedAt()).isEqualTo(invoice.getReceivedAt());
    }
}
