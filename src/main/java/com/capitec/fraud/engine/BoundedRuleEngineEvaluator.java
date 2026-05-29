package com.capitec.fraud.engine;

import com.capitec.fraud.domain.Decision;
import com.capitec.fraud.domain.Transaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Latency-bounded wrapper around {@link RuleEngine#evaluate(Transaction)}.
 *
 * <p>The deterministic rule engine is fast in the steady state, but a
 * pathological predicate (a regex doing catastrophic backtracking, a
 * stateful predicate stuck on a slow Redis Lua script, a recursive rule
 * condition deeper than expected) can pin a request thread. Without a
 * bound, that pin would cascade — Tomcat workers blocked, Hikari pool
 * starved, readiness probe failing. This evaluator caps the wait at
 * {@code app.engine.eval-timeout-ms} (default 200ms in prod, overridden
 * to 5000ms in {@code src/test/resources/application.yml} so cold-JVM
 * test runs don't flake).
 *
 * <p>On timeout the wait future is cancelled and a
 * {@link RuleEvaluationTimeoutException} is propagated as a 503-class
 * error rather than a silent APPROVE — under-blocking is the right
 * default for rate-limit fail-open, but for the engine itself a missed
 * evaluation is a correctness gap that should be visible.
 *
 * <p>The bounded executor is a fixed pool sized to match the Tomcat
 * worker pool — one evaluator slot per concurrent request — so the
 * indirection adds an O(microsecond) thread-hop without queue build-up.
 */
@Component
public class BoundedRuleEngineEvaluator {

    private static final Logger log = LoggerFactory.getLogger(BoundedRuleEngineEvaluator.class);

    private final RuleEngine engine;
    private final long timeoutMs;
    private final ExecutorService pool;
    private final Counter timeoutCounter;

    public BoundedRuleEngineEvaluator(RuleEngine engine,
                                      MeterRegistry meters,
                                      @Value("${app.engine.eval-timeout-ms:200}") long timeoutMs,
                                      @Value("${app.engine.eval-pool-size:50}") int poolSize) {
        this.engine = engine;
        this.timeoutMs = timeoutMs;
        this.pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "rule-eval");
            t.setDaemon(true);
            return t;
        });
        this.timeoutCounter = Counter.builder("rule_eval_timeout_total")
                .description("RuleEngine.evaluate calls that exceeded app.engine.eval-timeout-ms")
                .register(meters);
    }

    public Decision evaluate(Transaction tx) {
        CompletableFuture<Decision> future = CompletableFuture.supplyAsync(
                () -> engine.evaluate(tx), pool);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            timeoutCounter.increment();
            log.error("rule-eval timeout tx={} after {}ms — propagating as 503; check predicate health",
                    tx.txId(), timeoutMs);
            throw new RuleEvaluationTimeoutException(
                    "RuleEngine.evaluate exceeded " + timeoutMs + "ms for tx=" + tx.txId(), te);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuleEvaluationTimeoutException("rule-eval interrupted for tx=" + tx.txId(), ie);
        } catch (ExecutionException ee) {
            // Re-throw the original cause so existing error handling (e.g. the
            // ProblemDetailExceptionHandler) sees the same exception type it
            // would have seen without this wrapper.
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
