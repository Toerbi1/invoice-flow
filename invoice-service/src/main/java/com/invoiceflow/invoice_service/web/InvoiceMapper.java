package com.invoiceflow.invoice_service.web;

import com.invoiceflow.invoice_service.domain.Invoice;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface InvoiceMapper {
    InvoiceResponse toResponse(Invoice invoice);
}
