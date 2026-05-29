# 10-minute talk track

Times are cumulative. Cut from the bottom up if running short. Everything assumes Swagger UI on screen at <http://localhost:8090/swagger-ui.html> + a second tab with [ARCHITECTURE.md](../../../ARCHITECTURE.md).

---

## 0:00 — Frame (45 s)

> "I built a fraud rule engine. I treated it as if it were going into a bank's payment path — not a demo. The deterministic rule engine is the only source of truth; everything else is supporting infrastructure. There's an optional AI advisory, but it's structurally incapable of overruling a verdict — that boundary is enforced at the API surface, the data model, and the policy. Let me show you what I mean."

## 0:45 — Quickstart (45 s)

Run live (precommit the token):

```bash
docker compose up -d   # already running, but show the ps
docker compose ps      # 4 healthy: api, postgres, redis, redpanda
curl -s http://localhost:8090/actuator/health | jq
```

> "Three commands from a clean clone. README walks through this."

## 1:30 — Submit a transaction (90 s)

```bash
TOKEN=$(curl -s -X POST http://localhost:8090/auth/token -H 'Content-Type: application/json' -d '{"subject":"alice"}' | jq -r .accessToken)

curl -s -X POST http://localhost:8090/api/v1/transactions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-$(date +%s)" \
  -H "Content-Type: application/json" \
  -d @demo-tx.json | jq
```

Show the response. Point to:
- `status: REVIEW`, `score: 0.85`
- `matchedRules[0].ruleId: HIGH_AMOUNT_NEW_ACCOUNT`, `reason` text
- `ruleSetVersion: 1` — "this is what a reviewer needs to reproduce the decision months later"

## 3:00 — Show the rule (45 s)

`src/main/resources/rules/rule-set-v1.yml` — point to `HIGH_AMOUNT_NEW_ACCOUNT`:

```yaml
- id: HIGH_AMOUNT_NEW_ACCOUNT
  priority: 800
  condition:
    all:
      - { predicate: amountAbove, args: { value: 10000, currency: ZAR } }
      - { predicate: accountAgeBelow, args: { days: 30 } }
  action: { flag: REVIEW, score: 0.85, reason: "High amount on a young account." }
```

> "YAML for composition; Java for the predicates. Each predicate is a Spring `@Component` keyed by id; the registry fails fast on duplicates. Adding a rule is a YAML change; adding a predicate is code. That's the right boundary."

## 3:45 — Hot reload, atomic swap (45 s)

```bash
curl -X POST http://localhost:8090/admin/rules/reload -H "X-Service-Api-Key: $API_KEY"
```

> "AtomicReference swap. In-flight evaluations finish on the old set. If the YAML is bad, we return 422 with field-level errors and keep the old set serving — never a downtime."

## 4:30 — Async + outbox (60 s)

Open [ARCHITECTURE.md §4 sequence diagram](../../../ARCHITECTURE.md#4-data-flow--happy-path-sequence).

> "REST hits IngestService. Single `@Transactional`: insert processed_events, transactions, decision, decision_rules, outbox row. Then `OutboxPoller` drains the outbox to Kafka via `SELECT … FOR UPDATE SKIP LOCKED` so two replicas don't double-publish. Crash between commit and publish is safe — the row is durable. Crash before commit is safe — Kafka redelivers and we dedup on `processed_events`."

## 5:30 — Security boundary (60 s)

> "JWT for clients, service API key for admin. The demo `/auth/token` is allowed to mint USER only — closing a hole I caught in self-review where any caller could mint themselves SERVICE. ApiKey filter uses constant-time compare, registered with `FilterRegistrationBean(enabled=false)` so it only runs inside the SecurityFilterChain — Spring Boot was auto-registering it via servlet which was making the security chain a no-op."

Show ADR-0007 footgun section briefly.

## 6:30 — AI advisory boundary (60 s)

> "Separate endpoint: GET decisions/{id}/advisory. 2-second hard timeout — I caught in review that the original code only set connectTimeout, never readTimeout, so the 'hard 2s' was a lie. Fixed. Circuit breaker on top. And every response carries `humanReviewRequired=true`, set in code, not by the model. The prompt template forbids PII inclusion. There's a written 20-fixture hallucination evaluation rubric — the eval *script* is Day-7 polish; the rubric is design-complete."

```bash
# Show 503 fallback
curl -s http://localhost:8090/api/v1/decisions/<id>/advisory -H "Authorization: Bearer $TOKEN"
# {advisoryStatus: UNAVAILABLE, humanReviewRequired: true}
```

## 7:30 — Tests + verification (60 s)

> "78 tests. Engine line coverage 98%, gate at 95% in jacoco:check. ArchUnit pins three boundaries: api can't import persistence, domain has no Spring deps, advisory only reachable from api/engine. 12 golden cases for the rule engine. Hot-reload race test runs 50 concurrent evaluations during 50 reloads."

```bash
./mvnw verify | grep "Tests run:" | tail -3
```

## 8:30 — Observability + runbooks (45 s)

> "Domain metrics on /actuator/prometheus: `fraud_decisions_total{status}`, `rule_eval_duration_seconds` percentile histogram, `idempotency_cache_size`, `audit_write_failed_total`. Correlation-Id in every log line + response header. Six runbooks: db-down, kafka-lag, rule-misfire, bad-payload, deploy-rollback, disaster-recovery — each with symptoms, immediate steps, recovery, verification drill."

## 9:15 — What I'd do next (45 s)

> "Three things I deferred and would tackle first: WireMock-based unit tests for the Ollama adapter, durable retry queue for the audit listener so 'commit succeeded, audit failed' is recoverable not just observable, and OAuth2 against an external IdP instead of HS256 demo tokens. All documented in the ADRs with the migration path."

## 10:00 — Stop. Q&A.
