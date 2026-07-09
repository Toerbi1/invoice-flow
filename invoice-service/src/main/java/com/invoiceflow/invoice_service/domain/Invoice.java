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

import java.time.Instant;
import java.util.EnumMap;
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
            new EnumMap<>(InvoiceStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(InvoiceStatus.RECEIVED, EnumSet.of(InvoiceStatus.EXTRACTING));
        ALLOWED_TRANSITIONS.put(InvoiceStatus.EXTRACTING, EnumSet.of(InvoiceStatus.EXTRACTED));
        ALLOWED_TRANSITIONS.put(
                InvoiceStatus.EXTRACTED,
                EnumSet.of(InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.VALIDATION_FAILED)
        );
        ALLOWED_TRANSITIONS.put(
                InvoiceStatus.VALIDATION_FAILED,
                EnumSet.of(InvoiceStatus.PENDING_APPROVAL)
        );
        ALLOWED_TRANSITIONS.put(
                InvoiceStatus.PENDING_APPROVAL,
                EnumSet.of(InvoiceStatus.APPROVED, InvoiceStatus.REJECTED)
        );
        ALLOWED_TRANSITIONS.put(InvoiceStatus.APPROVED, EnumSet.noneOf(InvoiceStatus.class));
        ALLOWED_TRANSITIONS.put(InvoiceStatus.REJECTED, EnumSet.noneOf(InvoiceStatus.class));
    }

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
                EnumSet.noneOf(InvoiceStatus.class)
        );

        if (!allowedTargets.contains(target)) {
            throw new IllegalStateException(
                    "Invalid invoice status transition from " + this.status + " to " + target
            );
        }

        this.status = target;
        this.updatedAt = Instant.now();
    }
}
