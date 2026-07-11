package com.invoiceflow.invoice_service.repository;

import com.invoiceflow.invoice_service.domain.Invoice;
import com.invoiceflow.invoice_service.domain.InvoiceStatus;
import com.invoiceflow.invoice_service.domain.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    boolean existsBySupplierAndInvoiceNumberAndStatusInAndIdNot(
            Supplier supplier,
            String invoiceNumber,
            Collection<InvoiceStatus> statuses,
            UUID id
    );

}
