package com.capitec.fraud.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level proof that {@link MdcTaskDecorator} actually copies the
 * submitting thread's MDC into the worker thread. The integration version
 * (correlationId end-to-end from inbound request → audit log line) lives in
 * the IngestServiceIntegrationTest's log capture once Testcontainers is
 * available; here we want a fast, deterministic, infrastructure-free pin
 * so a regression in {@code AsyncConfig#auditExecutor} surfaces in the
 * unit test loop.
 */
class MdcPropagationTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();
    private ExecutorService pool;

    @AfterEach
    void teardown() throws InterruptedException {
        if (pool != null) {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
        MDC.clear();
    }

    @Test
    void contextSnapshotedAtSubmitAndRestoredOnWorker() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        MDC.put("correlationId", "test-corr-123");
        MDC.put("subject", "alice");
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();

        Runnable decorated = decorator.decorate(() -> seen.set(MDC.getCopyOfContextMap()));
        CompletableFuture.runAsync(decorated, pool).get(2, TimeUnit.SECONDS);

        assertThat(seen.get())
                .as("worker thread sees the submitter's MDC snapshot")
                .containsEntry("correlationId", "test-corr-123")
                .containsEntry("subject", "alice");
    }

    @Test
    void emptySubmitterContextClearsWorker() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        MDC.clear();
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();

        Runnable decorated = decorator.decorate(() -> seen.set(MDC.getCopyOfContextMap()));
        CompletableFuture.runAsync(decorated, pool).get(2, TimeUnit.SECONDS);

        assertThat(seen.get())
                .as("empty MDC snapshot maps to cleared worker MDC")
                .isNullOrEmpty();
    }

    @Test
    void workerMdcRestoredAfterTask() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        // Seed the worker's MDC with a pre-existing value (would normally be
        // set by another decorated submission on the same reused worker).
        Executor inline = Runnable::run;
        pool.submit(() -> {
            MDC.put("worker-only", "should-survive");
        }).get(2, TimeUnit.SECONDS);

        MDC.put("correlationId", "outer-456");
        Runnable decorated = decorator.decorate(() -> {
            assertThat(MDC.get("correlationId")).isEqualTo("outer-456");
        });
        pool.submit(decorated).get(2, TimeUnit.SECONDS);

        // After the decorated task, the worker's pre-existing MDC should be
        // restored — verified by submitting an undecorated probe.
        AtomicReference<String> probe = new AtomicReference<>();
        pool.submit(() -> probe.set(MDC.get("worker-only"))).get(2, TimeUnit.SECONDS);
        assertThat(probe.get())
                .as("worker's pre-existing MDC restored after decorated task")
                .isEqualTo("should-survive");
    }
}
