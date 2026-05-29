# Acceptance criteria — NFR → test mapping

Every requirement maps to a test or a runbook drill. "Documented" means there's an ADR but no automated test (deliberate; cited).

| NFR | Acceptance criterion | Where verified |
|---|---|---|
| AC-01 — Deterministic decisions | Same input always produces same decision across JVM restarts | `RuleEngineTest.status_escalates_to_strictest` + `RuleSet` sort tiebreaker (Day 2.5) |
| AC-02 — Rule-set versioning | Every decision carries `rule_set_version` | `RuleEngineTest.decision_carries_rule_set_version` + Flyway `decisions` schema |
| AC-03 — Hot reload atomicity | 50 concurrent evaluations during 50 reloads → no exception, no null version | `HotReloadRaceTest.atomic_swap_under_concurrent_evaluation` |
| AC-04 — Composition correctness | Each of 6 rules has match + no-match fixture | `GoldenCasesTest` (12 cases) |
| AC-05 — Layering | `api` cannot directly import `persistence` | `ArchitectureTest.api_must_not_depend_on_persistence_directly` |
| AC-06 — Domain purity | `domain` has no framework deps | `ArchitectureTest.domain_must_not_depend_on_frameworks` |
| AC-07 — Advisory isolation | Advisory only reachable from api / engine / config | `ArchitectureTest.advisory_must_only_be_called_from_api_or_engine` |
| AC-08 — Transactional persistence | All-or-nothing across decision + outbox + processed_events | `IngestServiceIntegrationTest.ingest_persists_full_set` |
| AC-09 — Idempotent replay | Replay of same Kafka event produces zero double-decisions across 1000 events | `IngestServiceIntegrationTest.replay_is_idempotent` |
| AC-10 — Outbox drain | All pending rows drained within 20s in Testcontainers env | `IngestServiceIntegrationTest.outbox_drains` |
| AC-11 — Engine coverage ≥ 95% | jacoco:check enforces in `verify` | `pom.xml` jacoco plugin + CI |
| AC-12 — RFC 7807 errors | Every error response is `application/problem+json` with correlationId | Reviewer smoke: send a malformed body (e.g. negative `amount`) to `/api/v1/transactions`; observe `Content-Type: application/problem+json` + `correlationId`. Centralised in `ProblemDetailExceptionHandler`. |
| AC-13 — JWT secured surface | `/api/v1/**` returns 401 without token | Reviewer smoke: `curl -i http://localhost:8090/api/v1/decisions` (no `Authorization` header) → 401. Wired in `SecurityConfig.filterChain`. |
| AC-14 — API-key admin | `/admin/**` returns 401 without `X-Service-Api-Key` | Reviewer smoke: `curl -i http://localhost:8090/admin/audit?size=3` → 401; same path with `X-Service-Api-Key: $API_KEY` (README §2(e)) → 200. Wired in `ApiKeyAuthFilter` + `SecurityConfig`. |
| AC-15 — Role escalation blocked | Caller cannot mint `ROLE_SERVICE` via `/auth/token` | `AuthController.ALLOWED_ROLES = Set.of("USER")` is the only role list; request body cannot override. Reviewer smoke: issue a token via README §2(a), base64-decode the JWT body, confirm `authorities` is `[ROLE_USER]`. |
| AC-16 — Idempotency-Key collision | Same key + different body → 409 | Reviewer smoke: POST `/api/v1/transactions` twice with the same `Idempotency-Key` and different bodies; second call returns 409 + `application/problem+json` with `previousRequestId`. Wired in `IdempotencyService` + `TransactionController`. |
| AC-17 — Rate limit | 101st req/min per JWT sub → 429 + Retry-After | `RateLimitFilter` (Bucket4j + Redis, 100/min per JWT `sub`). Reviewer smoke: `for i in $(seq 1 105); do curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/v1/decisions; done` → the 101st returns 429 + `Retry-After`. |
| AC-18 — Audit hash | `audit_log.payload_hash` is SHA-256 of canonical decision JSON | Reviewer smoke: `curl /admin/audit` (README §2(e)) and observe `payloadHash` is a 64-hex-char SHA-256. Produced in `IngestService.publishAuditEvent` from canonical decision JSON via `LinkedHashMap`. |
| AC-19 — PII redaction in logs | `accountId` masked as `ABC-****1234` in `IngestService` + `TransactionKafkaListener` logs | Code-grep `PiiRedactor.redactAccountId` in `src/main` — currently 4 production call sites: 2 in IngestService (idempotency-hit + race paths) and 2 in TransactionKafkaListener (success + failure paths) |
| AC-20 — JWT issuer enforced | Tokens with foreign `iss` claim rejected | `SecurityConfig.jwtDecoder` uses `JwtValidators.createDefaultWithIssuer` (Day 5) |
| AC-21 — p99 ingest < 200ms | k6 single-node 200 rps run | Reviewer smoke: run k6 via Docker as documented in `k6/load.js` header (constant-arrival 200 rps / 60 s). The script enforces `submit_latency_ms.p99 < 200` as a hard threshold — k6 exits non-zero on breach. |
| AC-22 — RPO 5 min / RTO 15 min | Documented procedure | [runbooks/disaster-recovery.md](../../runbooks/disaster-recovery.md) |
| AC-23 — Advisory non-blocking | Core decisioning unaffected when Ollama is down | Reviewer smoke: with default profile (no advisory), `curl /api/v1/decisions/<id>/advisory` → 503 + `Retry-After: 10`; ingest & query endpoints stay 200. With `--profile advisory` after `docker compose stop ollama`, same behaviour confirms the circuit-breaker fallback. Wired in `OllamaAdvisoryService` + `NoopAdvisoryService`. |
| AC-24 — Advisory timeout | Hard 2s read timeout | Day 5.5 fix: `JdkClientHttpRequestFactory.setReadTimeout` |
| AC-25 — Human-review invariant | `humanReviewRequired=true` on every AdvisoryResponse | `AdvisoryServiceTest.factory_methods_satisfy_invariants` |
| AC-26 — Hallucination measurable | Eval rubric with 6 thresholded metrics | [docs/advisory-eval.md](../../advisory-eval.md) |
| AC-27 — Outbox multi-replica safe | Two pollers don't double-publish | `OutboxRepository.findPendingForUpdate` uses SELECT FOR UPDATE SKIP LOCKED (Day 3.5) |
| AC-28 — Reload failure non-disruptive | Bad YAML → 422, old set stays active | `AdminController` 422 path + `RuleSetInitializer.@PostConstruct` for boot failure |
