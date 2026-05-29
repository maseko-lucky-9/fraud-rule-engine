# Implementation walkthrough

A pointer-style tour of the code in the order a reviewer would naturally read it.

## Entry points (start here)

1. **`api/TransactionController.java`** — POST `/api/v1/transactions`. JWT-gated. Reads `Idempotency-Key`, hashes the canonical body, looks up the Redis cache. On miss: calls `IngestService.ingest(tx, CONSUMER_REST)` and caches the result.
2. **`ingest/TransactionKafkaListener.java`** — `@KafkaListener(topics="tx.events.v1")`. Manual ack. Same `IngestService.ingest()` call with `CONSUMER_KAFKA`.

## The single writer

3. **`ingest/IngestService.java`** — the only place that writes.
   - `ingest()` (non-transactional) — dedup pre-check via `processed_events.existsById`.
   - `ingestNew()` (`@Transactional @CircuitBreaker(name="database")`) — atomic write of all four rows.
   - Race recovery via `DataIntegrityViolationException` catch.

## Deterministic core

4. **`engine/RuleEngine.java`** — `evaluate(Transaction)`. `AtomicReference<RuleSet>` snapshot. Walks the `Condition` AST. Computes max-score, strictest-status, short-circuits on `shortCircuit:true`.
5. **`engine/RuleLoader.java`** — YAML → JSON-Schema validate → domain mapping. Throws `RuleValidationException` on any failure.
6. **`engine/RuleSetInitializer.java`** — `@PostConstruct` loads the rule set at startup. Aborts boot on failure (Day 2.5 fix — previously deferred to ApplicationReadyEvent and the app would silently come up "ready" with no rules installed).
7. **`engine/PredicateRegistry.java`** — `List<Predicate>` injected → `Map<id, Predicate>`. Fails fast on duplicate ids.
8. **`engine/predicates/*.java`** — 8 stateless predicates. Stateful ones (`velocity`, `deviceFingerprintNew`) accept a `StateStore` from `PredicateContext`.

## Out-of-band

9. **`publish/OutboxPoller.java`** — `@Scheduled(fixedDelayString=500ms)`. `findPendingForUpdate(batchSize)` uses `SELECT FOR UPDATE SKIP LOCKED`. Parallel `CompletableFuture` Kafka sends; per-row settlement (Day 3.5).
10. **`audit/AuditEventListener.java`** — `@Async("auditExecutor") @Transactional(REQUIRES_NEW)`. Bumps `audit_write_failed_total` on failure (Day 5).

## Security

11. **`security/SecurityConfig.java`** — stateless, oauth2-resource-server JWT (issuer-validated), ApiKeyAuthFilter ordered before BearerTokenAuthenticationFilter, `FilterRegistrationBean(enabled=false)` to prevent servlet auto-registration (Day 4.5).
12. **`security/ApiKeyAuthFilter.java`** — constant-time compare on `X-Service-Api-Key`.
13. **`security/AuthController.java`** — demo `/auth/token` mints `ROLE_USER` only (Day 4.5; role escalation closed).

## Cross-cutting

14. **`api/ProblemDetailExceptionHandler.java`** — RFC 7807 for every exception, with `correlationId` from MDC.
15. **`observability/CorrelationIdFilter.java`** — `X-Correlation-Id` request + response header + MDC.
16. **`observability/EngineMetrics.java`** — `fraud_decisions_total{status}`, `rule_eval_duration_seconds` histogram.
17. **`observability/PiiRedactor.java`** — `ABC-****1234` masking. Called from `IngestService` + `TransactionKafkaListener`.

## Advisory (optional)

18. **`advisory/OllamaAdvisoryService.java`** — `@Profile=advisory`. 2s read timeout (Day 5.5 fix). `@CircuitBreaker(name="advisory", fallbackMethod="fallback")`. Strict JSON parser.
19. **`advisory/NoopAdvisoryService.java`** — default. Returns `UNAVAILABLE` so the endpoint contract is consistent.
20. **`api/AdvisoryController.java`** — `GET /api/v1/decisions/{id}/advisory`. 503 + `Retry-After:10` on `UNAVAILABLE`/`TIMED_OUT`.

## Read API

21. **`api/DecisionController.java`** — `GET /api/v1/decisions` + `/{id}`. Delegates to `query/DecisionQueryService` so `api` doesn't import `persistence` (ArchUnit).
22. **`audit/AuditController.java`** — `GET /admin/audit`. Same `query`-layer pattern via `AuditQueryService`.

## Configuration

23. **`src/main/resources/application.yml`** — datasource, redis, kafka, actuator probes, springdoc, resilience4j config, app-specific settings.
24. **`src/main/resources/rules/rule-set-v1.yml`** — 6 composed rules.
25. **`src/main/resources/rules/rule-set.schema.json`** — JSON Schema for rule validation.
26. **`src/main/resources/prompts/advisory-v1.md`** — versioned prompt template.

## Build

27. **`pom.xml`** — Spring Boot 4.0.6 parent; deps for OAuth2 RS, springdoc, archunit, resilience4j, bucket4j, jackson-yaml, json-schema-validator, logstash-logback-encoder. Plugins: Jacoco (95% engine gate), Pitest (70% mutation threshold on engine + predicates + domain).
28. **`Dockerfile`** — multi-stage; builder uses `eclipse-temurin:21-jdk-alpine`, runtime uses `21-jre-alpine` with non-root user + curl HEALTHCHECK.
29. **`docker-compose.yml`** + **`docker-compose.advisory.yml`** — core stack + optional Ollama overlay.

## Tests

30. **`src/test/java/.../architecture/ArchitectureTest.java`** — 3 ArchUnit rules.
31. **`src/test/java/.../engine/HotReloadRaceTest.java`** — 50 concurrent evaluations × 50 reloads.
32. **`src/test/java/.../engine/GoldenCasesTest.java`** — 12 parameterised fixtures.
33. **`src/test/java/.../ingest/IngestServiceIntegrationTest.java`** — Testcontainers PG + Redpanda + Redis. End-to-end ingest → persist → drain.
34. **`src/test/resources/fixtures/golden-cases.json`** — the 12 cases.
