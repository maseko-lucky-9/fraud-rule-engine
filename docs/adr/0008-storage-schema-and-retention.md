# ADR-0008: Storage Schema, Retention, and Audit Trail

**Status:** Accepted · **Date:** 2026-05-19

## Context

The Day-3 persistence layer crystallises five tables. Each has a distinct lifetime and audit-criticality profile; a one-size-fits-all retention policy would either bloat the hot path or quietly delete data legal expects to keep. ADR-0004 picked Postgres + Flyway + JSONB; this ADR pins the schema shape, indexing strategy, and retention contract that Day-3 ships.

## Decision

### Tables (all owned by `com.capitec.fraud.persistence`)

| Table | Purpose | Key | Retention |
|---|---|---|---|
| `transactions` | Raw event corpus — supports replay / rule backtesting. `payload` is JSONB. | `tx_id` (UUID) | 90d hot; monthly partitioning + offline archive (Day-3 schema; partition jobs Day-7). |
| `decisions` | Deterministic verdict + score. One row per evaluation. | `decision_id` (UUID) | Indefinite — small footprint, audit-critical. |
| `decision_rules` | Bridge table — which rules matched, in priority order, with reason text. | `(decision_id, rule_id)` composite | Cascade-deleted with parent decision. |
| `outbox` | Transactional outbox for `tx.decisions.v1`. | `id` (UUID) | Rows purged 7d after `processed_at`. |
| `processed_events` | Consumer-side dedup. | `(event_id, consumer_id)` composite | Retained 90d for replay-safety; then partition-aged. |
| `rule_versions` | Audit of every rule-set reload. | `version` (int) | Indefinite. |
| `audit_log` | Every admin action + every decision-write event. | `id` (bigserial) | Indefinite. Partition by month; offline archive after 24 months. |

### Indexes

- `ix_tx_account_received (account_id, received_at DESC)` — supports "recent activity for account X" queries.
- `ix_tx_received_at (received_at)` — partition-pruning + retention scans.
- `ix_dec_status_eval (status, evaluated_at DESC)` — supports operator review queues (`BLOCK`, `REVIEW`).
- `ix_dec_account (account_id, evaluated_at DESC)` — account-level decision history.
- `ix_dec_tx (tx_id)` — replay lookups + dedup recovery.
- `ix_dr_rule_id (rule_id)` — "how often did rule X fire" reporting.
- `ix_outbox_pending (created_at) WHERE processed_at IS NULL` — **partial index**, so the poller's hot scan stays small even after millions of decisions.
- `ix_audit_actor_time` + `ix_audit_action` — investigation queries.

### JSONB usage

Two places: `transactions.payload` (full inbound event for replay) and `outbox.payload` (full outbound decision for publish). Hibernate handles serialization via `@JdbcTypeCode(SqlTypes.JSON)` mapped to `Map<String, Object>`.

### ISO-code columns

`currency char(3)` and `country char(2)` / `ip_country char(2)` are fixed-length. To prevent Hibernate's schema validator from rejecting these as `VARCHAR`, the entity columns use `@JdbcTypeCode(SqlTypes.CHAR)`. (Documented because it was a Day-3 startup-failure footgun.)

### Decimal precision

`decisions.score numeric(4,3)` — score in `[0.000, 1.000]`. `IngestService` calls `setScale(3, HALF_UP)` before saving so float-to-decimal conversions don't silently round at a different precision than the DB stores. `transactions.amount numeric(19,4)` — banking-standard 19/4.

## Why versioned in `decisions.rule_set_version`

Every decision is reproducible: given the same input transaction and the same `rule_set_version`, the engine produces the same verdict. If a regulator asks "why was customer X's tx blocked on 14 March", we look up the decision, fetch the rule-set definition at that version, and replay. This is the audit invariant that justifies the entire `rule_versions` table.

## Retention rationale

- **Hot tables** (`decisions`, `audit_log`) are small enough that indefinite retention is cheap and pays for itself on the first audit query.
- **Bulk tables** (`transactions`) hold raw payloads — 90d hot + partition + archive limits both the index size and the working-set memory.
- **Operational tables** (`outbox`) keep processed rows for 7d to support post-incident "did this event publish?" forensics, then prune.
- **Dedup tables** (`processed_events`) live for 90d — long enough that any reasonable Kafka replay window will still hit the dedup, short enough to bound the row count.

## Consequences

**Positive.**
- Every regulatory question has a row to point at.
- Replay is a documented capability, not a wish.
- Indexes match the hot queries — no full-table scans on the decision review queue.

**Negative.**
- Partition management is now an operational responsibility (Day-7 ships the cron + script).
- Schema changes downstream must respect the `char(N)` JDBC-type-code quirk; documented in this ADR + a comment on the entity.

## Forward path

- Day 4 adds `audit_log` writes via `@EventListener` on every decision write + admin action.
- Day 5 adds JMeter / k6 load profile that exercises the indexed paths.
- Day 6 publishes a `disaster-recovery.md` runbook that ties this schema to RPO / RTO targets.
- Day 7 adds the partition-rotation script (`scripts/rotate-partitions.sh`) referenced in retention rows above.
