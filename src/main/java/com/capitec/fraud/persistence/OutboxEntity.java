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
 * Transactional outbox row. Written in the same tx as the {@link DecisionEntity}
 * so an at-least-once publication is guaranteed even if Redpanda is unreachable
 * at the time of the original ingest. The {@code OutboxPoller} drains rows
 * with {@code processed_at IS NULL} on a fixed cadence.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntity {

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

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    protected OutboxEntity() { /* JPA */ }

    public OutboxEntity(UUID id, UUID aggregateId, String eventType, Map<String, Object> payload) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.retryCount = 0;
    }

    public void markProcessed() { this.processedAt = Instant.now(); }
    public void incrementRetry() { this.retryCount++; }

    public UUID getId() { return id; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public Map<String, Object> getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }
    public int getRetryCount() { return retryCount; }
}
