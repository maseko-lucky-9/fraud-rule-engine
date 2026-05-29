package com.capitec.fraud.audit;

import com.capitec.fraud.persistence.PendingAuditEntity;
import com.capitec.fraud.persistence.PendingAuditRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the {@link AuditEntity} row asynchronously so the calling
 * transaction (e.g. an ingest) is not blocked by audit-log write latency.
 * The audit write opens its own {@link Propagation#REQUIRES_NEW} transaction.
 *
 * <p>Failure observability (Day-4.5 review item #5): if the async write
 * fails — DB down, executor queue overflow, deserialisation error — we
 * log {@code ERROR} (not warn) and bump {@code audit_write_failed_total}
 * so alerting can fire. Banking auditor must NEVER see a successful
 * business transaction with no corresponding audit row; this counter is
 * the smoke alarm. A full durable retry queue is documented as Day-7
 * polish in ADR-0007.
 */
@Component
public class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

    private final AuditRepository repo;
    private final PendingAuditRepository pendingRepo;
    private final Counter writeFailed;
    private final Counter writePendingPersisted;

    public AuditEventListener(AuditRepository repo,
                              PendingAuditRepository pendingRepo,
                              MeterRegistry metrics) {
        this.repo = repo;
        this.pendingRepo = pendingRepo;
        this.writeFailed = Counter.builder("audit_write_failed_total")
                .description("Audit log writes that failed after the business transaction committed")
                .register(metrics);
        this.writePendingPersisted = Counter.builder("audit_write_pending_persisted_total")
                .description("Audit events written to audit_pending after the live write failed")
                .register(metrics);
    }

    @EventListener
    @Async("auditExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(AuditEvent event) {
        try {
            repo.save(new AuditEntity(event.actor(), event.action(),
                    event.resourceId(), event.payloadHash()));
        } catch (RuntimeException ex) {
            writeFailed.increment();
            log.error("audit-write FAILED action={} resourceId={} actor={} cause={} — falling back to audit_pending retry queue",
                    event.action(), event.resourceId(), event.actor(), ex.toString(), ex);
            // Durable fallback: the AuditRetryPoller drains audit_pending
            // back into audit_log under ON CONFLICT (resource_id, payload_hash)
            // DO NOTHING so a successful retry never duplicates a live-path
            // write.
            try {
                pendingRepo.save(new PendingAuditEntity(
                        event.actor(), event.action(), event.resourceId(),
                        event.payloadHash(), ex.toString()));
                writePendingPersisted.increment();
            } catch (RuntimeException pendingEx) {
                log.error("audit-write FAILED to audit_pending too — audit row lost! actor={} resourceId={} cause={}",
                        event.actor(), event.resourceId(), pendingEx.toString(), pendingEx);
                throw pendingEx;
            }
        }
    }
}
