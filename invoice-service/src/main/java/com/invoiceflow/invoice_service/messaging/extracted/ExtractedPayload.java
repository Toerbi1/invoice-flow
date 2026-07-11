package com.invoiceflow.invoice_service.messaging.extracted;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractedPayload(
        String invoiceId,
        String documentKey,
        String status,
        ExtractionFields extraction,
        String mongoRef,
        String error
) { }
