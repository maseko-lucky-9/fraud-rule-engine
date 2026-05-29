package com.capitec.fraud.observability;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Copies the calling thread's {@link MDC} context map onto the worker
 * thread for the duration of a single task. Without this, every {@code @Async}
 * boundary erases {@code correlationId} (set by
 * {@link CorrelationIdFilter}), so audit log lines and any other downstream
 * structured logs lose the request-correlation breadcrumb that ties them to
 * the inbound HTTP call.
 *
 * <p>The decorator is installed on {@link com.capitec.fraud.config.AsyncConfig
 * AsyncConfig#auditExecutor}; reuse via {@code ex.setTaskDecorator(new
 * MdcTaskDecorator())} on any future executor that needs to preserve logging
 * context.
 */
public final class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Snapshot the submitting thread's context.
        Map<String, String> contextSnapshot = MDC.getCopyOfContextMap();
        return () -> {
            // Preserve whatever the worker thread already had (typically none
            // for a fresh executor task) so a defensive restore on completion
            // doesn't accidentally clobber unrelated context the worker may
            // have set up before running this task.
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (contextSnapshot == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(contextSnapshot);
                }
                runnable.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
