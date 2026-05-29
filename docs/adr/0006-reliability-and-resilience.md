# ADR-0006: Reliability and Resilience

**Status:** Accepted · **Date:** 2026-05-19

## Context

Day-3 wires the async pipeline. A banking system cannot drop a transaction event silently, double-bill an account, or accept a decision that wasn't actually persisted. The reliability surface to cover:

1. **Atomicity** — decision + audit trail + outgoing event row write or none at all.
2. **Idempotency** — replayed events must not produce duplicate decisions.
3. **At-least-once delivery** — every committed decision must reach `tx.decisions.v1` even if the broker is briefly unreachable.
4. **Graceful degradation** — DB or Redis flap must not corrupt state or cascade into outright API outage.

## Decision

### 1. Single `@Transactional` boundary in `IngestService.ingestNew`

Decision row + matched-rule trace + raw-event row + outbox row + processed-events row commit together or roll back together. Postgres aborts the entire transaction on any constraint violation; the inner method is wrapped by a non-transactional outer method that performs the dedup pre-check, so a unique-constraint failure on `processed_events` (lost race with a concurrent consumer) doesn't poison the active transaction — control falls through to the existing-decision return path.

### 2. Consumer-side idempotency via `processed_events(event_id, consumer_id)`

The natural primary key on the table is `(event_id, consumer_id)`. `ProcessedEventEntity` implements `Persistable` with `isNew()=true` so Spring Data JPA always calls `persist()` (not `merge()`) — a collision throws `DataIntegrityViolationException` instead of silently UPDATE-ing. Two consumers ("rest-api" and "kafka-tx-events") can each process the same event independently; same consumer + same event → silent skip + return existing decision.

### 3. Transactional outbox + scheduled drain

The outbox row is written in the same transaction as the decision. The `OutboxPoller` (`@Scheduled` fixedDelay = 500ms) drains pending rows in batches of 100, publishes each to `tx.decisions.v1` via `KafkaTemplate.send().get(5s)`, and marks `processed_at` on success. Failed rows stay pending; their `retry_count` is bumped; the next tick retries them. Idempotent Kafka producer + at-least-once semantics → downstream consumers must dedup on `decisionId`.

### 4. Resilience4j

- **`database` circuit breaker** wrapping `IngestService.ingestNew` — sliding window 20, 50% failure threshold, 10s open-state. When open, calls fail fast (HTTP 503) and the consumer-side retry/DLT path catches them.
- **`kafka-publish` retry** wrapping `OutboxPoller.drain` — 3 attempts, 200ms backoff, exponential. After exhaustion the rows stay pending for next tick.
- **`redis-state` circuit breaker** wrapping every `RedisStateStore` call — fail-open fallback returns `0` (no velocity match) and `false` (device not new). Rationale: over-blocking on a redis flap is worse than under-blocking for a single stateful predicate; deterministic rules continue to operate.

### 5. Dead-letter topic

Kafka consumer wired with a `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`: 3 retries × 500ms back-off, then route to `tx.events.dlt`. The original payload + exception headers land in the DLT partition for human triage.

## SLO targets

| SLO | Target | How measured |
|---|---|---|
| Ingest p99 latency | < 200ms (dev hardware, 200 rps single node) | k6 load script (Day 5) — `k6/load.js` constant-arrival 200 rps / 60s; `http_req_duration{name:submit}.p99 < 200` threshold |
| Availability | 99.9% (3 nines) | Uptime monitor on `/actuator/health/readiness` |
| Outbox lag | < 5s (alert threshold) | `outbox_lag_seconds` metric (Day 4) |
| Lost events | 0 | Outbox + DLT design; consumer dedup |
| DR — RPO | 5 min | WAL archive + Redpanda topic replication |
| DR — RTO | 15 min | Stateful-set restart + outbox replay (documented in `disaster-recovery.md`) |

## Failure-mode behaviour (verified by `IngestServiceIntegrationTest` against Testcontainers Postgres + Redpanda + Redis)

| Dependency | When down | API behaviour |
|---|---|---|
| Postgres | Down | `database` breaker opens → 503 on ingest; outbox queue retains pending → drains on recovery. |
| Redpanda | Down | REST + DB path stays up; outbox rows accumulate; on recovery the poller catches up. |
| Redis | Down | `redis-state` breaker opens → velocity / device predicates fail-open (return false / 0); deterministic rules continue. |

## Consequences

**Positive.**
- Audit trail is bulletproof: every committed decision has a matching `processed_events` row and an outbox publication record.
- Replay of the same event-stream is safe — decisions stable across replays (verified in `IngestServiceIntegrationTest`).
- Banking review can trace any single decision back to its rule-set version, matched rules, and source event.

**Negative.**
- Outbox-poller batch size and tick interval are tuning levers; defaults (100 / 500ms) trade off throughput vs. latency. A burst > 100/500ms = sustained lag until next tick.
- Fail-open on Redis means a sustained Redis outage degrades velocity/device detection silently — surfaced via two metrics in parallel: Resilience4j's auto-exported `resilience4j_circuitbreaker_calls_total{name="redis-state",kind="failed"}` + `resilience4j_circuitbreaker_state{name="redis-state"}` gauges, plus the domain-named `predicate_state_unavailable_total{operation="velocity"|"device"}` counter wired in `RedisStateStore` fallback methods (Day 7). The domain-named counter distinguishes velocity vs device fail-opens, which Resilience4j alone does not.

## Forward path

- Day 4 wires Bucket4j rate limiting + audit log + idempotency-key header on the API.
- Day 5 runs k6 to confirm the p99 < 200ms SLO and produces capacity numbers.
- Day 6 publishes the full DR runbook in `docs/runbooks/disaster-recovery.md`.
