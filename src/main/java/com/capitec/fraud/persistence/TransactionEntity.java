package com.capitec.fraud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class TransactionEntity {

    @Id
    @Column(name = "tx_id")
    private UUID txId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String mcc;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private com.capitec.fraud.domain.Transaction.Channel channel;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 2)
    private String country;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ip_country", nullable = false, length = 2)
    private String ipCountry;

    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "merchant_id")
    private String merchantId;

    @Column(name = "account_age_days", nullable = false)
    private int accountAgeDays;

    @Column(name = "event_ts", nullable = false)
    private Instant eventTs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @UpdateTimestamp
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected TransactionEntity() { /* JPA */ }

    public TransactionEntity(UUID txId, String accountId, BigDecimal amount, String currency,
                             String mcc, com.capitec.fraud.domain.Transaction.Channel channel,
                             String country, String ipCountry, String deviceId, String merchantId,
                             int accountAgeDays, Instant eventTs, Map<String, Object> payload) {
        this.txId = txId;
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.mcc = mcc;
        this.channel = channel;
        this.country = country;
        this.ipCountry = ipCountry;
        this.deviceId = deviceId;
        this.merchantId = merchantId;
        this.accountAgeDays = accountAgeDays;
        this.eventTs = eventTs;
        this.payload = payload;
    }

    public UUID getTxId() { return txId; }
    public String getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
}
