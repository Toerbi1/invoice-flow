package com.invoiceflow.invoice_service.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

class InvoiceTest {

    @Test
    void receiveCreatesInvoiceWithReceivedStatusAndMetadata() {
        UUID id = UUID.randomUUID();

        Invoice invoice = Invoice.receive(
                id,
                "rechnung.pdf",
                "2026/07/" + id + ".pdf",
                "application/pdf",
                1234L
        );

        assertThat(invoice.getId()).isEqualTo(id);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.RECEIVED);
        assertThat(invoice.getOriginalFilename()).isEqualTo("rechnung.pdf");
        assertThat(invoice.getDocumentKey()).isEqualTo("2026/07/" + id + ".pdf");
        assertThat(invoice.getContentType()).isEqualTo("application/pdf");
        assertThat(invoice.getSizeBytes()).isEqualTo(1234L);
        assertThat(invoice.getReceivedAt()).isNotNull();
        assertThat(invoice.getUpdatedAt()).isNull();
    }

    @Test
    void applyExtractionMovesReceivedInvoiceToExtractedAndStoresFields() {
        UUID id = UUID.randomUUID();
        Supplier supplier = new Supplier("ACME GmbH");

        Invoice invoice = Invoice.receive(
                id,
                "rechnung.pdf",
                "2026/07/" + id + ".pdf",
                "application/pdf",
                100L
        );

        invoice.applyExtraction(
                supplier,
                "RE-2026-001",
                LocalDate.of(2026, 7, 10),
                new BigDecimal("100.00"),
                new BigDecimal("119.00"),
                "EUR",
                "mongo-123"
        );

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.EXTRACTED);
        assertThat(invoice.getSupplier()).isSameAs(supplier);
        assertThat(invoice.getInvoiceNumber()).isEqualTo("RE-2026-001");
        assertThat(invoice.getInvoiceDate()).isEqualTo(LocalDate.of(2026, 7, 10));
        assertThat(invoice.getTotalNet()).isEqualByComparingTo("100.00");
        assertThat(invoice.getTotalGross()).isEqualByComparingTo("119.00");
        assertThat(invoice.getCurrency()).isEqualTo("EUR");
        assertThat(invoice.getMongoRef()).isEqualTo("mongo-123");
        assertThat(invoice.getUpdatedAt()).isNotNull();
    }

    @Test
    void markValidatedMovesExtractedInvoiceToPendingApproval() {
        Invoice invoice = Invoice.receive(
                UUID.randomUUID(),
                "rechnung.pdf",
                "2026/07/file.pdf",
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

        invoice.markValidationFailed("old error");
        invoice.applyCorrection(null, null, null, null, null, null);
        invoice.markValidated();

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(invoice.getValidationError()).isNull();
    }

    @Test
    void failExtractionMovesReceivedInvoiceToValidationFailed() {
        Invoice invoice = Invoice.receive(
                UUID.randomUUID(),
                "rechnung.pdf",
                "2026/07/file.pdf",
                "application/pdf",
                100L
        );

        invoice.failExtraction("ocr timeout");

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.VALIDATION_FAILED);
        assertThat(invoice.getValidationError()).isEqualTo("extraction failed: ocr timeout");
    }

    @Test
    void applyExtractionFromPendingApprovalThrowsIllegalStateException() {
        Invoice invoice = Invoice.receive(
                UUID.randomUUID(),
                "rechnung.pdf",
                "2026/07/file.pdf",
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
        invoice.markValidated();

        assertThatThrownBy(() -> invoice.applyExtraction(
                new Supplier("ACME GmbH"),
                "RE-2026-002",
                LocalDate.of(2026, 7, 11),
                new BigDecimal("200.00"),
                new BigDecimal("238.00"),
                "EUR",
                "mongo-456"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Illegal invoice status transition");
    }
}
