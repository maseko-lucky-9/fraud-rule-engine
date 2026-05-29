package com.capitec.fraud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Terminal-state landing pad for outbox rows that exhausted their retry budget.
 * The {@link OutboxPoller} routes a row here once {@code retry_count} crosses
 * {@code app.outbox.max-retries}; this prevents the live outbox from
 * indefinitely accumulating poison-pill payloads.
 *
 * <p>Operators inspect this table to triage permanently-unpublishable events
 * (typically schema drift, broker-side rejections, or downstream contract
 * breaks). The shape mirrors {@link OutboxEntity} plus {@code lastError} and
 * {@code routedAt} so a manual replay is a single insert back into the live
 * table.
 */
@Entity
@Table(name = "outbox_dlt")
public class OutboxDltEntity {

    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error", nullable = false)
    private String lastError;

    @Column(name = "routed_at", nullable = false)
    private Instant routedAt;

    protected OutboxDltEntity() { /* JPA */ }

    public OutboxDltEntity(OutboxEntity source, String lastError) {
        this.id = source.getId();
        this.aggregateId = source.getAggregateId();
        this.eventType = source.getEventType();
        this.payload = source.getPayload();
        this.createdAt = source.getCreatedAt();
        this.retryCount = source.getRetryCount();
        this.lastError = lastError == null ? "unknown" : truncate(lastError, 4000);
        this.routedAt = Instant.now();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    public UUID getId() { return id; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public Map<String, Object> getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public int getRetryCount() { return retryCount; }
    public String getLastError() { return lastError; }
    public Instant getRoutedAt() { return routedAt; }
}
