package com.capitec.fraud.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionRequest(
        @NotNull UUID txId,
        @NotBlank String accountId,
        @NotNull @DecimalMin(value = "0.0", inclusive = true)
        @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be ISO-4217 uppercase, e.g. ZAR") String currency,
        @NotBlank @Pattern(regexp = "[0-9]{4}", message = "mcc must be 4 numeric digits") String mcc,
        @NotBlank @Pattern(regexp = "WEB|MOBILE|POS|ATM|API") String channel,
        @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "country must be ISO-3166-1 alpha-2 uppercase") String country,
        @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "ipCountry must be ISO-3166-1 alpha-2 uppercase") String ipCountry,
        String deviceId,
        String merchantId,
        @NotNull @Min(0) Integer accountAgeDays,
        @NotNull Instant timestamp
) {
}
