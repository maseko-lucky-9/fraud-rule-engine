-- V3: Operational tables added by the full-overhaul code review.
--
-- 1. outbox_dlt — terminal-state landing pad for outbox rows that have
--    exhausted their retry budget. Without this rows with permanently
--    unpublishable payloads would loop forever, bloating the outbox table
--    and drowning the audit / metrics signal.
--
-- 2. audit_pending — durable retry queue for audit writes that failed
--    inside the async listener (executor saturation or transient DB error).
--    Backed by AuditRetryPoller; drained back into audit_log under
--    ON CONFLICT DO NOTHING so a successful retry never duplicates the live
--    path's write.
--
-- 3. UNIQUE (resource_id, payload_hash) on audit_log so the retry-side
--    INSERT … ON CONFLICT DO NOTHING is well-defined.

CREATE TABLE outbox_dlt (
    id            uuid        PRIMARY KEY,
    aggregate_id  uuid        NOT NULL,
    event_type    text        NOT NULL,
    payload       jsonb       NOT NULL,
    -- Carry-over from outbox so forensics can correlate timing.
    created_at    timestamptz NOT NULL,
    retry_count   integer     NOT NULL,
    last_error    text        NOT NULL,
    -- When the poller moved the row out of the live outbox.
    routed_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_outbox_dlt_aggregate ON outbox_dlt (aggregate_id);
CREATE INDEX ix_outbox_dlt_routed    ON outbox_dlt (routed_at DESC);


CREATE TABLE audit_pending (
    id            uuid        PRIMARY KEY,
    actor         text        NOT NULL,
    action        text        NOT NULL,
    resource_id   text        NOT NULL,
    payload_hash  text        NOT NULL,
    failure       text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    retry_count   integer     NOT NULL DEFAULT 0
);

-- Partial index drives the poller scan and ages out as rows are drained.
CREATE INDEX ix_audit_pending_age ON audit_pending (created_at ASC);


-- Dedup target for AuditRetryPoller's ON CONFLICT DO NOTHING.
-- The (resource_id, payload_hash) pair is stable across the live + retry
-- paths because the hash is computed from the canonical decision JSON,
-- which is deterministic per decision.
ALTER TABLE audit_log
    ADD CONSTRAINT uq_audit_log_resource_hash UNIQUE (resource_id, payload_hash);
