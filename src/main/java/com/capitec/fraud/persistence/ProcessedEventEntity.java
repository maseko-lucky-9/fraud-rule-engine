package com.capitec.fraud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Consumer-side idempotency table. A unique-constraint violation on
 * {@code (event_id, consumer_id)} signals "this event was already processed
 * by this consumer" and the ingest service swallows it.
 *
 * <p>Implements {@link Persistable} with {@code isNew()=true} so Spring Data
 * always uses {@code EntityManager#persist} (not merge) — which throws on
 * primary-key collision instead of silently UPDATE-ing the existing row.
 * Without this, the dedup is broken for {@code @EmbeddedId} entities where
 * the {@code save()} heuristic can't tell new from existing.
 *
 * <p>Two distinct consumers ("rest-api" and "kafka-tx-events") can each
 * process the same event independently — the REST façade and the Kafka
 * pipeline operate on disjoint identities.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEventEntity implements Persistable<ProcessedEventEntity.ProcessedEventId> {

    @EmbeddedId
    private ProcessedEventId id;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEventEntity() { /* JPA */ }

    public ProcessedEventEntity(UUID eventId, String consumerId) {
        this.id = new ProcessedEventId(eventId, consumerId);
        this.processedAt = Instant.now();
    }

    @Override
    public ProcessedEventId getId() { return id; }

    @Override
    public boolean isNew() {
        // We never update processed_events rows; every save MUST be an insert.
        return true;
    }

    public Instant getProcessedAt() { return processedAt; }

    @Embeddable
    public record ProcessedEventId(
            @Column(name = "event_id") UUID eventId,
            @Column(name = "consumer_id") String consumerId
    ) implements Serializable {
        public ProcessedEventId {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(consumerId);
        }
    }
}
