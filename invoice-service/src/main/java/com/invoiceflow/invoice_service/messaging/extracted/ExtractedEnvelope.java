package com.invoiceflow.invoice_service.messaging.extracted;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractedEnvelope(
        String eventId,
        String eventType,
        int schemaVersion,
        String occurredAt,
        String correlationId,
        String producer,
        ExtractedPayload payload
) { }
