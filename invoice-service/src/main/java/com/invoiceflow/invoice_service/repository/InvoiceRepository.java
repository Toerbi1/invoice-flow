package com.invoiceflow.invoice_service.repository;

import com.invoiceflow.invoice_service.domain.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
}
