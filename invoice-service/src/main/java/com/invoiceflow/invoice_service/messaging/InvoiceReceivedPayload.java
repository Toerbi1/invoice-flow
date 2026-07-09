package com.invoiceflow.invoice_service.messaging;

import java.time.Instant;

public record InvoiceReceivedPayload(
        String invoiceId,
        String documentKey,
        String originalFilename,
        String contentType,
        long sizeBytes,
        Instant receivedAt
) { }
