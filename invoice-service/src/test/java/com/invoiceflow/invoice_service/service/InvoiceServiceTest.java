package com.invoiceflow.invoice_service.service;

import com.invoiceflow.invoice_service.domain.Invoice;
import com.invoiceflow.invoice_service.repository.InvoiceRepository;
import com.invoiceflow.invoice_service.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

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

    private final InvoiceService invoiceService = new InvoiceService(
            invoiceRepository,
            objectStorageService,
            applicationEventPublisher
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
}
