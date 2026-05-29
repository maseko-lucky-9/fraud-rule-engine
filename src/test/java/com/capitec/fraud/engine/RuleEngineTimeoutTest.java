package com.capitec.fraud.engine;

import com.capitec.fraud.domain.Decision;
import com.capitec.fraud.domain.DecisionStatus;
import com.capitec.fraud.domain.Transaction;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-level proof of the timeout contract on
 * {@link BoundedRuleEngineEvaluator}.
 *
 * <p>The reviewer-visible invariant is: a pathological predicate cannot
 * silently APPROVE the transaction. Instead the wrapper throws a
 * {@link RuleEvaluationTimeoutException} which the
 * ProblemDetailExceptionHandler turns into a 503 + Retry-After=1.
 */
class RuleEngineTimeoutTest {

    private BoundedRuleEngineEvaluator evaluator;
    private RuleEngine engine;
    private MeterRegistry metrics;

    @BeforeEach
    void setup() {
        engine = mock(RuleEngine.class);
        metrics = new SimpleMeterRegistry();
    }

    @AfterEach
    void teardown() {
        if (evaluator != null) evaluator.shutdown();
    }

    @Test
    void fastEvalReturnsNormally() {
        Decision expected = new Decision(
                UUID.randomUUID(), UUID.randomUUID(), "ACC-1",
                DecisionStatus.APPROVE, 0.0, 1, List.of(), Instant.now());
        when(engine.evaluate(any())).thenReturn(expected);

        // Generous 500ms — under the prod 200ms floor but comfortably above
        // the synchronous return of the mock.
        evaluator = new BoundedRuleEngineEvaluator(engine, metrics, 500, 4);

        Decision actual = evaluator.evaluate(sampleTx());
        assertThat(actual).isSameAs(expected);
        assertThat(metrics.counter("rule_eval_timeout_total").count()).isZero();
    }

    @Test
    void slowEvalThrowsTimeoutAndTicksCounter() {
        when(engine.evaluate(any())).thenAnswer(inv -> {
            // Pretend the engine got stuck on a pathological predicate.
            Thread.sleep(2000);
            return new Decision(UUID.randomUUID(), UUID.randomUUID(), "ACC-1",
                    DecisionStatus.APPROVE, 0.0, 1, List.of(), Instant.now());
        });

        // 100ms cap so the test isn't slow.
        evaluator = new BoundedRuleEngineEvaluator(engine, metrics, 100, 4);

        assertThatThrownBy(() -> evaluator.evaluate(sampleTx()))
                .isInstanceOf(RuleEvaluationTimeoutException.class)
                .hasMessageContaining("exceeded 100ms");
        assertThat(metrics.counter("rule_eval_timeout_total").count()).isEqualTo(1.0);
    }

    @Test
    void engineRuntimeExceptionPropagates() {
        when(engine.evaluate(any())).thenThrow(new IllegalStateException("predicate boom"));

        evaluator = new BoundedRuleEngineEvaluator(engine, metrics, 500, 4);

        assertThatThrownBy(() -> evaluator.evaluate(sampleTx()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("predicate boom");
        assertThat(metrics.counter("rule_eval_timeout_total").count())
                .as("a thrown error is not a timeout")
                .isZero();
    }

    private static Transaction sampleTx() {
        return new Transaction(
                UUID.randomUUID(),
                "ACC-1",
                BigDecimal.valueOf(100),
                "ZAR", "5411",
                Transaction.Channel.WEB,
                "ZA", "ZA",
                "d-1", null,
                500,
                Instant.parse("2026-05-19T12:00:00Z"));
    }
}
