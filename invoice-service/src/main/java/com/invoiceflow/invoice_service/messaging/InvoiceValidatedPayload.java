package com.invoiceflow.invoice_service.messaging;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InvoiceValidatedPayload(
        String invoiceId,
        String status,
        String supplierName,
        String invoiceNumber,
        LocalDate invoiceDate,
        String currency,
        BigDecimal totalNet,
        BigDecimal totalGross,
        String validationError
) { }
