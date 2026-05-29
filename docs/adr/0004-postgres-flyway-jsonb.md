# ADR-0004: PostgreSQL 16 + Flyway, with JSONB for Replay Payloads

**Status:** Accepted · **Date:** 2026-05-19

## Context

We need a strong-consistency store for decisions, audit trail, and the transactional outbox. We also want raw event payloads stored for replay (rule backtesting) without forcing a separate raw store.

## Decision

- **PostgreSQL 16-alpine** in compose.
- **Flyway** for schema migrations (versioned, reviewable, ordered).
- `transactions.payload` is `jsonb` — stores the full raw event for replay.
- Partition `transactions` and `audit_log` by month; >90d partitions detached + dumped to archive.
- `decisions` retained indefinitely (hot for queries, small footprint).
- `outbox` rows purged 7d after `processed_at`.

## Rationale

- Banking favours ACID-compliant relational stores. JSONB gives schema flexibility without giving up transactional integrity.
- Flyway over Liquibase: simpler, raw SQL, easier for reviewers to read.
- Partitioning is the cheapest path to bounded table growth without an additional tier (no S3, no separate analytics DB).

## Consequences

- Cannot do full-text search on payloads efficiently (acceptable; not a use case here).
- Partition management requires a scheduled job (documented in DR runbook).
- All migrations are versioned + audited via the `rule_versions` table when they affect rule semantics.
