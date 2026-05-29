package com.capitec.fraud.engine;

import com.capitec.fraud.domain.Action;
import com.capitec.fraud.domain.Condition;
import com.capitec.fraud.domain.Decision;
import com.capitec.fraud.domain.DecisionStatus;
import com.capitec.fraud.domain.Rule;
import com.capitec.fraud.domain.RuleSet;
import com.capitec.fraud.domain.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class HotReloadRaceTest {

    private static final Instant FIXED = Instant.parse("2026-05-19T12:00:00Z");

    /**
     * Plan §13 risk: "Rule hot-reload race condition → wrong decision under
     * load". Mitigation is an {@code AtomicReference<RuleSet>} swap. This
     * test runs 50 concurrent evaluations while another thread reloads the
     * rule set 50 times. Asserts no exception leaked AND every decision
     * carries either the old or the new version — never null, never mixed.
     */
    @Test
    void atomic_swap_under_concurrent_evaluation() throws InterruptedException {
        var registry = new PredicateRegistry(List.of(
                new com.capitec.fraud.engine.predicates.AmountAbovePredicate()
        ));
        var engine = new RuleEngine(registry, new InMemoryStateStore(),
                Clock.fixed(FIXED, ZoneOffset.UTC), java.util.Optional.empty());

        RuleSet v1 = ruleSet(1);
        RuleSet v2 = ruleSet(2);
        engine.install(v1);

        int evaluators = 50;
        var pool = Executors.newFixedThreadPool(evaluators + 1);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(evaluators);
        var versions = new ConcurrentLinkedQueue<Integer>();
        var failures = new ConcurrentLinkedQueue<Throwable>();

        for (int i = 0; i < evaluators; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 100; n++) {
                        Decision d = engine.evaluate(makeTx());
                        versions.add(d.ruleSetVersion());
                    }
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        var reloaderDone = new CountDownLatch(1);
        pool.submit(() -> {
            try {
                start.await();
                for (int n = 0; n < 50; n++) {
                    engine.install(n % 2 == 0 ? v2 : v1);
                    Thread.sleep(1);
                }
            } catch (InterruptedException ignored) {
                // Expected only if the test times out; not a failure signal.
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                failures.add(t);
            } finally {
                reloaderDone.countDown();
            }
        });

        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS)
                && reloaderDone.await(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).as("evaluators completed within timeout").isTrue();
        assertThat(failures).as("no exceptions during concurrent reload").isEmpty();
        assertThat(versions).as("every decision tagged with v1 or v2 — never null/zero")
                .isNotEmpty()
                .allSatisfy(v -> assertThat(v).isIn(1, 2));
    }

    private static RuleSet ruleSet(int version) {
        return new RuleSet("test", version, List.of(new Rule(
                "AMOUNT_REVIEW", 100, Rule.Severity.MEDIUM, false,
                new Condition.Atomic("amountAbove", Map.of("value", 100, "currency", "ZAR")),
                new Action(DecisionStatus.REVIEW, 0.5, "amount above threshold")
        )));
    }

    private static Transaction makeTx() {
        return new Transaction(UUID.randomUUID(), "ACC-RACE",
                BigDecimal.valueOf(500), "ZAR", "5411",
                Transaction.Channel.WEB, "ZA", "ZA", "dev", null, 100, FIXED);
    }
}
