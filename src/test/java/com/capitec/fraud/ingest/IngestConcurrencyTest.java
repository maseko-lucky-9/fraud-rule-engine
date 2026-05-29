package com.capitec.fraud.ingest;

import com.capitec.fraud.TestcontainersConfiguration;
import com.capitec.fraud.domain.Decision;
import com.capitec.fraud.domain.Transaction;
import com.capitec.fraud.persistence.DecisionRepository;
import com.capitec.fraud.persistence.OutboxRepository;
import com.capitec.fraud.persistence.ProcessedEventRepository;
import com.capitec.fraud.persistence.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the post-overhaul ingest correctness guarantee: under N concurrent
 * callers submitting the SAME {@code (txId, consumerId)}, exactly one
 * decision row, one transaction row, one outbox row, and one
 * processed_events row are written, and every returned {@link Decision}
 * references the same decisionId.
 *
 * <p>Pre-overhaul {@code IngestService} used a dual-method pattern (outer
 * non-tx + inner @Transactional + DataIntegrityViolationException recovery)
 * to dodge PostgreSQL's "doomed transaction" state. The collapsed single-tx
 * version uses {@code INSERT … ON CONFLICT DO NOTHING} + EntityManager
 * flush/clear + read-back for the loser path; this test is the regression
 * proof that the cleaner pattern preserves the original race-freedom.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class IngestConcurrencyTest {

    private static final int CONCURRENCY = 50;

    @Autowired IngestService ingestService;
    @Autowired TransactionRepository transactionRepo;
    @Autowired DecisionRepository decisionRepo;
    @Autowired OutboxRepository outboxRepo;
    @Autowired ProcessedEventRepository processedRepo;
    @Autowired TransactionTemplate txTemplate;

    @BeforeEach
    void purge() {
        txTemplate.executeWithoutResult(s -> {
            outboxRepo.deleteAllInBatch();
            processedRepo.deleteAllInBatch();
            decisionRepo.deleteAllInBatch();
            transactionRepo.deleteAllInBatch();
        });
    }

    @Test
    @DisplayName("50 concurrent identical (txId, consumerId) -> 1 row in each of the 4 tables")
    void concurrent_duplicate_claims_produce_single_row_set() throws Exception {
        Transaction tx = new Transaction(
                UUID.randomUUID(),
                "ACC-CONC-1",
                BigDecimal.valueOf(750),
                "ZAR", "5411",
                Transaction.Channel.WEB,
                "ZA", "ZA",
                "device-conc-1", null,
                500,
                Instant.parse("2026-05-19T12:00:00Z")
        );
        String consumer = IngestService.CONSUMER_KAFKA;

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<CompletableFuture<Decision>> futures = IntStream.range(0, CONCURRENCY)
                    .mapToObj(i -> CompletableFuture.supplyAsync(
                            () -> ingestService.ingest(tx, consumer), pool))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);

            // All 50 callers should receive a Decision pointing at the same
            // decisionId — whoever wrote it.
            UUID winnerDecisionId = futures.get(0).join().decisionId();
            assertThat(futures.stream().map(CompletableFuture::join)
                    .map(Decision::decisionId))
                    .as("every concurrent caller observes the same decisionId")
                    .containsOnly(winnerDecisionId);

            // The persistence side must reflect a single set of rows.
            assertThat(decisionRepo.count()).as("exactly 1 decision row").isEqualTo(1);
            assertThat(transactionRepo.count()).as("exactly 1 transaction row").isEqualTo(1);
            assertThat(outboxRepo.count()).as("exactly 1 outbox row").isEqualTo(1);
            assertThat(processedRepo.count()).as("exactly 1 processed_events row").isEqualTo(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
