package com.invoiceflow.invoice_service.messaging.extracted;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractionFields(
        ExtractedField supplierName,
        ExtractedField invoiceNumber,
        ExtractedField invoiceDate,
        ExtractedField currency,
        ExtractedField totalNet,
        ExtractedField totalGross
) { }
