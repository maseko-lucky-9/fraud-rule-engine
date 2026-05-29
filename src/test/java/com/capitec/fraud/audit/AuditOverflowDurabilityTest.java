package com.capitec.fraud.audit;

import com.capitec.fraud.persistence.PendingAuditEntity;
import com.capitec.fraud.persistence.PendingAuditRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit-level proof of the durable-fallback contract on
 * {@link AuditEventListener}. When the live-path save against
 * {@link AuditRepository} fails (executor saturation, DB down, transient
 * SQL error), the listener writes the event to {@link PendingAuditRepository}
 * so {@link AuditRetryPoller} can promote it later. The audit_write_failed
 * counter ticks; the audit_write_pending_persisted counter ticks; the
 * event is NOT lost.
 *
 * <p>End-to-end semantics (live AuditExecutor saturation under burst, real
 * Testcontainers Postgres) sit in the CI integration run.
 */
class AuditOverflowDurabilityTest {

    @Test
    void liveSaveFailureFallsBackToPendingQueueAndCountsBoth() {
        AuditRepository liveRepo = mock(AuditRepository.class);
        PendingAuditRepository pendingRepo = mock(PendingAuditRepository.class);
        MeterRegistry metrics = new SimpleMeterRegistry();

        doThrow(new RuntimeException("simulated DB down"))
                .when(liveRepo).save(any(AuditEntity.class));

        AuditEventListener listener = new AuditEventListener(liveRepo, pendingRepo, metrics);
        AuditEvent event = new AuditEvent("rest-api", "DECISION_WRITE",
                "decision-id-7", "deadbeef-payload-hash");

        listener.on(event);

        // Live failed -> falls back to pending.
        ArgumentCaptor<PendingAuditEntity> captor =
                ArgumentCaptor.forClass(PendingAuditEntity.class);
        verify(pendingRepo).save(captor.capture());
        PendingAuditEntity persisted = captor.getValue();
        assertThat(persisted.getActor()).isEqualTo("rest-api");
        assertThat(persisted.getAction()).isEqualTo("DECISION_WRITE");
        assertThat(persisted.getResourceId()).isEqualTo("decision-id-7");
        assertThat(persisted.getPayloadHash()).isEqualTo("deadbeef-payload-hash");
        assertThat(persisted.getFailure()).contains("simulated DB down");

        assertThat(metrics.counter("audit_write_failed_total").count())
                .as("failed counter ticks on live-path failure").isEqualTo(1.0);
        assertThat(metrics.counter("audit_write_pending_persisted_total").count())
                .as("pending counter ticks on successful fallback").isEqualTo(1.0);
    }

    @Test
    void liveSaveSuccessDoesNotTouchPendingQueue() {
        AuditRepository liveRepo = mock(AuditRepository.class);
        PendingAuditRepository pendingRepo = mock(PendingAuditRepository.class);
        MeterRegistry metrics = new SimpleMeterRegistry();

        AuditEventListener listener = new AuditEventListener(liveRepo, pendingRepo, metrics);
        listener.on(new AuditEvent("rest-api", "DECISION_WRITE",
                "decision-id-7", "deadbeef-payload-hash"));

        verify(liveRepo).save(any(AuditEntity.class));
        org.mockito.Mockito.verifyNoInteractions(pendingRepo);
        assertThat(metrics.counter("audit_write_failed_total").count()).isZero();
        assertThat(metrics.counter("audit_write_pending_persisted_total").count()).isZero();
    }

    @Test
    void bothFailuresLogAndPropagate() {
        AuditRepository liveRepo = mock(AuditRepository.class);
        PendingAuditRepository pendingRepo = mock(PendingAuditRepository.class);
        MeterRegistry metrics = new SimpleMeterRegistry();

        doThrow(new RuntimeException("live down")).when(liveRepo).save(any(AuditEntity.class));
        doThrow(new RuntimeException("pending also down")).when(pendingRepo).save(any(PendingAuditEntity.class));

        AuditEventListener listener = new AuditEventListener(liveRepo, pendingRepo, metrics);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> listener.on(new AuditEvent(
                        "rest-api", "DECISION_WRITE", "decision-id-7", "h-7")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("pending also down");

        // The failed counter still ticks even though pending fallback also blew up.
        assertThat(metrics.counter("audit_write_failed_total").count()).isEqualTo(1.0);
        assertThat(metrics.counter("audit_write_pending_persisted_total").count())
                .as("pending persisted counter does NOT tick if pending also failed").isZero();
    }
}
