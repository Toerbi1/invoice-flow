package com.invoiceflow.invoice_service.service;

import java.util.UUID;

public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException(UUID id) {
        super("Invoice not found: " + id);
    }
}
