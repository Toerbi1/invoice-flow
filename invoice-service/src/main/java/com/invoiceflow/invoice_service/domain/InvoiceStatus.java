package com.invoiceflow.invoice_service.domain;

public enum InvoiceStatus {
    RECEIVED,
    EXTRACTING,
    EXTRACTED,
    PENDING_APPROVAL,
    VALIDATION_FAILED,
    APPROVED,
    REJECTED
}
