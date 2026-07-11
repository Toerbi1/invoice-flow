package com.invoiceflow.invoice_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "invoice")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invoice {

    private static final Map<InvoiceStatus, Set<InvoiceStatus>> ALLOWED_TRANSITIONS =
            Map.of(
                    InvoiceStatus.RECEIVED,
                    EnumSet.of(InvoiceStatus.EXTRACTED, InvoiceStatus.VALIDATION_FAILED),

                    InvoiceStatus.EXTRACTED,
                    EnumSet.of(InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.VALIDATION_FAILED),

                    InvoiceStatus.VALIDATION_FAILED,
                    EnumSet.of(InvoiceStatus.PENDING_APPROVAL),

                    InvoiceStatus.PENDING_APPROVAL,
                    EnumSet.of(InvoiceStatus.APPROVED, InvoiceStatus.REJECTED)
            );

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status;

    @Column(name = "original_filename", nullable = false, length = 512)
    private String originalFilename;

    @Column(name = "document_key", nullable = false, unique = true, length = 1024)
    private String documentKey;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;

    @Column(name = "invoice_number", length = 64)
    private String invoiceNumber;

    @Column(length = 3)
    private String currency;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "total_net")
    private BigDecimal totalNet;

    @Column(name = "total_gross")
    private BigDecimal totalGross;

    @Column(name = "validation_error", length = 1024)
    private String validationError;

    @Column(name = "mongo_ref", length = 256)
    private String mongoRef;

    public static Invoice receive(
            UUID id,
            String originalFilename,
            String documentKey,
            String contentType,
            long sizeBytes
    ) {
        Invoice invoice = new Invoice();
        invoice.id = id;
        invoice.status = InvoiceStatus.RECEIVED;
        invoice.originalFilename = originalFilename;
        invoice.documentKey = documentKey;
        invoice.contentType = contentType;
        invoice.sizeBytes = sizeBytes;
        invoice.receivedAt = Instant.now();
        return invoice;
    }

    private void transitionTo(InvoiceStatus target) {
        Set<InvoiceStatus> allowedTargets = ALLOWED_TRANSITIONS.getOrDefault(
                this.status,
                Set.of()
        );

        if (!allowedTargets.contains(target)) {
            throw new IllegalStateException(
                    "Illegal invoice status transition from " + this.status + " to " + target
            );
        }

        this.status = target;
        this.updatedAt = Instant.now();
    }

    public void applyExtraction(
            Supplier supplier,
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal totalNet,
            BigDecimal totalGross,
            String currency,
            String mongoRef
    ) {
        this.supplier = supplier;
        this.invoiceNumber = invoiceNumber;
        this.invoiceDate = invoiceDate;
        this.totalNet = totalNet;
        this.totalGross = totalGross;
        this.currency = currency;
        this.mongoRef = mongoRef;

        transitionTo(InvoiceStatus.EXTRACTED);
    }

    public void markValidated() {
        this.validationError = null;
        transitionTo(InvoiceStatus.PENDING_APPROVAL);
    }

    public void markValidationFailed(String reason) {
        this.validationError = reason;
        transitionTo(InvoiceStatus.VALIDATION_FAILED);
    }

    public void failExtraction(String error) {
        String reason = error == null || error.isBlank()
                ? "extraction failed"
                : "extraction failed: " + error;

        markValidationFailed(reason);
    }

    public void applyCorrection(
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal totalNet,
            BigDecimal totalGross,
            String currency,
            Supplier supplier
    ) {
        if (invoiceNumber != null) {
            this.invoiceNumber = invoiceNumber;
        }

        if (invoiceDate != null) {
            this.invoiceDate = invoiceDate;
        }

        if (totalNet != null) {
            this.totalNet = totalNet;
        }

        if (totalGross != null) {
            this.totalGross = totalGross;
        }

        if (currency != null) {
            this.currency = currency;
        }

        if (supplier != null) {
            this.supplier = supplier;
        }

        this.updatedAt = Instant.now();
    }
}
