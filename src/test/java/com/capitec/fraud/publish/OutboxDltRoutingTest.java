package com.capitec.fraud.publish;

import com.capitec.fraud.persistence.OutboxDltEntity;
import com.capitec.fraud.persistence.OutboxEntity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level proof of the DLT-routing carry-over invariants:
 *
 *  - the dead-letter row's id matches the source outbox row's id (so
 *    operator triage can correlate by id without an extra lookup),
 *  - the payload is copied verbatim (so manual replay is just an insert
 *    back into outbox without re-deriving the JSON),
 *  - retry_count and created_at are preserved (so post-mortem can
 *    distinguish slow-failure paths from immediate-rejection paths),
 *  - last_error is captured (and truncated if absurdly long).
 *
 * The full end-to-end "row appears in outbox_dlt after N failed sends" is
 * exercised in an integration test under Testcontainers in the CI run.
 */
class OutboxDltRoutingTest {

    @Test
    void copiesAllOutboxFieldsAndCapturesLastError() {
        UUID id = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("decisionId", aggregateId.toString(), "status", "BLOCK");
        OutboxEntity source = new OutboxEntity(id, aggregateId, "tx.decisions.v1", payload);
        // Simulate 10 retries having occurred.
        for (int i = 0; i < 10; i++) source.incrementRetry();

        OutboxDltEntity dlt = new OutboxDltEntity(source, "kafka.TimeoutException: broker quiet");

        assertThat(dlt.getId()).isEqualTo(id);
        assertThat(dlt.getAggregateId()).isEqualTo(aggregateId);
        assertThat(dlt.getEventType()).isEqualTo("tx.decisions.v1");
        assertThat(dlt.getPayload()).isEqualTo(payload);
        assertThat(dlt.getCreatedAt()).isEqualTo(source.getCreatedAt());
        assertThat(dlt.getRetryCount()).isEqualTo(10);
        assertThat(dlt.getLastError()).isEqualTo("kafka.TimeoutException: broker quiet");
        assertThat(dlt.getRoutedAt()).isNotNull();
    }

    @Test
    void nullErrorBecomesUnknownPlaceholder() {
        OutboxEntity source = new OutboxEntity(UUID.randomUUID(), UUID.randomUUID(),
                "tx.decisions.v1", Map.of("k", "v"));
        OutboxDltEntity dlt = new OutboxDltEntity(source, null);
        assertThat(dlt.getLastError()).isEqualTo("unknown");
    }

    @Test
    void hugeErrorMessageIsTruncated() {
        OutboxEntity source = new OutboxEntity(UUID.randomUUID(), UUID.randomUUID(),
                "tx.decisions.v1", Map.of("k", "v"));
        String huge = "x".repeat(10_000);
        OutboxDltEntity dlt = new OutboxDltEntity(source, huge);
        assertThat(dlt.getLastError()).hasSize(4000);
    }
}
