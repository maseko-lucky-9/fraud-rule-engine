-- Truncate the engine tables a simulation run writes to.
--
-- Used by `sim reset --truncate` between runs so latency percentiles and
-- decision counts aren't skewed by accumulated rows from prior runs.
--
-- IMPORTANT: this script preserves the schema (created by Flyway) and any
-- data outside the named tables. It is destructive to those tables only.
--
-- Safe to run on an idle engine; if the engine is actively writing while
-- this runs, transactions in-flight will fail with serialisation errors —
-- callers should stop the engine first.

BEGIN;

-- Table names verified against the live Flyway schema (\dt on fraud_engine).
-- The previous list named `idempotency_cache` and `audit_events`, neither of
-- which exists, so the whole TRUNCATE aborted with UndefinedTable. The real
-- tables a run writes to are below (decision_rules is a child of decisions and
-- is cleared via CASCADE, but listed explicitly for clarity).
TRUNCATE TABLE
    decisions,
    decision_rules,
    transactions,
    audit_log,
    audit_pending,
    outbox,
    outbox_dlt,
    processed_events
RESTART IDENTITY CASCADE;

COMMIT;
