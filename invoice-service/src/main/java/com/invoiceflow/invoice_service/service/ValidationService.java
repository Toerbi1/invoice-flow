package com.invoiceflow.invoice_service.service;

import com.invoiceflow.invoice_service.domain.Invoice;
import com.invoiceflow.invoice_service.domain.InvoiceStatus;
import com.invoiceflow.invoice_service.messaging.extracted.ExtractedField;
import com.invoiceflow.invoice_service.messaging.extracted.ExtractionFields;
import com.invoiceflow.invoice_service.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ValidationService {

    static final double CONFIDENCE_THRESHOLD = 0.70;

    static final Set<InvoiceStatus> LIVE_STATUSES = EnumSet.of(
            InvoiceStatus.EXTRACTED,
            InvoiceStatus.PENDING_APPROVAL,
            InvoiceStatus.APPROVED
    );

    private final InvoiceRepository invoiceRepository;

    public List<String> validateExtracted(Invoice invoice, ExtractionFields extraction) {
        List<String> errors = new ArrayList<>();

        validateRequiredExtractedField(errors, "supplierName", extraction == null ? null : extraction.supplierName());
        validateRequiredExtractedField(errors, "invoiceNumber", extraction == null ? null : extraction.invoiceNumber());
        validateRequiredExtractedField(errors, "invoiceDate", extraction == null ? null : extraction.invoiceDate());
        validateRequiredExtractedField(errors, "totalGross", extraction == null ? null : extraction.totalGross());

        validateDuplicate(errors, invoice);

        return errors;
    }

    public List<String> validateCorrected(Invoice invoice) {
        List<String> errors = new ArrayList<>();

        if (invoice.getSupplier() == null || invoice.getSupplier().getName() == null
                || invoice.getSupplier().getName().isBlank()) {
            errors.add("supplierName fehlt");
        }

        if (invoice.getInvoiceNumber() == null || invoice.getInvoiceNumber().isBlank()) {
            errors.add("invoiceNumber fehlt");
        }

        if (invoice.getInvoiceDate() == null) {
            errors.add("invoiceDate fehlt");
        }

        if (invoice.getTotalGross() == null) {
            errors.add("totalGross fehlt");
        }

        validateDuplicate(errors, invoice);

        return errors;
    }

    private void validateRequiredExtractedField(
            List<String> errors,
            String fieldName,
            ExtractedField field
    ) {
        if (field == null || field.value() == null || field.value().isBlank()) {
            errors.add(fieldName + " fehlt");
            return;
        }

        if (field.confidence() < CONFIDENCE_THRESHOLD) {
            errors.add(fieldName + " Konfidenz zu niedrig");
        }
    }

    private void validateDuplicate(List<String> errors, Invoice invoice) {
        if (invoice.getSupplier() == null || invoice.getInvoiceNumber() == null
                || invoice.getInvoiceNumber().isBlank()) {
            return;
        }

        boolean duplicateExists = invoiceRepository.existsBySupplierAndInvoiceNumberAndStatusInAndIdNot(
                invoice.getSupplier(),
                invoice.getInvoiceNumber(),
                LIVE_STATUSES,
                invoice.getId()
        );

        if (duplicateExists) {
            errors.add("Dublette: Lieferant + Nummer existiert");
        }
    }
}
