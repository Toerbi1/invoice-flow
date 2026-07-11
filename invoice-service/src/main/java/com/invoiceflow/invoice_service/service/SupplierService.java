package com.invoiceflow.invoice_service.service;

import com.invoiceflow.invoice_service.domain.Supplier;
import com.invoiceflow.invoice_service.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;

    @Transactional
    public Supplier resolve(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Supplier name must not be blank");
        }

        String normalizedName = name.trim();

        return supplierRepository.findByName(normalizedName)
                .orElseGet(() -> createSupplier(normalizedName));
    }

    private Supplier createSupplier(String name) {
        try {
            return supplierRepository.save(new Supplier(name));
        } catch (DataIntegrityViolationException exception) {
            return supplierRepository.findByName(name)
                    .orElseThrow(() -> exception);
        }
    }
}
