package com.capitec.fraud.ingest;

import com.capitec.fraud.audit.AuditEvent;
import com.capitec.fraud.domain.Decision;
import com.capitec.fraud.domain.RuleResult;
import com.capitec.fraud.domain.Transaction;
import com.capitec.fraud.engine.BoundedRuleEngineEvaluator;
import com.capitec.fraud.observability.PiiRedactor;
import com.capitec.fraud.persistence.DecisionEntity;
import com.capitec.fraud.persistence.DecisionRepository;
import com.capitec.fraud.persistence.DecisionRuleEntity;
import com.capitec.fraud.persistence.OutboxEntity;
import com.capitec.fraud.persistence.OutboxRepository;
import com.capitec.fraud.persistence.ProcessedEventEntity;
import com.capitec.fraud.persistence.ProcessedEventRepository;
import com.capitec.fraud.persistence.TransactionEntity;
import com.capitec.fraud.persistence.TransactionRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Single transactional boundary for the decision pipeline. Every successful
 * ingest writes four rows atomically:
 * <ol>
 *   <li>{@code transactions} — raw event for replay/audit;</li>
 *   <li>{@code decisions} + {@code decision_rules} — verdict + full trace;</li>
 *   <li>{@code outbox} — at-least-once publication of {@code tx.decisions.v1};</li>
 *   <li>{@code processed_events} — consumer-side dedup signal.</li>
 * </ol>
 * Either all four rows commit, or none do. A unique-constraint violation on
 * {@code processed_events} means this event was already processed by this
 * consumer; the previously-persisted decision is returned and no duplicate
 * is written.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    public static final String CONSUMER_REST = "rest-api";
    public static final String CONSUMER_KAFKA = "kafka-tx-events";

    private final BoundedRuleEngineEvaluator engine;
    private final TransactionRepository transactionRepo;
    private final DecisionRepository decisionRepo;
    private final OutboxRepository outboxRepo;
    private final ProcessedEventRepository processedEventRepo;
    private final ApplicationEventPublisher events;

    @PersistenceContext
    private EntityManager em;

    public IngestService(BoundedRuleEngineEvaluator engine,
                         TransactionRepository transactionRepo,
                         DecisionRepository decisionRepo,
                         OutboxRepository outboxRepo,
                         ProcessedEventRepository processedEventRepo,
                         ApplicationEventPublisher events) {
        this.engine = engine;
        this.transactionRepo = transactionRepo;
        this.decisionRepo = decisionRepo;
        this.outboxRepo = outboxRepo;
        this.processedEventRepo = processedEventRepo;
        this.events = events;
    }

    /**
     * Idempotent ingest, race-free in a single {@code @Transactional} boundary.
     *
     * <p>The full-overhaul review replaces the prior dual-method pattern (outer
     * non-transactional + inner transactional + DataIntegrityViolationException
     * recovery) with a single transaction backed by an {@code INSERT … ON
     * CONFLICT DO NOTHING} primitive. The previous pattern existed to dodge
     * PostgreSQL's "doomed transaction" state, where a constraint violation
     * aborts the surrounding tx and leaves the recovery read on a poisoned
     * connection. {@code ON CONFLICT} never raises, so no abort, no doomed
     * transaction — the recovery read happens in the same tx, immediately
     * after a {@code flush + clear} that forces the persistence context to
     * observe the winner's just-committed row under READ_COMMITTED.
     */
    @Transactional
    @CircuitBreaker(name = "database")
    public Decision ingest(Transaction tx, String consumerId) {
        int claimed = processedEventRepo.tryClaim(tx.txId(), consumerId);
        if (claimed == 0) {
            // Another caller (typically the parallel Kafka consumer for the
            // same event id, or a retried REST POST) won the race. Flush any
            // pending writes from this Hibernate session and clear it so the
            // following findFirstByTxIdOrderByEvaluatedAtDesc bypasses the
            // session cache and reads the winner's committed decision row.
            em.flush();
            em.clear();
            log.debug("idempotent hit tx={} account={} consumer={}",
                    tx.txId(), PiiRedactor.redactAccountId(tx.accountId()), consumerId);
            return existingDecision(tx.txId(), consumerId);
        }

        // Persist raw event for replay/audit.
        transactionRepo.save(toTransactionEntity(tx));

        // Evaluate.
        Decision decision = engine.evaluate(tx);

        // Persist decision + matched-rule trace.
        DecisionEntity de = new DecisionEntity(
                decision.decisionId(),
                decision.txId(),
                decision.accountId(),
                decision.status(),
                BigDecimal.valueOf(decision.score()).setScale(3, RoundingMode.HALF_UP),
                decision.ruleSetVersion(),
                decision.evaluatedAt()
        );
        for (RuleResult r : decision.matchedRules()) {
            de.addMatchedRule(new DecisionRuleEntity(decision.decisionId(), r.ruleId(), r.priority(), r.reason()));
        }
        decisionRepo.save(de);

        // Decision payload computed once: reused by outbox publish + audit hash.
        Map<String, Object> decisionPayload = buildDecisionPayload(decision);

        // Outbox row → polled out to tx.decisions.v1 by OutboxPoller.
        outboxRepo.save(new OutboxEntity(
                UUID.randomUUID(),
                decision.decisionId(),
                "tx.decisions.v1",
                decisionPayload
        ));

        // Audit trail — published as an event so the write happens async (REQUIRES_NEW tx)
        // without blocking the ingest hot path. consumerId is the actor. The
        // payload_hash is a SHA-256 of the canonical decision payload (NOT the
        // transaction id — that name was misleading); tampering with the
        // audit row in-place would change the row but not the hash, which the
        // audit GET endpoint can re-derive on demand.
        events.publishEvent(new AuditEvent(
                consumerId,
                "DECISION_WRITE",
                decision.decisionId().toString(),
                hashPayload(decisionPayload)));

        return decision;
    }

    private static String hashPayload(java.util.Map<String, Object> payload) {
        try {
            byte[] canonical = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsBytes(new java.util.TreeMap<>(payload));
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "hash-failed";
        }
    }

    private Decision existingDecision(UUID txId, String consumerId) {
        return decisionRepo.findFirstByTxIdOrderByEvaluatedAtDesc(txId)
                .map(IngestService::toDomain)
                .orElseThrow(() -> new IllegalStateException(
                        "processed_events row exists but no decision found for tx=" + txId
                        + " consumer=" + consumerId));
    }

    private static TransactionEntity toTransactionEntity(Transaction tx) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("txId", tx.txId().toString());
        raw.put("accountId", tx.accountId());
        raw.put("amount", tx.amount().toPlainString());
        raw.put("currency", tx.currency());
        raw.put("mcc", tx.mcc());
        raw.put("channel", tx.channel().name());
        raw.put("country", tx.country());
        raw.put("ipCountry", tx.ipCountry());
        raw.put("deviceId", tx.deviceId());
        raw.put("merchantId", tx.merchantId());
        raw.put("accountAgeDays", tx.accountAgeDays());
        raw.put("timestamp", tx.timestamp().toString());
        return new TransactionEntity(
                tx.txId(), tx.accountId(), tx.amount(), tx.currency(),
                tx.mcc(), tx.channel(), tx.country(), tx.ipCountry(),
                tx.deviceId(), tx.merchantId(), tx.accountAgeDays(),
                tx.timestamp(), raw
        );
    }

    private static Map<String, Object> buildDecisionPayload(Decision d) {
        // LinkedHashMap → deterministic field order in the published JSON
        // (matches toTransactionEntity above; helps replay/diff/forensics).
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("decisionId", d.decisionId().toString());
        p.put("txId", d.txId().toString());
        p.put("accountId", d.accountId());
        p.put("status", d.status().name());
        p.put("score", d.score());
        p.put("ruleSetVersion", d.ruleSetVersion());
        p.put("matchedRules", d.matchedRules().stream()
                .map(r -> Map.of("ruleId", r.ruleId(), "priority", r.priority(), "reason", r.reason()))
                .toList());
        p.put("evaluatedAt", d.evaluatedAt().toString());
        return p;
    }

    private static Decision toDomain(DecisionEntity e) {
        return new Decision(
                e.getDecisionId(),
                e.getTxId(),
                e.getAccountId(),
                e.getStatus(),
                e.getScore().doubleValue(),
                e.getRuleSetVersion(),
                e.getMatchedRules().stream()
                        .map(dr -> new RuleResult(dr.getRuleId(), dr.getMatchedPriority(), dr.getReason()))
                        .toList(),
                e.getEvaluatedAt()
        );
    }
}
