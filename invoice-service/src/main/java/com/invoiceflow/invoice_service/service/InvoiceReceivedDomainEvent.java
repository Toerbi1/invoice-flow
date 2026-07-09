package com.invoiceflow.invoice_service.service;

import com.invoiceflow.invoice_service.domain.Invoice;

public record InvoiceReceivedDomainEvent(Invoice invoice) {
}
