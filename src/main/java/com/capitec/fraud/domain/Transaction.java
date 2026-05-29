package com.capitec.fraud.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The canonical transaction event. Pure domain — no Spring, JPA, Jackson, Bean Validation.
 * Validation lives at API boundary in {@code api.TransactionRequest}; this is the in-engine form.
 */
public record Transaction(
        UUID txId,
        String accountId,
        BigDecimal amount,
        String currency,
        String mcc,
        Channel channel,
        String country,
        String ipCountry,
        String deviceId,
        String merchantId,
        int accountAgeDays,
        Instant timestamp
) {
    public enum Channel { WEB, MOBILE, POS, ATM, API }
}
