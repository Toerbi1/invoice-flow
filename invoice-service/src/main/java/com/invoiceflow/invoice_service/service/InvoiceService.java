package com.invoiceflow.invoice_service.service;

import com.invoiceflow.invoice_service.domain.Invoice;
import com.invoiceflow.invoice_service.repository.InvoiceRepository;
import com.invoiceflow.invoice_service.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final ObjectStorageService objectStorageService;
    private final ApplicationEventPublisher applicationEventPublisher;

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
}
