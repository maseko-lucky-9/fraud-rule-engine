package com.capitec.fraud.audit;

import com.capitec.fraud.persistence.PendingAuditEntity;
import com.capitec.fraud.persistence.PendingAuditRepository;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Drains {@code audit_pending} back into {@code audit_log}. Runs every
 * {@code app.audit.retry-interval-ms} (default 2s).
 *
 * <p>Each row is promoted via a single
 * {@code INSERT … ON CONFLICT (resource_id, payload_hash) DO NOTHING}
 * so a successful live-path write is never duplicated by a retry. The
 * pending row is deleted regardless of whether the conflict fired — the
 * idempotency guarantee is that the {@code audit_log} ends up with exactly
 * one row per {@code (resource_id, payload_hash)} regardless of which
 * path got it there.
 *
 * <p>{@code @Bulkhead("audit-retry")} prevents overlapping drains on the
 * same replica. Multi-replica deployments share the queue safely because
 * {@link PendingAuditRepository#findDrainCandidates} uses
 * {@code FOR UPDATE SKIP LOCKED}.
 */
@Component
public class AuditRetryPoller {

    private static final Logger log = LoggerFactory.getLogger(AuditRetryPoller.class);

    private final PendingAuditRepository pendingRepo;
    private final int batchSize;
    private final Counter promotedCounter;
    private final Counter giveUpCounter;
    private final int maxAttempts;

    public AuditRetryPoller(PendingAuditRepository pendingRepo,
                            MeterRegistry meters,
                            @Value("${app.audit.retry-batch-size:50}") int batchSize,
                            @Value("${app.audit.retry-max-attempts:20}") int maxAttempts) {
        this.pendingRepo = pendingRepo;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.promotedCounter = Counter.builder("audit_pending_promoted_total")
                .description("audit_pending rows successfully promoted (or deduped) into audit_log")
                .register(meters);
        this.giveUpCounter = Counter.builder("audit_pending_give_up_total")
                .description("audit_pending rows abandoned after retry-max-attempts (lost audit)")
                .register(meters);
        // Expose pending-queue depth so the dashboard alarms before the
        // queue grows out of operational bounds.
        meters.gauge("audit_pending_size", pendingRepo, PendingAuditRepository::count);
    }

    @Scheduled(fixedDelayString = "${app.audit.retry-interval-ms:2000}")
    public void drain() {
        try {
            drainBatch();
        } catch (RuntimeException ex) {
            log.warn("audit-retry: drain returned exceptionally — rows stay pending. cause={}",
                    ex.toString());
        }
    }

    @Bulkhead(name = "audit-retry")
    @Transactional
    public void drainBatch() {
        List<PendingAuditEntity> batch = pendingRepo.findDrainCandidates(batchSize);
        if (batch.isEmpty()) return;

        int promoted = 0;
        int gaveUp = 0;
        for (PendingAuditEntity row : batch) {
            try {
                pendingRepo.promoteToAuditLog(row.getId());
                pendingRepo.delete(row);
                promoted++;
            } catch (RuntimeException ex) {
                row.incrementRetry();
                if (row.getRetryCount() >= maxAttempts) {
                    log.error("audit-retry: giving up on pending row id={} after {} attempts cause={}",
                            row.getId(), row.getRetryCount(), ex.toString());
                    pendingRepo.delete(row);
                    gaveUp++;
                } else {
                    log.warn("audit-retry: row id={} attempt {} failed — staying pending. cause={}",
                            row.getId(), row.getRetryCount(), ex.toString());
                }
            }
        }
        if (promoted > 0) promotedCounter.increment(promoted);
        if (gaveUp > 0) giveUpCounter.increment(gaveUp);
    }
}
