package com.capitec.fraud.ingest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire format for Kafka topic {@code tx.events.v1}. Mirrors the REST
 * {@code TransactionRequest} payload so the same JSON can drive either path.
 * Validation runs in {@code TransactionKafkaListener} (manual; Bean Validation
 * is API-only).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionEvent(
        UUID txId,
        String accountId,
        BigDecimal amount,
        String currency,
        String mcc,
        String channel,
        String country,
        String ipCountry,
        String deviceId,
        String merchantId,
        Integer accountAgeDays,
        Instant timestamp
) {
}
