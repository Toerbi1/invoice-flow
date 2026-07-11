package com.invoiceflow.invoice_service.web;

import com.invoiceflow.invoice_service.domain.Invoice;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface InvoiceMapper {

    @Mapping(target = "supplierName", source = "supplier.name")
    InvoiceResponse toResponse(Invoice invoice);
}
