package com.capitec.fraud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Read/write access to {@link OutboxDltEntity}. Writes happen exclusively
 * inside the OutboxPoller's transactional drain so the move from outbox to
 * dead-letter is atomic. Reads serve operator triage tooling.
 */
public interface OutboxDltRepository extends JpaRepository<OutboxDltEntity, UUID> {

    long countByEventType(String eventType);
}
