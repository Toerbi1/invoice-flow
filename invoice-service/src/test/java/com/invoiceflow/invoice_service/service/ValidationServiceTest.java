package com.invoiceflow.invoice_service.service;

import com.invoiceflow.invoice_service.domain.Invoice;
import com.invoiceflow.invoice_service.domain.InvoiceStatus;
import com.invoiceflow.invoice_service.domain.Supplier;
import com.invoiceflow.invoice_service.messaging.extracted.ExtractedField;
import com.invoiceflow.invoice_service.messaging.extracted.ExtractionFields;
import com.invoiceflow.invoice_service.repository.InvoiceRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ValidationServiceTest {

    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final ValidationService validationService = new ValidationService(invoiceRepository);

    @Test
    void validateExtractedReturnsNoErrorsForValidExtraction() {
        Invoice invoice = extractedInvoice();

        ExtractionFields extraction = new ExtractionFields(
                new ExtractedField("ACME GmbH", 1.0),
                new ExtractedField("RE-2026-001", 1.0),
                new ExtractedField("2026-07-10", 1.0),
                new ExtractedField("EUR", 1.0),
                new ExtractedField("100.00", 1.0),
                new ExtractedField("119.00", 1.0)
        );

        when(invoiceRepository.existsBySupplierAndInvoiceNumberAndStatusInAndIdNot(
                eq(invoice.getSupplier()),
                eq(invoice.getInvoiceNumber()),
                any(Collection.class),
                eq(invoice.getId())
        )).thenReturn(false);

        assertThat(validationService.validateExtracted(invoice, extraction)).isEmpty();
    }

    @Test
    void validateExtractedReturnsErrorsForMissingRequiredFields() {
        Invoice invoice = extractedInvoice();

        ExtractionFields extraction = new ExtractionFields(
                null,
                new ExtractedField("", 1.0),
                null,
                new ExtractedField("EUR", 1.0),
                new ExtractedField("100.00", 1.0),
                null
        );

        assertThat(validationService.validateExtracted(invoice, extraction))
                .containsExactlyInAnyOrder(
                        "supplierName fehlt",
                        "invoiceNumber fehlt",
                        "invoiceDate fehlt",
                        "totalGross fehlt"
                );
    }

    @Test
    void validateExtractedReturnsErrorsForLowConfidence() {
        Invoice invoice = extractedInvoice();

        ExtractionFields extraction = new ExtractionFields(
                new ExtractedField("ACME GmbH", 0.69),
                new ExtractedField("RE-2026-001", 0.69),
                new ExtractedField("2026-07-10", 0.69),
                new ExtractedField("EUR", 1.0),
                new ExtractedField("100.00", 1.0),
                new ExtractedField("119.00", 0.69)
        );

        assertThat(validationService.validateExtracted(invoice, extraction))
                .containsExactlyInAnyOrder(
                        "supplierName Konfidenz zu niedrig",
                        "invoiceNumber Konfidenz zu niedrig",
                        "invoiceDate Konfidenz zu niedrig",
                        "totalGross Konfidenz zu niedrig"
                );
    }

    @Test
    void validateCorrectedDoesNotCheckConfidence() {
        Invoice invoice = extractedInvoice();

        when(invoiceRepository.existsBySupplierAndInvoiceNumberAndStatusInAndIdNot(
                eq(invoice.getSupplier()),
                eq(invoice.getInvoiceNumber()),
                any(Collection.class),
                eq(invoice.getId())
        )).thenReturn(false);

        assertThat(validationService.validateCorrected(invoice)).isEmpty();
    }

    @Test
    void validateCorrectedReturnsDuplicateError() {
        Invoice invoice = extractedInvoice();

        when(invoiceRepository.existsBySupplierAndInvoiceNumberAndStatusInAndIdNot(
                eq(invoice.getSupplier()),
                eq(invoice.getInvoiceNumber()),
                any(Collection.class),
                eq(invoice.getId())
        )).thenReturn(true);

        assertThat(validationService.validateCorrected(invoice))
                .containsExactly("Dublette: Lieferant + Nummer existiert");
    }

    private Invoice extractedInvoice() {
        UUID id = UUID.randomUUID();

        Invoice invoice = Invoice.receive(
                id,
                "rechnung.pdf",
                "2026/07/" + id + ".pdf",
                "application/pdf",
                100L
        );

        invoice.applyExtraction(
                new Supplier("ACME GmbH"),
                "RE-2026-001",
                LocalDate.of(2026, 7, 10),
                new BigDecimal("100.00"),
                new BigDecimal("119.00"),
                "EUR",
                "mongo-123"
        );

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.EXTRACTED);

        return invoice;
    }
}
