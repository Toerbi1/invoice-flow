package com.invoiceflow.invoice_service.messaging;

import com.invoiceflow.invoice_service.service.InvoiceReceivedDomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class InvoiceReceivedEventListener {

    private final InvoiceEventPublisher invoiceEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvoiceReceived(InvoiceReceivedDomainEvent event) {
        invoiceEventPublisher.publishReceived(event.invoice());
    }

}
