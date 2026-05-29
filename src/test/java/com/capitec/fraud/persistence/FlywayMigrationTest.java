package com.capitec.fraud.persistence;

import com.capitec.fraud.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms that the Day-7 schema (V1+V2+V3+V4) applies cleanly and that the
 * shapes the application code depends on actually exist after Flyway runs.
 *
 * <p>The assertions are deliberately structural — what tables, what columns,
 * what indexes — rather than behavioural. Behaviour is exercised by the
 * domain-specific tests downstream.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayMigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void allCoreTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                        "WHERE table_schema = 'public' ORDER BY table_name",
                String.class);
        assertThat(tables).contains(
                "transactions", "decisions", "decision_rules",
                "outbox", "outbox_dlt",
                "processed_events", "rule_versions",
                "audit_log", "audit_pending");
    }

    @Test
    void outboxDltCarriesLastErrorAndRoutedAt() {
        Set<String> cols = Set.copyOf(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                        "WHERE table_name = 'outbox_dlt'",
                String.class));
        assertThat(cols).contains(
                "id", "aggregate_id", "event_type", "payload",
                "created_at", "retry_count", "last_error", "routed_at");
    }

    @Test
    void auditLogHasUniquenessConstraintForRetryDedup() {
        // ON CONFLICT (resource_id, payload_hash) in AuditRetryPoller depends
        // on this UNIQUE constraint existing exactly as named.
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint " +
                        "WHERE conname = 'uq_audit_log_resource_hash'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void transactionsPartitionedParentExistsWithThreeChildren() {
        Integer parent = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_class " +
                        "WHERE relname = 'transactions_partitioned' AND relkind = 'p'",
                Integer.class);
        assertThat(parent).as("partitioned parent transactions_partitioned").isEqualTo(1);

        Integer children = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_inherits i " +
                        "JOIN pg_class c ON c.oid = i.inhparent " +
                        "WHERE c.relname = 'transactions_partitioned'",
                Integer.class);
        assertThat(children).as("monthly child partitions").isEqualTo(3);
    }

    @Test
    void auditPendingAgeIndexExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes " +
                        "WHERE schemaname = 'public' AND indexname = 'ix_audit_pending_age'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
