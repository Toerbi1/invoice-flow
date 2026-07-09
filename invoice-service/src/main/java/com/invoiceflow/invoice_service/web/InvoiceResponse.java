package com.invoiceflow.invoice_service.web;

import com.invoiceflow.invoice_service.domain.InvoiceStatus;

import java.time.Instant;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        InvoiceStatus status,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        Instant receivedAt
) { }
