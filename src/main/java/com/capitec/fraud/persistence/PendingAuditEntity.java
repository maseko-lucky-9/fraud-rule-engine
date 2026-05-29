package com.capitec.fraud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable retry queue for audit writes that the async listener could not
 * persist (executor saturation under burst, transient DB error inside the
 * REQUIRES_NEW transaction). The original {@code audit_write_failed_total}
 * counter remains the operator alarm signal, but the table guarantees that
 * the surfaced gap is bounded by the retry poller's drain cadence rather
 * than lost forever.
 *
 * <p>{@link com.capitec.fraud.audit.AuditRetryPoller} drains rows on a
 * cadence and writes back to {@code audit_log} under
 * {@code INSERT … ON CONFLICT (resource_id, payload_hash) DO NOTHING} so a
 * successful live-path write is never duplicated by a retry-path write.
 */
@Entity
@Table(name = "audit_pending")
public class PendingAuditEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String action;

    @Column(name = "resource_id", nullable = false)
    private String resourceId;

    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    @Column(nullable = false)
    private String failure;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    protected PendingAuditEntity() { /* JPA */ }

    public PendingAuditEntity(String actor, String action, String resourceId,
                              String payloadHash, String failure) {
        this.id = UUID.randomUUID();
        this.actor = actor;
        this.action = action;
        this.resourceId = resourceId;
        this.payloadHash = payloadHash;
        this.failure = failure == null ? "unknown" : truncate(failure, 4000);
        this.createdAt = Instant.now();
        this.retryCount = 0;
    }

    public void incrementRetry() { this.retryCount++; }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    public UUID getId() { return id; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getResourceId() { return resourceId; }
    public String getPayloadHash() { return payloadHash; }
    public String getFailure() { return failure; }
    public Instant getCreatedAt() { return createdAt; }
    public int getRetryCount() { return retryCount; }
}
