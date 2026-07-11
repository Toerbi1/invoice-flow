package com.invoiceflow.invoice_service.web;

import com.invoiceflow.invoice_service.domain.InvoiceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        InvoiceStatus status,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        Instant receivedAt,
        String supplierName,
        String invoiceNumber,
        LocalDate invoiceDate,
        String currency,
        BigDecimal totalNet,
        BigDecimal totalGross,
        String validationError
) { }
