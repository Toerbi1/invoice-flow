package com.invoiceflow.invoice_service.messaging;

import com.invoiceflow.invoice_service.domain.Invoice;
import com.invoiceflow.invoice_service.domain.InvoiceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InvoiceEventPublisher {

    private static final String TOPIC = "invoice.received";
    private static final String EVENT_TYPE = "invoice.received";
    private static final String VALIDATED_TOPIC = "invoice.validated";
    private static final String VALIDATED_EVENT_TYPE = "invoice.validated";

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

    public void publishValidated(Invoice invoice) {
        String validationStatus = invoice.getStatus() == InvoiceStatus.PENDING_APPROVAL
                ? "VALIDATED"
                : "VALIDATION_FAILED";

        InvoiceValidatedPayload payload = getInvoiceValidatedPayload(invoice, validationStatus);

        EventEnvelope<InvoiceValidatedPayload> envelope = EventEnvelope.of(
                VALIDATED_EVENT_TYPE,
                invoice.getId().toString(),
                payload
        );

        kafkaTemplate.send(
                VALIDATED_TOPIC,
                invoice.getId().toString(),
                envelope
        );
    }

    private static InvoiceValidatedPayload getInvoiceValidatedPayload(Invoice invoice, String validationStatus) {
        String supplierName = invoice.getSupplier() == null
                ? null
                : invoice.getSupplier().getName();

        return new InvoiceValidatedPayload(
                invoice.getId().toString(),
                validationStatus,
                supplierName,
                invoice.getInvoiceNumber(),
                invoice.getInvoiceDate(),
                invoice.getCurrency(),
                invoice.getTotalNet(),
                invoice.getTotalGross(),
                invoice.getValidationError()
        );
    }
}
