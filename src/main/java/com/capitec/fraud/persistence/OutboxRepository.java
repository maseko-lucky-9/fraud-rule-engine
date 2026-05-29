package com.capitec.fraud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {

    /**
     * Drain candidates: oldest first, capped, only unprocessed.
     *
     * <p>The Postgres {@code FOR UPDATE SKIP LOCKED} clause lets multiple
     * API replicas poll the outbox concurrently — each claims a disjoint
     * subset of rows, eliminating the duplicate-publish bug that a plain
     * SELECT would have under horizontal scaling. The row lock is held
     * for the lifetime of the surrounding transaction (the poller's
     * {@code drain} method commits or rolls back the locks).
     *
     * <p>Native query because JPA's {@code @Lock(PESSIMISTIC_WRITE)} does
     * not emit {@code SKIP LOCKED} — without that clause, replicas serialise
     * on the same row instead of picking different ones.
     */
    @Query(value = """
            SELECT * FROM outbox
            WHERE processed_at IS NULL
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEntity> findPendingForUpdate(@Param("batchSize") int batchSize);

    long countByProcessedAtIsNull();
}
