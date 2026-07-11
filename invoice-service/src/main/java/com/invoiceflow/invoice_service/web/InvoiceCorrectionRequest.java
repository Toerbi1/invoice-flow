package com.invoiceflow.invoice_service.web;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InvoiceCorrectionRequest(
        String supplierName,
        String invoiceNumber,
        LocalDate invoiceDate,
        BigDecimal totalNet,
        BigDecimal totalGross,
        String currency
) { }
