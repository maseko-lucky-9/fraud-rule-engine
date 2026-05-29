package com.capitec.fraud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProcessedEventRepository
        extends JpaRepository<ProcessedEventEntity, ProcessedEventEntity.ProcessedEventId> {

    /**
     * Race-free idempotency claim. Replaces the previous
     * {@code saveAndFlush + DataIntegrityViolationException} dance, which
     * required a non-transactional outer + transactional inner method to
     * survive PostgreSQL's "doomed transaction" state on PK collision.
     *
     * <p>Returns the number of rows actually inserted: 1 if this caller
     * won the race, 0 if another caller already claimed the {@code
     * (txId, consumerId)} key. With 0, callers must {@code flush + clear}
     * the EntityManager and re-read the existing decision under
     * {@code READ_COMMITTED} so the winner's just-committed row is visible.
     *
     * <p>{@code @Modifying(flushAutomatically = true, clearAutomatically =
     * false)} forces the SQL to run before any subsequent SELECT in the
     * same transaction (so the row count reflects the truth Postgres saw)
     * but does NOT clear the persistence context — the caller decides
     * whether a clear is appropriate after re-reading.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO processed_events (event_id, consumer_id, processed_at)
            VALUES (:txId, :consumerId, now())
            ON CONFLICT (event_id, consumer_id) DO NOTHING
            """, nativeQuery = true)
    int tryClaim(@Param("txId") UUID txId, @Param("consumerId") String consumerId);
}
