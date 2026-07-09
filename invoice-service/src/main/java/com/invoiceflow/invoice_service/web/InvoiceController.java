package com.invoiceflow.invoice_service.web;

import com.invoiceflow.invoice_service.domain.Invoice;
import com.invoiceflow.invoice_service.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final InvoiceMapper invoiceMapper;

    @GetMapping
    public List<InvoiceResponse> list() {
        return invoiceService.findAll()
                .stream()
                .map(invoiceMapper::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public InvoiceResponse get(@PathVariable UUID id) {
        return invoiceMapper.toResponse(invoiceService.findById(id));
    }

    @PostMapping
    public ResponseEntity<InvoiceResponse> upload(@RequestParam("file")MultipartFile file) {
        Invoice invoice = invoiceService.upload(file);
        InvoiceResponse response = invoiceMapper.toResponse(invoice);

        URI location = URI.create("api/invoices/" + invoice.getId());

        return ResponseEntity
                .created(location)
                .body(response);
    }
}
