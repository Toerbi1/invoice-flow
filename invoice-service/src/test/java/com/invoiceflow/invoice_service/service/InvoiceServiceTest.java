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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InvoiceServiceTest {

    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
    private final ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);
    private final SupplierService supplierService = mock(SupplierService.class);
    private final ValidationService validationService = mock(ValidationService.class);
    private final InvoiceEventPublisher invoiceEventPublisher = mock(InvoiceEventPublisher.class);

    private final InvoiceService invoiceService = new InvoiceService(
            invoiceRepository,
            objectStorageService,
            applicationEventPublisher,
            supplierService,
            validationService,
            invoiceEventPublisher
    );

    @Test
    void findAllReturnsInvoicesFromRepository() {
        Invoice invoice = Invoice.receive(
                UUID.randomUUID(),
                "rechnung.pdf",
                "2026/07/file.pdf",
                "application/pdf",
                10L
        );

        when(invoiceRepository.findAll()).thenReturn(List.of(invoice));

        List<Invoice> result = invoiceService.findAll();

        assertThat(result).containsExactly(invoice);
    }

    @Test
    void findByIdReturnsInvoiceWhenItExists() {
        UUID id = UUID.randomUUID();

        Invoice invoice = Invoice.receive(
                id,
                "rechnung.pdf",
                "2026/07/" + id + ".pdf",
                "application/pdf",
                10L
        );

        when(invoiceRepository.findById(id)).thenReturn(Optional.of(invoice));

        Invoice result = invoiceService.findById(id);

        assertThat(result).isSameAs(invoice);
    }

    @Test
    void findByIdThrowsWhenInvoiceDoesNotExist() {
        UUID id = UUID.randomUUID();

        when(invoiceRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.findById(id))
                .isInstanceOf(InvoiceNotFoundException.class)
                .hasMessage("Invoice not found: " + id);
    }

    @Test
    void uploadStoresPdfPersistsInvoiceAndPublishesDomainEvent() {
        byte[] content = "%PDF- test invoice".getBytes();

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "rechnung.pdf",
                "application/pdf",
                content
        );

        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Invoice result = invoiceService.upload(file);

        assertThat(result.getStatus().name()).isEqualTo("RECEIVED");
        assertThat(result.getOriginalFilename()).isEqualTo("rechnung.pdf");
        assertThat(result.getContentType()).isEqualTo("application/pdf");
        assertThat(result.getSizeBytes()).isEqualTo((long) content.length);
        assertThat(result.getDocumentKey()).endsWith(result.getId() + ".pdf");

        verify(objectStorageService).putObject(
                result.getDocumentKey(),
                content,
                "application/pdf"
        );

        verify(invoiceRepository).save(any(Invoice.class));

        ArgumentCaptor<InvoiceReceivedDomainEvent> eventCaptor =
                ArgumentCaptor.forClass(InvoiceReceivedDomainEvent.class);

        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());

        assertThat(eventCaptor.getValue().invoice()).isSameAs(result);
    }

    @Test
    void uploadRejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "empty.pdf",
                "application/pdf",
                new byte[0]
        );

        assertThatThrownBy(() -> invoiceService.upload(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Uploaded file must not be empty");

        verifyNoInteractions(objectStorageService);
        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void uploadRejectsNonPdfContentType() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "rechnung.txt",
                "text/plain",
                "hello".getBytes()
        );

        assertThatThrownBy(() -> invoiceService.upload(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Uploaded file must be a PDF");

        verifyNoInteractions(objectStorageService);
        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void uploadRejectsPdfWithoutPdfHeader() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "fake.pdf",
                "application/pdf",
                "hello".getBytes()
        );

        assertThatThrownBy(() -> invoiceService.upload(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Uploaded file content is not a valid PDF");

        verifyNoInteractions(objectStorageService);
        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void applyExtractedEventMarksInvoicePendingApprovalWhenValidationSucceeds() {
        UUID invoiceId = UUID.randomUUID();

        Invoice invoice = Invoice.receive(
                invoiceId,
                "rechnung.pdf",
                "2026/07/" + invoiceId + ".pdf",
                "application/pdf",
                100L
        );

        Supplier supplier = new Supplier("ACME GmbH");

        ExtractedPayload payload = validExtractedPayload(invoiceId);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(supplierService.resolve("ACME GmbH")).thenReturn(supplier);
        when(validationService.validateExtracted(any(Invoice.class), any(ExtractionFields.class)))
                .thenReturn(List.of());
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        invoiceService.applyExtractedEvent(payload);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(invoice.getSupplier()).isSameAs(supplier);
        assertThat(invoice.getInvoiceNumber()).isEqualTo("RE-2026-001");
        assertThat(invoice.getInvoiceDate()).isEqualTo(LocalDate.of(2026, 7, 10));
        assertThat(invoice.getTotalNet()).isEqualByComparingTo("100.00");
        assertThat(invoice.getTotalGross()).isEqualByComparingTo("119.00");
        assertThat(invoice.getCurrency()).isEqualTo("EUR");
        assertThat(invoice.getMongoRef()).isEqualTo("mongo-123");

        verify(invoiceEventPublisher).publishValidated(invoice);
    }

    @Test
    void applyExtractedEventMarksInvoiceValidationFailedWhenExtractionFailed() {
        UUID invoiceId = UUID.randomUUID();

        Invoice invoice = Invoice.receive(
                invoiceId,
                "rechnung.pdf",
                "2026/07/" + invoiceId + ".pdf",
                "application/pdf",
                100L
        );

        ExtractedPayload payload = new ExtractedPayload(
                invoiceId.toString(),
                invoice.getDocumentKey(),
                "EXTRACTION_FAILED",
                null,
                "mongo-123",
                "ocr timeout"
        );

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        invoiceService.applyExtractedEvent(payload);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.VALIDATION_FAILED);
        assertThat(invoice.getValidationError()).isEqualTo("extraction failed: ocr timeout");

        verify(invoiceEventPublisher).publishValidated(invoice);
        verifyNoInteractions(supplierService);
        verifyNoInteractions(validationService);
    }

    @Test
    void applyExtractedEventIgnoresAlreadyProcessedInvoice() {
        UUID invoiceId = UUID.randomUUID();

        Invoice invoice = Invoice.receive(
                invoiceId,
                "rechnung.pdf",
                "2026/07/" + invoiceId + ".pdf",
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

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        invoiceService.applyExtractedEvent(validExtractedPayload(invoiceId));

        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceEventPublisher);
    }

    @Test
    void correctMovesValidationFailedInvoiceToPendingApprovalWhenValid() {
        UUID invoiceId = UUID.randomUUID();

        Invoice invoice = Invoice.receive(
                invoiceId,
                "rechnung.pdf",
                "2026/07/" + invoiceId + ".pdf",
                "application/pdf",
                100L
        );
        invoice.failExtraction("ocr timeout");

        Supplier supplier = new Supplier("ACME GmbH");

        InvoiceCorrectionRequest request = new InvoiceCorrectionRequest(
                "ACME GmbH",
                "RE-2026-001",
                LocalDate.of(2026, 7, 10),
                new BigDecimal("100.00"),
                new BigDecimal("119.00"),
                "EUR"
        );

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(supplierService.resolve("ACME GmbH")).thenReturn(supplier);
        when(validationService.validateCorrected(invoice)).thenReturn(List.of());
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Invoice result = invoiceService.correct(invoiceId, request);

        assertThat(result.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(result.getValidationError()).isNull();
        assertThat(result.getSupplier()).isSameAs(supplier);
        assertThat(result.getInvoiceNumber()).isEqualTo("RE-2026-001");

        verify(invoiceEventPublisher).publishValidated(invoice);
    }

    @Test
    void correctThrowsWhenInvoiceIsNotValidationFailed() {
        UUID invoiceId = UUID.randomUUID();

        Invoice invoice = Invoice.receive(
                invoiceId,
                "rechnung.pdf",
                "2026/07/" + invoiceId + ".pdf",
                "application/pdf",
                100L
        );

        InvoiceCorrectionRequest request = new InvoiceCorrectionRequest(
                "ACME GmbH",
                "RE-2026-001",
                LocalDate.of(2026, 7, 10),
                new BigDecimal("100.00"),
                new BigDecimal("119.00"),
                "EUR"
        );

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> invoiceService.correct(invoiceId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invoice can only be corrected in status VALIDATION_FAILED");

        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceEventPublisher);
    }

    // -------------------- HELPER --------------------

    private ExtractedPayload validExtractedPayload(UUID invoiceId) {
        return new ExtractedPayload(
                invoiceId.toString(),
                "2026/07/" + invoiceId + ".pdf",
                "EXTRACTED",
                new ExtractionFields(
                        new ExtractedField("ACME GmbH", 1.0),
                        new ExtractedField("RE-2026-001", 1.0),
                        new ExtractedField("2026-07-10", 1.0),
                        new ExtractedField("EUR", 1.0),
                        new ExtractedField("100.00", 1.0),
                        new ExtractedField("119.00", 1.0)
                ),
                "mongo-123",
                null
        );
    }
}
