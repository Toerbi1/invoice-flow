package com.invoiceflow.invoice_service.service;

import com.invoiceflow.invoice_service.domain.Invoice;
import com.invoiceflow.invoice_service.domain.InvoiceStatus;
import com.invoiceflow.invoice_service.domain.Supplier;
import com.invoiceflow.invoice_service.messaging.InvoiceEventPublisher;
import com.invoiceflow.invoice_service.messaging.extracted.ExtractedField;
import com.invoiceflow.invoice_service.messaging.extracted.ExtractedPayload;
import com.invoiceflow.invoice_service.messaging.extracted.ExtractionFields;
import com.invoiceflow.invoice_service.repository.InvoiceRepository;
import com.invoiceflow.invoice_service.storage.ObjectStorageService;
import com.invoiceflow.invoice_service.web.InvoiceCorrectionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final ObjectStorageService objectStorageService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SupplierService supplierService;
    private final ValidationService validationService;
    private final InvoiceEventPublisher invoiceEventPublisher;

    public List<Invoice> findAll() {
        return invoiceRepository.findAll();
    }

    public Invoice findById(UUID id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new InvoiceNotFoundException(id));
    }

    @Transactional
    public Invoice upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file must not be empty");
        }

        String contentType = file.getContentType();

        if (!"application/pdf".equalsIgnoreCase(contentType)) {
            throw new IllegalArgumentException("Uploaded file must be a PDF");
        }

        byte[] content = readFileBytes(file);

        validatePdfContent(content);

        UUID id = UUID.randomUUID();
        String originalFilename = file.getOriginalFilename();
        String documentKey = buildDocumentKey(id);

        objectStorageService.putObject(documentKey, content, contentType);

        Invoice invoice = Invoice.receive(
                id,
                originalFilename,
                documentKey,
                contentType,
                content.length
        );

        Invoice savedInvoice = invoiceRepository.save(invoice);

        applicationEventPublisher.publishEvent(new InvoiceReceivedDomainEvent(savedInvoice));

        return savedInvoice;
    }

    @Transactional
    public void applyExtractedEvent(ExtractedPayload payload) {
        UUID invoiceId = parseInvoiceId(payload.invoiceId());

        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElse(null);

        if (invoice == null) {
            return;
        }

        if (invoice.getStatus() != InvoiceStatus.RECEIVED) {
            return;
        }

        if ("EXTRACTION_FAILED".equals(payload.status())) {
            invoice.failExtraction(payload.error());
            Invoice savedInvoice = invoiceRepository.save(invoice);
            invoiceEventPublisher.publishValidated(savedInvoice);
            return;
        }

        if (!"EXTRACTED".equals(payload.status())) {
            invoice.markValidationFailed("unknown extraction status: " + payload.status());
            Invoice savedInvoice = invoiceRepository.save(invoice);
            invoiceEventPublisher.publishValidated(savedInvoice);
            return;
        }

        ExtractionFields extraction = payload.extraction();

        if (extraction == null) {
            invoice.markValidationFailed("extraction fields missing");
            Invoice savedInvoice = invoiceRepository.save(invoice);
            invoiceEventPublisher.publishValidated(savedInvoice);
            return;
        }

        Supplier supplier = supplierService.resolve(valueOf(extraction.supplierName()));

        invoice.applyExtraction(
                supplier,
                valueOf(extraction.invoiceNumber()),
                parseDate(valueOf(extraction.invoiceDate())),
                parseDecimalOrNull(valueOf(extraction.totalNet())),
                parseDecimalOrNull(valueOf(extraction.totalGross())),
                valueOf(extraction.currency()),
                payload.mongoRef()
        );

        List<String> errors = validationService.validateExtracted(invoice, extraction);

        if (errors.isEmpty()) {
            invoice.markValidated();
        } else {
            invoice.markValidationFailed(String.join("; ", errors));
        }

        Invoice savedInvoice = invoiceRepository.save(invoice);

        invoiceEventPublisher.publishValidated(savedInvoice);
    }

    @Transactional
    public Invoice correct(UUID id, InvoiceCorrectionRequest request) {
        Invoice invoice = findById(id);

        if (invoice.getStatus() != InvoiceStatus.VALIDATION_FAILED) {
            throw new IllegalStateException("Invoice can only be corrected in status VALIDATION_FAILED");
        }

        Supplier supplier = null;

        if (request.supplierName() != null && !request.supplierName().isBlank()) {
            supplier = supplierService.resolve(request.supplierName());
        }

        invoice.applyCorrection(
                request.invoiceNumber(),
                request.invoiceDate(),
                request.totalNet(),
                request.totalGross(),
                request.currency(),
                supplier
        );

        List<String> errors = validationService.validateCorrected(invoice);

        if (errors.isEmpty()) {
            invoice.markValidated();
        } else {
            invoice.markValidationFailed(String.join("; ", errors));
        }

        Invoice savedInvoice = invoiceRepository.save(invoice);

        invoiceEventPublisher.publishValidated(savedInvoice);

        return savedInvoice;
    }

    // ------------- HELPER -------------

    private byte[] readFileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Uploaded file could not be read", exception);
        }
    }

    private void validatePdfContent(byte[] content) {
        if (content.length < 5) {
            throw new IllegalArgumentException("Uploaded file content is not a valid PDF");
        }

        boolean hasPdfHeader =
                content[0] == '%'
                        && content[1] == 'P'
                        && content[2] == 'D'
                        && content[3] == 'F'
                        && content[4] == '-';

        if (!hasPdfHeader) {
            throw new IllegalArgumentException("Uploaded file content is not a valid PDF");
        }
    }

    private String buildDocumentKey(UUID invoiceId) {
        YearMonth now = YearMonth.now();

        return "%d/%02d/%s.pdf".formatted(
                now.getYear(),
                now.getMonthValue(),
                invoiceId
        );
    }

    private UUID parseInvoiceId(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid invoiceId: " + value, exception);
        }
    }

    private String valueOf(ExtractedField field) {
        if (field == null) {
            return null;
        }

        return field.value();
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return LocalDate.parse(value);
    }

    private BigDecimal parseDecimalOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return new BigDecimal(value);
    }
}
