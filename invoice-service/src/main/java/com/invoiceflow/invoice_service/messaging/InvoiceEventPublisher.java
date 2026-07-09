package com.invoiceflow.invoice_service.messaging;

import com.invoiceflow.invoice_service.domain.Invoice;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InvoiceEventPublisher {

    private static final String TOPIC = "invoice.received";
    private static final String EVENT_TYPE = "invoice.received";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishReceived(Invoice invoice) {
        InvoiceReceivedPayload payload = new InvoiceReceivedPayload(
                invoice.getId().toString(),
                invoice.getDocumentKey(),
                invoice.getOriginalFilename(),
                invoice.getContentType(),
                invoice.getSizeBytes(),
                invoice.getReceivedAt()
        );

        EventEnvelope<InvoiceReceivedPayload> envelope = EventEnvelope.of(
                EVENT_TYPE,
                invoice.getId().toString(),
                payload
        );

        kafkaTemplate.send(
                TOPIC,
                invoice.getId().toString(),
                envelope
        );
    }
}
