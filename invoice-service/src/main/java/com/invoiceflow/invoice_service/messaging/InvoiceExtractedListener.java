package com.invoiceflow.invoice_service.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoiceflow.invoice_service.messaging.extracted.ExtractedEnvelope;
import com.invoiceflow.invoice_service.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InvoiceExtractedListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(InvoiceExtractedListener.class);

    private final ObjectMapper objectMapper;
    private final InvoiceService invoiceService;

    @KafkaListener(topics = "invoice.extracted", groupId = "invoice-service")
    public void onExtracted(String message) {
        try {
            ExtractedEnvelope envelope = objectMapper.readValue(message, ExtractedEnvelope.class);

            if (envelope.payload() == null) {
                LOGGER.warn("Received invoice.extracted event without payload");
                return;
            }

            invoiceService.applyExtractedEvent(envelope.payload());

        }
        catch (JsonProcessingException exception) {
            LOGGER.warn("Could not parse invoice.extracted event: {}", message, exception);
        } catch (RuntimeException exception) {
            LOGGER.error("Could not process invoice.extracted event", exception);
        }
    }
}
