# 25 likely interview questions with crisp answers

Grouped by theme. Each answer is ≤ 60 words — interview-pace, not a thesis.

## Architecture

**1. Why monolith, not microservices?**
Banking audit needs a single transaction across decisioning + outbox + audit. Microservices split that into eventually-consistent fragments and dilute the audit trail. Modular monolith with strict package boundaries (ArchUnit-enforced) gives me the ACID guarantees the regulator wants without the operational complexity. Forward path: extract `advisory` or `ingest` to separate services when partitioning demands it.

**2. Why YAML rules + Java predicates, not Drools?**
Drools brings a RETE engine + MVEL-like syntax that adds two weeks of learning curve for whoever maintains it after me. My rule set is composed of typed Java predicates registered by id, evaluated by a small AST walker (Atomic / All / Any). Sub-millisecond evaluation, type-safe args via PredicateArgs helpers, JSON-Schema validated YAML for the composition. ADR-0005 has the full comparison.

**3. Why Redpanda not Kafka?**
Kafka-compatible API, but a single binary in compose — no KRaft init dance, no Zookeeper. The Spring Kafka client doesn't know the difference. Production switch is documented as ADR-0003 §"Forward path" if the broker fleet is canonical Kafka.

**4. Why Postgres not Mongo for decisions?**
Decisions are relational: a decision row + its matched-rule trace (1-to-N) + its outbox row, all in one ACID transaction. JSONB handles the raw event payload. Mongo would force eventual consistency between the decision and the trace, and the bank can't have a flagged transaction whose explanation is missing.

## Rule engine

**5. How do you ensure decisions are deterministic?**
Rules sort by priority desc with `id` as tiebreaker — without the tiebreaker, two rules with equal priority sort in arbitrary JVM-hash order and `shortCircuit:true` could either fire or yield non-deterministically. Caught this in Day-2.5 self-review. AtomicReference snapshot is taken at the start of every evaluation so a reload mid-evaluation can't mix versions.

**6. How is a rule change audited?**
Every reload bumps `rule_versions.version` and writes to `audit_log` with `action=RULES_RELOAD` and the YAML SHA-256 hash. Every decision row carries `rule_set_version`. The reviewer queries the audit log by version + the decisions where that version fired.

**7. What's the rule-loading failure mode?**
JSON-Schema validation runs before domain construction. Bad YAML → 422 with field-level errors AND the old ruleset stays active. `rules_reload_failed_total` increments so alerts fire. Validation happens at startup too — `@PostConstruct` in `RuleSetInitializer`, so a bad rules.yml aborts the container instead of letting the app come up "ready" but unable to evaluate.

**8. Race between evaluation and reload?**
AtomicReference swap is atomic — the new ruleset is fully constructed before `engine.install(rs)` makes it visible. In-flight evaluations finish on the old reference. There's a 50-evaluator-during-50-reloads `HotReloadRaceTest` that asserts every decision carries either v_old or v_new, never null, never mixed.

## Async + persistence

**9. Why outbox not direct Kafka send?**
Two writes (Postgres + Kafka) without a distributed transaction would fail the ACID expectation. Outbox makes the publish part of the same DB transaction; a separate poller drains to Kafka. Crash anywhere preserves the invariant: the row is durable, the poller catches it on next tick.

**10. How is multi-instance safety guaranteed for the outbox?**
`SELECT … FOR UPDATE SKIP LOCKED` (Postgres-native). Two API replicas each claim a disjoint subset of pending rows. Caught the gap in Day-3.5 self-review — the original JPA query had no lock so both replicas would have double-published.

**11. What if Kafka is down?**
Outbox rows accumulate. `outbox_lag_seconds` rises. Alert fires. The deterministic decision is still made and persisted; the publish is delayed, not lost. Once Kafka returns, the poller catches up.

**12. Consumer-side deduplication?**
`processed_events(event_id, consumer_id)` with a unique constraint. The consumer writes this row inside the same transaction as the decision. Kafka redelivery on the same offset hits the constraint, the existing decision is returned. Zero double-decisions across a 1000-event replay test.

## Security

**13. Why HS256 not RS256 for the JWT?**
HS256 for the demo so the submission is self-contained (no IdP needed). RS256 + JWKS is the production migration documented in ADR-0007. The decoder is wired with `JwtValidators.createDefaultWithIssuer` so foreign-iss tokens fail.

**14. Could anyone mint a SERVICE token via /auth/token?**
Caught and closed in Day-4.5 review. The endpoint now hard-codes `ROLE_USER` regardless of request body. SERVICE access is reachable only via the static `X-Service-Api-Key` header. Production: kill the demo endpoint and integrate against a real IdP.

**15. PII in logs?**
`PiiRedactor.redactAccountId()` is called at every `accountId` log site (`IngestService.ingest` paths, `TransactionKafkaListener` paths). Format `ABC-****1234`. POPIA-compliant. Validated by grepping `src/main` for usages — wasn't wired before Day-4.5 review; now 4 sites (2 in IngestService, 2 in TransactionKafkaListener).

**16. CSRF / session fixation?**
Stateless session policy + CSRF disabled — correct for a token-based API. CORS is not configured in this submission because the surface is server-to-server; a customer-facing browser client would need a `CorsConfigurationSource` bean.

## Operations

**17. SLOs?**
P99 ingest latency < 200 ms (verified by k6 single-node, 200 rps). Availability 99.9 %/month on a single-VM stack. RPO 5 min via Postgres WAL archive + outbox replay. RTO 15 min via the DR runbook. Quarterly drill.

**18. What's the audit-gap problem?**
The audit listener is `@Async @Transactional(REQUIRES_NEW)` for latency — but if the audit write fails, the business transaction has already committed. `audit_write_failed_total` counter + ERROR log surface the gap. Day-7 work adds a durable retry queue so the gap is recoverable, not just observable.

**19. How is rate-limiting enforced across replicas?**
Bucket4j-with-Redis. The bucket key is `rl:{jwt_sub}`. All API instances share the counter. ROLE_SERVICE bypasses the limiter (Prometheus scrape can't be throttled). 429 + Retry-After when exhausted. `rate_limit_exceeded_total` counter.

## Tests

**20. Why 95% coverage gate and not 100%?**
100% catches diminishing returns — defensive `throw new IllegalStateException("unreachable")` lines and the like. 95% guarantees no large slab of untested logic. Engine is at 98.1%. The gate is in `jacoco:check` on the `verify` phase, so a PR can't merge without it.

**21. How do you stop tests from passing while production breaks?**
Three layers: unit tests on predicates + engine, 12 golden cases at the YAML→decision level, Testcontainers integration tests on the full pipeline (PG + Redpanda + Redis). Plus a hot-reload race test. Day-7 adds Pitest mutation testing on the engine package — already wired in `pom.xml` with a 70% mutation threshold.

**22. Contract tests?**
springdoc generates the OpenAPI spec from controller signatures. Day-7 polish adds a CI step that diffs the generated spec against a checked-in baseline — breaking changes fail the build.

## AI advisory

**23. How is hallucination measured?**
Twenty expert-labelled fixture transactions. The eval rubric measures agreement rate (≥ 70%), human-review invariant (100%, hard), no-PII rate (100%, hard), no-overturn rate (≥ 95%), latency p95 (≤ 2000 ms), malformed rate (≤ 5%). Run quarterly + nightly in production. Full spec in `docs/advisory-eval.md`.

**24. What if Ollama hangs?**
2-second hard read timeout (`JdkClientHttpRequestFactory.setReadTimeout`) — caught and fixed in Day-5.5 review; the original code only set connectTimeout. Resilience4j circuit breaker on `advisory` opens after sustained failure. Endpoint returns 503 + Retry-After. Core ingest unaffected.

**25. Why an LLM at all if it's never authoritative?**
Reviewer productivity. An analyst looking at a flagged transaction reads the rule trace plus a 140-character LLM commentary. Saves seconds. Over a million transactions a year that's real time. The boundary is the value: AI is fine for narrative, never for the verdict.
