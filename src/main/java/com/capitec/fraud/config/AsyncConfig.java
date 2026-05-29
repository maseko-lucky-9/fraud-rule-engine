package com.capitec.fraud.config;

import com.capitec.fraud.observability.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Dedicated pool for audit writes so they never block hot-path threads.
     *
     * <p>The {@link MdcTaskDecorator} copies the inbound request's
     * {@code correlationId} into the worker thread so audit-log lines are
     * still tagged with the originating request's MDC. Without it every
     * audit row would log as {@code [corrId=-]} and compliance tracing
     * across the @Async boundary would break.
     *
     * <p>Commit 5 of the full-overhaul code review changes the saturation
     * policy from the JDK default (AbortPolicy → RejectedExecutionException
     * → audit event silently dropped) to {@link CallerRunsPolicy}, paired
     * with a durable {@code audit_pending} retry queue so the audit trail
     * is bulletproof for banking compliance.
     */
    @Bean("auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(500);
        ex.setThreadNamePrefix("audit-");
        ex.setTaskDecorator(new MdcTaskDecorator());
        // CallerRunsPolicy: under sustained burst the publishing thread
        // executes the audit write inline rather than rejecting it. Combined
        // with the durable audit_pending fallback (AuditEventListener catch
        // block), the audit trail is no longer subject to silent drop on
        // executor saturation — the worst case is back-pressure on the
        // ingest path, which is acceptable for a banking audit invariant.
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.initialize();
        return ex;
    }
}
