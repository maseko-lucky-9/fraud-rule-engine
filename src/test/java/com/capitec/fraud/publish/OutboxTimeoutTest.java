package com.capitec.fraud.publish;

import com.capitec.fraud.persistence.OutboxDltRepository;
import com.capitec.fraud.persistence.OutboxEntity;
import com.capitec.fraud.persistence.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level proof of the wait-timeout + per-row settlement contract:
 *
 *  - A row whose send future never completes within
 *    {@code publishTimeoutMs} has its retry_count bumped (not its
 *    processed_at) so the next tick re-tries it.
 *  - A row that has already crossed {@code maxRetries} is routed to
 *    {@link OutboxDltRepository} INSTEAD OF being left pending — the
 *    OutboxRepository receives a delete call.
 *  - The metrics counter {@code outbox_dlt_total} ticks on each routed row.
 *
 * The full integration version (real Kafka, real Postgres) lives in the
 * Testcontainers-backed CI run; this test sits in the unit layer so a
 * regression surfaces in seconds rather than minutes.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class OutboxTimeoutTest {

    @Test
    void slowSendBumpsRetryAndRowStaysPending() throws Exception {
        OutboxRepository outboxRepo = mock(OutboxRepository.class);
        OutboxDltRepository dltRepo = mock(OutboxDltRepository.class);
        KafkaTemplate kafka = mock(KafkaTemplate.class);
        MeterRegistry metrics = new SimpleMeterRegistry();

        OutboxEntity row = new OutboxEntity(UUID.randomUUID(), UUID.randomUUID(),
                "tx.decisions.v1", Map.of("k", "v"));

        when(outboxRepo.findPendingForUpdate(anyInt())).thenReturn(List.of(row));
        // Never-completing future simulates a hung broker.
        CompletableFuture<SendResult<String, Object>> never = new CompletableFuture<>();
        when(kafka.send(anyString(), anyString(), any())).thenReturn(never);

        // publishTimeoutMs = 100 (very short) so the test is fast; max-retries 10.
        OutboxPoller poller = new OutboxPoller(outboxRepo, dltRepo, kafka, metrics, 100, 100L, 10);
        poller.drain();

        assertThat(row.getRetryCount()).as("retry bumped on timeout").isEqualTo(1);
        assertThat(row.getProcessedAt()).as("row stays pending").isNull();
        verify(dltRepo, never()).save(any());
        verify(outboxRepo, never()).delete(any());
    }

    @Test
    void rowAtMaxRetriesIsRoutedToDltAndDeletedFromLiveTable() {
        OutboxRepository outboxRepo = mock(OutboxRepository.class);
        OutboxDltRepository dltRepo = mock(OutboxDltRepository.class);
        KafkaTemplate kafka = mock(KafkaTemplate.class);
        MeterRegistry metrics = new SimpleMeterRegistry();

        OutboxEntity row = new OutboxEntity(UUID.randomUUID(), UUID.randomUUID(),
                "tx.decisions.v1", Map.of("k", "v"));
        // Simulate 9 prior failures so this attempt's increment crosses 10.
        for (int i = 0; i < 9; i++) row.incrementRetry();

        when(outboxRepo.findPendingForUpdate(anyInt())).thenReturn(List.of(row));
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker.AuthenticationException: bad creds"));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(failed);

        OutboxPoller poller = new OutboxPoller(outboxRepo, dltRepo, kafka, metrics, 100, 100L, 10);
        poller.drain();

        verify(dltRepo, times(1)).save(any());
        verify(outboxRepo, times(1)).delete(row);
        assertThat(metrics.counter("outbox_dlt_total").count())
                .as("DLT counter ticks on routing")
                .isEqualTo(1.0);
    }

    @Test
    void successfulSendIsMarkedProcessedAndStaysInLiveTable() {
        OutboxRepository outboxRepo = mock(OutboxRepository.class);
        OutboxDltRepository dltRepo = mock(OutboxDltRepository.class);
        KafkaTemplate kafka = mock(KafkaTemplate.class);
        MeterRegistry metrics = new SimpleMeterRegistry();

        OutboxEntity row = new OutboxEntity(UUID.randomUUID(), UUID.randomUUID(),
                "tx.decisions.v1", Map.of("k", "v"));
        when(outboxRepo.findPendingForUpdate(anyInt())).thenReturn(List.of(row));
        SendResult<String, Object> result = mock(SendResult.class);
        CompletableFuture<SendResult<String, Object>> ok = CompletableFuture.completedFuture(result);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(ok);

        OutboxPoller poller = new OutboxPoller(outboxRepo, dltRepo, kafka, metrics, 100, 5000L, 10);
        poller.drain();

        assertThat(row.getProcessedAt()).as("processed_at stamped on success").isNotNull();
        assertThat(row.getRetryCount()).as("retry NOT bumped on success").isZero();
        verify(dltRepo, never()).save(any());
        verify(outboxRepo, never()).delete(any());
    }

    @org.junit.jupiter.api.Test
    void emptyBatchIsANoop() {
        OutboxRepository outboxRepo = mock(OutboxRepository.class);
        OutboxDltRepository dltRepo = mock(OutboxDltRepository.class);
        KafkaTemplate kafka = mock(KafkaTemplate.class);
        MeterRegistry metrics = new SimpleMeterRegistry();

        when(outboxRepo.findPendingForUpdate(anyInt())).thenReturn(List.of());

        OutboxPoller poller = new OutboxPoller(outboxRepo, dltRepo, kafka, metrics, 100, 100L, 10);
        poller.drain();

        verify(kafka, never()).send(anyString(), anyString(), any());
        verify(dltRepo, never()).save(any());
    }

    /** Silences AnyInt complaint */
    private static int anyInt() { return org.mockito.ArgumentMatchers.anyInt(); }
}
