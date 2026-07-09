package com.invoiceflow.invoice_service.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
}
