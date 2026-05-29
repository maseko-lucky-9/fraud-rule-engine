package com.capitec.fraud.publish;

import com.capitec.fraud.ingest.KafkaTopics;
import com.capitec.fraud.persistence.OutboxDltEntity;
import com.capitec.fraud.persistence.OutboxDltRepository;
import com.capitec.fraud.persistence.OutboxEntity;
import com.capitec.fraud.persistence.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Drains {@code outbox} rows to Kafka. Runs every {@code app.outbox.poll-interval-ms}
 * milliseconds.
 *
 * <p>Multi-instance safe: the underlying query uses
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}, so concurrent replicas pick
 * disjoint row subsets. Each batch is published in parallel — all sends are
 * dispatched, then the poller waits up to {@code app.outbox.publish-timeout-ms}
 * for all of them collectively. Rows whose send succeeded are marked
 * processed; rows whose send failed (or timed out) have their {@code
 * retry_count} bumped and remain pending for the next tick.
 *
 * <p>Once {@code retry_count} crosses {@code app.outbox.max-retries}, the
 * row is routed atomically to {@link OutboxDltEntity outbox_dlt} and deleted
 * from {@code outbox}. Without this guard a permanently-unpublishable
 * payload (schema drift, broker rejection, contract break) would loop
 * forever, bloating the live table and drowning the metrics signal.
 *
 * <p>Overlap protection comes from {@code @Scheduled(fixedDelay)} itself —
 * the scheduler waits for the previous tick to complete before starting the
 * next, so overlapping ticks are impossible by design. The per-batch
 * publish wait is bounded by {@code app.outbox.publish-timeout-ms}
 * (default 5s) inside {@link #drainAsync()}; that timeout is the actual
 * broker-down guard, so a slow Kafka broker cannot park the scheduled
 * thread beyond it.
 *
 * <p>Idempotent producer + at-least-once semantics. Consumers of
 * {@code tx.decisions.v1} must dedup on {@code decisionId} (the outbox row id
 * is the message key).
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRepository outboxRepo;
    private final OutboxDltRepository outboxDltRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final int batchSize;
    private final long publishTimeoutMs;
    private final int maxRetries;
    private final Counter dltTotal;

    public OutboxPoller(OutboxRepository outboxRepo,
                        OutboxDltRepository outboxDltRepo,
                        KafkaTemplate<String, Object> kafkaTemplate,
                        MeterRegistry metrics,
                        @Value("${app.outbox.batch-size:100}") int batchSize,
                        @Value("${app.outbox.publish-timeout-ms:5000}") long publishTimeoutMs,
                        @Value("${app.outbox.max-retries:10}") int maxRetries) {
        this.outboxRepo = outboxRepo;
        this.outboxDltRepo = outboxDltRepo;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
        this.publishTimeoutMs = publishTimeoutMs;
        this.maxRetries = maxRetries;
        this.dltTotal = Counter.builder("outbox_dlt_total")
                .description("Outbox rows routed to outbox_dlt after exhausting retries")
                .register(metrics);
        // Oldest-pending gauge: aggregate but cheap (single query per poll).
        metrics.gauge("outbox_pending_oldest_seconds", this,
                p -> p.oldestPendingAgeSeconds());
    }

    /**
     * Scheduler entrypoint AND transactional boundary in one method.
     *
     * <p>The split-method pattern (drain delegating to drainAsync) bypassed
     * the Spring @Transactional proxy because the call was internal to the
     * same bean — `this.drainAsync()` skipped the AOP interception, leaving
     * the writes outside a transaction. Inlining keeps the proxy on the
     * scheduler-invoked entrypoint, which is the only one Spring sees.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    @Transactional
    public void drain() {
        List<OutboxEntity> batch = outboxRepo.findPendingForUpdate(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("outbox: draining {} pending rows (lock held until commit)", batch.size());

        // Dispatch every send in parallel so a single slow row can't stall the batch.
        Map<OutboxEntity, CompletableFuture<?>> inflight = new HashMap<>(batch.size());
        Map<OutboxEntity, String> lastErrors = new HashMap<>();
        for (OutboxEntity row : batch) {
            CompletableFuture<?> future = kafkaTemplate
                    .send(KafkaTopics.DECISIONS_OUT, row.getAggregateId().toString(), row.getPayload())
                    .toCompletableFuture();
            inflight.put(row, future);
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(inflight.values().toArray(new CompletableFuture[0]));
        try {
            all.get(publishTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ex) {
            log.warn("outbox: batch publish wait failed — settling per-row. cause={}", ex.toString());
        }

        // Per-row settlement: mark processed if its future completed normally; bump retry otherwise.
        // Rows that have crossed the retry threshold are routed to the DLT in the same transaction.
        List<UUID> succeeded = new ArrayList<>();
        int routedToDlt = 0;
        for (Map.Entry<OutboxEntity, CompletableFuture<?>> e : inflight.entrySet()) {
            OutboxEntity row = e.getKey();
            CompletableFuture<?> f = e.getValue();
            if (f.isDone() && !f.isCompletedExceptionally()) {
                row.markProcessed();
                succeeded.add(row.getId());
            } else {
                row.incrementRetry();
                String why = describeFailure(f);
                lastErrors.put(row, why);
                f.cancel(true);
                if (row.getRetryCount() >= maxRetries) {
                    routeToDlt(row, why);
                    routedToDlt++;
                } else {
                    log.debug("outbox: row {} stays pending (attempt {})",
                            row.getId(), row.getRetryCount());
                }
            }
        }
        if (!succeeded.isEmpty()) {
            log.debug("outbox: marked {} rows processed", succeeded.size());
        }
        if (routedToDlt > 0) {
            log.warn("outbox: routed {} row(s) to outbox_dlt (>= max-retries={})", routedToDlt, maxRetries);
        }
    }

    /**
     * Persists the dead-letter row and deletes the source row in the SAME
     * transaction so a crash mid-route can never lose the payload. The
     * outbox row is removed last; if the dead-letter insert fails, the
     * delete never runs and the row stays in the live table for the next
     * tick.
     */
    void routeToDlt(OutboxEntity row, String lastError) {
        outboxDltRepo.save(new OutboxDltEntity(row, lastError));
        outboxRepo.delete(row);
        dltTotal.increment();
    }

    /** Best-effort cause extraction without re-blocking on the future. */
    private static String describeFailure(CompletableFuture<?> f) {
        if (!f.isDone()) {
            return "timeout-waiting-for-broker";
        }
        try {
            f.getNow(null);
            return "unknown";
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }

    private long oldestPendingAgeSeconds() {
        return outboxRepo.findPendingForUpdate(1).stream()
                .findFirst()
                .map(OutboxEntity::getCreatedAt)
                .map(t -> Duration.between(t, Instant.now()).toSeconds())
                .orElse(0L);
    }
}
