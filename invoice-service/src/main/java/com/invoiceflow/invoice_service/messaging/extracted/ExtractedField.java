package com.invoiceflow.invoice_service.messaging.extracted;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractedField(
        String value,
        double confidence
) { }
