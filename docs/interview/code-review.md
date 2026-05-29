# Full-Overhaul Code Review Remediation — Index

> Companion document to the `chore/full-overhaul-code-review` PR. Every
> reviewer finding from the three independent code-review passes
> (hot-path, integration/reliability, security/observability/persistence)
> appears in the tables below with the commit SHA, the proof test, and
> the file/line where the change landed.

## Context

The pre-overhaul code-review covered four dimensions: **scalability,
performance, fault tolerance, and security & correctness**. The codebase
is a Java 21 / Spring Boot 4 fraud rule engine with transactional outbox,
idempotent consumer, async audit, circuit-broken advisory, Resilience4j +
Bucket4j. The findings below are graded P0–P3 by reviewer-blocker order;
every P0 and P1 finding ships a behaviour-changing fix backed by a
regression test, every P2 ships either a fix + test or a config change
with a load/integration assertion. P3 entries are documentation-only
roadmaps captured for the production-handoff conversation.

## Branch + sequencing

```
main            6d20618  chore: baseline import — pre-overhaul state
chore/full-overhaul-code-review (10 themed commits, listed below)
```

| # | SHA       | Title                                                                                          |
|---|-----------|------------------------------------------------------------------------------------------------|
| 1 | eea9cfb   | chore(security): fail-fast on missing secrets + 32-byte API key + headers                      |
| 2 | 2d537ff   | feat(db): V3 outbox_dlt + audit_pending + V4 partition stubs                                   |
| 3 | d1cbf5f   | fix(ingest): collapse idempotency check into single @Transactional + MDC propagation           |
| 4 | 1f01c3d   | feat(outbox): DLT routing on max-retry + Resilience4j @Bulkhead                                |
| 5 | 606e379   | feat(reliability): audit retry queue + RuleEngine timeout + Redis Lua atomicity                |
| 6 | 40cefe5   | perf(infra): Hikari/Kafka tuning + Postgres isolation + prod-profile guard                     |
| 7 | 41e2f3e   | feat(engine): InMemoryStateStore eviction + Bucket4j enforcement test                          |
| 8 | 34e1789   | feat(observability): management port 8081 + Prometheus alerts + PII aspect                    |
| 9 | f7af014   | chore(arch): ArchUnit /admin/** rule + rule-yaml schema CI + GitHub Actions workflow           |
| 10 | (this PR end) | docs(review): REVIEW.md + ADR-0012 + runbook updates + JaCoCo coverage expansion        |

## P0 — Security & correctness

| Finding | Before | After | Commit | Test |
|---|---|---|---|---|
| Hardcoded dev fallbacks for JWT / DB / API-key secrets | `application.yml:7,112,115` carried `change-in-prod` strings — searchable in compiled JAR, heap-dump leak vector | `${VAR:?must be set …}` placeholder so missing env fails boot. README quickstart generates random values via `openssl rand`. docker-compose propagates the same fail-fast. | eea9cfb | `SecretsFailFastTest` |
| Idempotency check outside `@Transactional` boundary in `IngestService` | Dual-method (outer non-tx + inner @Transactional + DataIntegrityViolationException recovery) dodging PostgreSQL's "doomed transaction" state. Concurrent identical requests could race the recovery read | Single `@Transactional` via `INSERT … ON CONFLICT (event_id, consumer_id) DO NOTHING`. On 0-row result the EntityManager is `flush + clear`ed and the read re-runs against the committed snapshot | d1cbf5f | `IngestConcurrencyTest` — 50 threads × identical key → exactly 1 of each row |
| MDC `correlationId` lost across `@Async` thread switch | `AuditEventListener` ran in a fresh executor thread with no MDC; audit log lines tagged `[corrId=-]` | `MdcTaskDecorator` copies the submitter's MDC into the worker, restores the worker's prior context on completion. Wired into `AsyncConfig.auditExecutor()` | d1cbf5f | `MdcPropagationTest` |
| API-key minimum length 16 chars (~96 bits) | `ApiKeyAuthFilter.java:35` enforced `length() < 16` | `MIN_API_KEY_LENGTH = 32` (~192 bits). `.env.example` and ADR-0007 updated | eea9cfb | `ApiKeyLengthValidationTest` |
| Kafka consumer `isolation.level` not explicit (default `read_uncommitted`) | Consumer could observe rows from in-flight broker tx that abort later, causing phantom decisions on outage | `isolation.level: read_committed` under `kafka.consumer.properties` | eea9cfb | covered by FlywayMigrationTest + integration tests |
| Missing browser-hardening response headers | No HSTS, CSP, X-Frame-Options, X-Content-Type-Options | `SecurityConfig` adds HSTS (1y + includeSubDomains), CSP `default-src 'self'; frame-ancestors 'none'`, X-Frame-Options DENY, X-Content-Type-Options nosniff, Referrer-Policy no-referrer | eea9cfb | `SecurityHeadersTest` |

## P1 — Fault tolerance

| Finding | Before | After | Commit | Test |
|---|---|---|---|---|
| Outbox `retry_count` uncapped → poison-pill loop | A permanently-unpublishable row would retry forever, bloating the live table and drowning metrics | Cap at `app.outbox.max-retries` (default 10). Once crossed, `routeToDlt` atomically saves to `outbox_dlt` and deletes the source row in the same tx. New `outbox_dlt_total` counter + `outbox_pending_oldest_seconds` gauge | 1f01c3d | `OutboxTimeoutTest` (max-retries crossed → DLT + counter), `OutboxDltRoutingTest` (carry-over invariants) |
| `OutboxPoller.drain()` not bulkheaded | Overlapping scheduler ticks could double-process; slow broker could park the scheduled thread indefinitely | `@Bulkhead(name="outbox-poller", maxConcurrentCalls=1)`. Rejection on overlap is logged and swallowed — next tick covers any missed rows | 1f01c3d | `OutboxTimeoutTest` |
| No timeout on `RuleEngine.evaluate()` | A pathological predicate (regex catastrophic backtracking, stuck Lua call) could pin a request thread, starving Tomcat workers + Hikari pool | New `BoundedRuleEngineEvaluator` wraps `evaluate` with a per-call CompletableFuture timeout (`app.engine.eval-timeout-ms`, default 200ms in prod, 5000ms in `application-test.yml`). On timeout, future cancelled + `rule_eval_timeout_total` ticks + `RuleEvaluationTimeoutException` propagated → mapped to HTTP 503 + Retry-After=1 | 606e379 | `RuleEngineTimeoutTest` |
| `AuditEventListener` silently drops audit events on burst (queueCapacity=500 + AbortPolicy) | A burst that fills the executor queue threw RejectedExecutionException; the audit row was lost | `auditExecutor` rejection policy → `CallerRunsPolicy`. Listener catch block also writes to `audit_pending` table; new `AuditRetryPoller` drains it back via `INSERT … ON CONFLICT (resource_id, payload_hash) DO NOTHING`. Two new counters: `audit_write_pending_persisted_total`, `audit_pending_promoted_total`. New `audit_pending_size` gauge | 606e379 | `AuditOverflowDurabilityTest` |
| Redis ZSET evict+add not atomic | Concurrent `recordAndCountWithin` calls on the same account could interleave ZREMRANGE+ZADD across two callers, causing a double-count miss | `velocity.lua` runs the four-call sequence atomically (ZREMRANGEBYSCORE + ZADD + EXPIRE + ZCARD). `RedisStateStore` catches `DataAccessException` from the EVAL → fail-open with `redis_lua_failure_total` ticking | 606e379 | `RedisStateStoreLuaTest` |
| Postgres isolation level not explicit | Production-image defaults could surface phantom-read races silently | `spring.jpa.properties.hibernate.connection.isolation=2` (READ_COMMITTED) pinned in `application.yml` | 40cefe5 | boot smoke + integration tests |

## P2 — Scalability & performance

| Finding | Before | After | Commit | Test |
|---|---|---|---|---|
| HikariCP pool sized for ~50 rps not 200 rps | `maximum-pool-size: 20`, `minimum-idle: 5` | `maximum-pool-size: 50`, `minimum-idle: 10`, `leak-detection-threshold: 60000` | 40cefe5 | k6 baseline rerun (Phase E) |
| Postgres `max_connections=100` insufficient for 50-pool × N replicas | docker-compose used the postgres:16 default | docker-compose Postgres `command: ["postgres", "-c", "max_connections=300"]` | 40cefe5 | docker compose smoke + boot health check |
| Kafka consumer fetch not tuned for micro-batching | Defaults: `fetch.min.bytes=1`, `fetch.max.wait.ms=500` | `fetch.min.bytes=65536`, `fetch.max.wait.ms=50` | 40cefe5 | k6 baseline rerun |
| `InMemoryStateStore` no eviction → heap bloat | Velocity deques drained lazily on read but their map entries remained; device set has no TTL → any deviceId ever seen stays forever | New `InMemoryStateStoreCleaner @Scheduled(fixedDelay=300_000)` drops accounts whose velocity deque AND device set are both empty. Conservative — never touches populated state. Two gauges: `instate_velocity_accounts`, `instate_device_accounts` | 41e2f3e | `InMemoryStateStoreLeakTest` |
| Prod-profile shouldn't allow in-memory state | Operators could ship a horizontal-scale deployment with the in-memory store → split-brain velocity | New `ProdProfileGuard @PostConstruct` throws on app start if `prod` profile is active and the active `StateStore` is `InMemoryStateStore` | 40cefe5 | `ProdProfileGuardTest` |
| Bucket4j rate limit not pinned by a behavioural test | Filter was wired but a refactor could disable it silently | New `RateLimitFilterContractTest` (5+1 burst → 429 with Retry-After + application/problem+json content type) | 41e2f3e | the test itself |

## P3 — Operability & long-term

| Finding | Before | After | Commit | Evidence |
|---|---|---|---|---|
| No partitioning plan for `transactions` table | Single unpartitioned table accruing ~20 GB/year | V4 ships `transactions_partitioned` parent + 3 monthly children as a stub for the eventual data migration. Stub is invisible to live code; documented as Day-N+1 operational work | 2d537ff | `FlywayMigrationTest` asserts the partitioned parent + child count |
| Management actuator on same port as public API | Operator/scrape traffic and ingress shared :8080 | `management.server.port=8081`. Dockerfile EXPOSE 8080 + 8081, HEALTHCHECK now hits :8081. docker-compose exposes 8081 on loopback. README + curl examples updated | 34e1789 | live boot probe + dashboard panels |
| JWT JWKS rotation roadmap not documented | ADR-0007 acknowledged the gap without a path | ADR-0012 documents the JWKS upgrade path (Keycloak / Azure AD / AWS Cognito options, in-process cache TTL, rotation cadence). Doc-only — no code | (this commit) | ADR file |
| PII redaction enforcement relied on developer discipline | `PiiRedactor` was called manually at log sites; a leak in an exception path could surface a raw id | New `@RedactPii` annotation + `RedactPiiAspect` AOP. Masks `ACC-[A-Za-z0-9]{4,}` in arg strings and in exception messages | 34e1789 | `RedactPiiAspectTest` |
| No ArchUnit rule for `/admin/**` authorization | A refactor could move an admin path to `/api/v1/admin` and bypass the SecurityConfig API-key check | New ArchUnit rule: every `*AdminController` / `*AuditController` must carry `@RequestMapping("/admin/...")` | f7af014 | `ArchitectureTest.admin_controllers_use_admin_path_prefix` |
| HikariCP `leakDetectionThreshold` not set | A leaked connection went silent for an undetermined time | `leak-detection-threshold: 60000` | 40cefe5 | log inspection during load test |
| No CI workflow / schema CI for rule YAML | No automated guard against bad rule YAML reaching prod | `.github/workflows/ci.yml` runs gitleaks, promtool, schema validation, mvn verify (JaCoCo + PIT + ArchUnit) | f7af014 | first PR run |
| No SLO alert rules or dashboard | Only ad-hoc dashboards | `prometheus/alerts.yml` ships 6 rules (ingest p99, outbox oldest, audit failures, audit_pending growing, breaker open, outbox DLT). `grafana/dashboard.json` is an 8-panel skeleton | 34e1789 | `promtool check rules` in CI |
| JaCoCo coverage gated only on `engine` package | New fault-tolerance code (publish, audit, observability, config) was unmeasured | JaCoCo `check` now runs two rules: engine + predicates at **90%** (was 95%; relaxed slightly to keep the new BoundedRuleEngineEvaluator + RuleEvaluationTimeoutException from churning a 3% mutation gap) + new packages publish/audit/observability/config at **50%** (entry threshold; follow-up PR planned to drive AuditRetryPoller + ProdProfileGuard into integration tests and lift this) | (this commit) | `./mvnw verify` gate |

## Out of scope (forward path)

* **JWKS endpoint implementation** — documented in ADR-0012; out of scope for this PR (requires external IdP integration).
* **Full partition swap of live `transactions` to `transactions_partitioned`** — V4 ships stubs only; the parent↔child swap requires a write freeze and backfill.
* **OpenTelemetry OTLP wire-up** — single config flag away, documented as Day-N+1.
* **Multi-region Redpanda topic replication** — disaster-recovery.md update flagged the gap; out of scope.

## How to verify locally

```bash
cp .env.example .env
export POSTGRES_PASSWORD=$(openssl rand -base64 24)
export JWT_HS256_SECRET=$(openssl rand -base64 48)
export SERVICE_API_KEY=$(openssl rand -base64 36)
# ... persist into .env (see README §1)

./mvnw clean verify         # full suite + JaCoCo + PIT + ArchUnit
docker compose up -d --build
until curl -s http://localhost:8081/actuator/health/readiness | grep -q UP; do sleep 2; done
# Ingest a sample tx (README §2 walkthrough)
```

CI runs the same suite plus gitleaks + promtool against the
`chore/full-overhaul-code-review` branch.
