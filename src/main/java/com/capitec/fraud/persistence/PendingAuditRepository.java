package com.capitec.fraud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Read/write access to {@link PendingAuditEntity}. The retry poller uses
 * {@link #findDrainCandidates} to pull a bounded batch oldest-first,
 * leveraging the {@code ix_audit_pending_age} index. Skip-locked semantics
 * are unnecessary here because audit retries are processed by a single
 * scheduled poller per replica and the on-conflict-do-nothing landing pad
 * makes a duplicate drain harmless.
 */
public interface PendingAuditRepository extends JpaRepository<PendingAuditEntity, UUID> {

    @Query(value = """
            SELECT * FROM audit_pending
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PendingAuditEntity> findDrainCandidates(@Param("batchSize") int batchSize);

    /**
     * Drain promotion: copy the pending row into audit_log under
     * ON CONFLICT (resource_id, payload_hash) DO NOTHING. Returns the
     * row count so the poller can detect whether the live-path
     * already wrote the same (resource_id, payload_hash) pair —
     * a duplicate is harmless and the pending row is deleted regardless.
     */
    @Modifying
    @Query(value = """
            INSERT INTO audit_log (actor, action, resource_id, payload_hash, occurred_at)
            SELECT actor, action, resource_id, payload_hash, created_at
            FROM audit_pending
            WHERE id = :id
            ON CONFLICT (resource_id, payload_hash) DO NOTHING
            """, nativeQuery = true)
    int promoteToAuditLog(@Param("id") UUID id);

    long count();
}
