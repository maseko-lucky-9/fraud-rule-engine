package com.capitec.fraud.ingest;

import com.capitec.fraud.TestcontainersConfiguration;
import com.capitec.fraud.domain.Decision;
import com.capitec.fraud.domain.Transaction;
import com.capitec.fraud.persistence.DecisionRepository;
import com.capitec.fraud.persistence.OutboxRepository;
import com.capitec.fraud.persistence.ProcessedEventRepository;
import com.capitec.fraud.persistence.TransactionRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan §7 Day-3 DoD:
 *  - 100 ingests → 100 transactions, 100 decisions, 100 outbox rows.
 *  - Replay (same 100 txIds again) → 0 duplicates: still 100 decisions, 100 processed_events.
 *  - OutboxPoller drains all outbox rows within a few ticks.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class IngestServiceIntegrationTest {

    @Autowired IngestService ingestService;
    @Autowired TransactionRepository transactionRepo;
    @Autowired DecisionRepository decisionRepo;
    @Autowired OutboxRepository outboxRepo;
    @Autowired ProcessedEventRepository processedRepo;
    @Autowired TransactionTemplate txTemplate;

    private List<Transaction> events;

    @BeforeEach
    void seedAndPurge() {
        txTemplate.executeWithoutResult(s -> {
            outboxRepo.deleteAllInBatch();
            processedRepo.deleteAllInBatch();
            decisionRepo.deleteAllInBatch();
            transactionRepo.deleteAllInBatch();
        });
        events = IntStream.range(0, 100)
                .mapToObj(i -> new Transaction(
                        UUID.randomUUID(),
                        "ACC-INT-" + i,
                        BigDecimal.valueOf(100 + i),
                        "ZAR", "5411",
                        Transaction.Channel.WEB,
                        "ZA", "ZA", "dev-" + i, null,
                        500, Instant.parse("2026-05-19T12:00:00Z")
                ))
                .toList();
    }

    @Test
    @DisplayName("100 ingests → 100 decisions, 100 outbox rows, all transactional")
    void ingest_persists_full_set() {
        events.forEach(e -> ingestService.ingest(e, IngestService.CONSUMER_KAFKA));

        assertThat(transactionRepo.count()).isEqualTo(100);
        assertThat(decisionRepo.count()).isEqualTo(100);
        assertThat(outboxRepo.count()).isEqualTo(100);
        assertThat(processedRepo.count()).isEqualTo(100);
    }

    @Test
    @DisplayName("Replay (same txIds twice) → 0 duplicates; decisions stable at 100")
    void replay_is_idempotent() {
        events.forEach(e -> ingestService.ingest(e, IngestService.CONSUMER_KAFKA));
        // Replay — every call returns the existing decision via the dedup path.
        events.forEach(e -> ingestService.ingest(e, IngestService.CONSUMER_KAFKA));

        assertThat(decisionRepo.count()).as("decisions stable after replay").isEqualTo(100);
        assertThat(processedRepo.count()).as("no new processed_events rows").isEqualTo(100);
        assertThat(outboxRepo.count()).as("no new outbox rows on replay").isEqualTo(100);

        // Cross-consumer: same txId from a different consumer is a legitimate
        // distinct event — record sanity-check.
        ingestService.ingest(events.get(0), IngestService.CONSUMER_REST);
        assertThat(processedRepo.count()).isEqualTo(101);
        assertThat(decisionRepo.count()).isEqualTo(101);
    }

    @Test
    @DisplayName("OutboxPoller drains all pending rows within a few seconds")
    void outbox_drains() {
        events.forEach(e -> ingestService.ingest(e, IngestService.CONSUMER_KAFKA));
        // Note: the poller may already have drained some rows by the time we
        // assert — the only meaningful invariant is "eventually zero pending".

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() ->
                        assertThat(outboxRepo.countByProcessedAtIsNull()).as("all outbox rows processed").isZero()
                );

        assertThat(outboxRepo.count()).as("rows are retained, just marked processed").isEqualTo(100);
    }

    @Test
    @DisplayName("Returned decisions all carry ruleSetVersion=1 (audit invariant)")
    void decisions_carry_rule_set_version() {
        List<Decision> results = events.stream()
                .map(e -> ingestService.ingest(e, IngestService.CONSUMER_KAFKA))
                .collect(Collectors.toList());
        assertThat(results).allSatisfy(d -> assertThat(d.ruleSetVersion()).isEqualTo(1));
    }

}
