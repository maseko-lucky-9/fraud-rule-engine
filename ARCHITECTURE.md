# Architecture — Fraud Rule Engine

This document is the reviewer's deep-dive companion to [README.md](README.md). It explains the design choices behind the running system, the trade-offs that were rejected, and the operational shape (failure modes, runbooks, SLOs).

For decision-by-decision rationale see the ADRs in [docs/adr/](docs/adr/).

---

## 1. Why this shape

A bank's fraud-engine has to be **deterministic, explainable, and auditable** — every flagged transaction must answer "which rule fired, on which input, against which rule-set version, who reviewed it". A modular monolith with a deterministic engine + transactional outbox + audit log delivers that directly; microservices would dilute the audit trail across multiple service-owned databases and introduce eventual-consistency questions the regulator does not want to hear.

AI sits **adjacent**, not inline. The deterministic decision exists before any LLM call. The advisor describes the decision; it cannot overrule it. This boundary is enforced at the API surface (separate endpoint, separate timeout, separate compose profile), the data model (no advisory column on `decisions`), and the policy (`humanReviewRequired` is always true).

---

## 2. C4 — Context

```
                  ┌──────────────────────┐
                  │   Ops console        │ (admin)
                  └─────────┬────────────┘
                            │ X-Service-Api-Key
                            ▼
┌──────────────┐      ┌────────────────────┐      ┌──────────────────┐
│  Card system │─────►│  fraud-rule-engine │─────►│ Decisions stream │
│  / merchant  │ JWT  │  (this submission) │ Kafka│ (downstream svc) │
└──────────────┘      └─────────┬──────────┘      └──────────────────┘
                                │
                          ┌─────┴──────┐
                          │            │
                          ▼            ▼
                  ┌────────────┐  ┌────────────┐
                  │ Postgres   │  │ Redis      │
                  │ (audit +   │  │ (idem +    │
                  │  decisions)│  │  velocity) │
                  └────────────┘  └────────────┘

                          (optional, --profile advisory)
                          ┌──────────────────┐
                          │  Ollama (phi3:mini)│
                          └──────────────────┘
```

External actors: **Card system / merchants** submit transactions (REST `POST /api/v1/transactions` or Kafka topic `tx.events.v1`). **Ops** drive admin endpoints via API key. **Downstream services** consume `tx.decisions.v1`.

---

## 3. C4 — Container

```
┌──────────────────── fraud-rule-engine ────────────────────────┐
│                                                               │
│  ┌─────────────┐    ┌────────────────┐    ┌────────────────┐  │
│  │ Spring MVC  │    │ Spring Kafka   │    │ Actuator       │  │
│  │ (REST)      │    │ (consumer)     │    │ + Prometheus   │  │
│  └─────┬───────┘    └────────┬───────┘    └────────────────┘  │
│        │                     │                                │
│  ┌─────▼─────────────────────▼──────────────────────────────┐ │
│  │      IngestService.ingest(tx, consumerId)                │ │
│  │   - dedup pre-check (processed_events)                   │ │
│  │   - @Transactional: ingestNew                            │ │
│  │       1) processed_events.saveAndFlush                   │ │
│  │       2) transactions.save                               │ │
│  │       3) engine.evaluate                                 │ │
│  │       4) decisions.save + decision_rules.save            │ │
│  │       5) outbox.save                                     │ │
│  │       6) publish AuditEvent (async, REQUIRES_NEW)        │ │
│  └─────┬──────────────────────────────────────────┬─────────┘ │
│        │                                          │           │
│        │                                          │           │
│  ┌─────▼─────┐                              ┌─────▼────────┐  │
│  │ RuleEngine│                              │ OutboxPoller │  │
│  │ (atomic   │                              │ (SKIP LOCKED,│  │
│  │  swap)    │                              │  parallel    │  │
│  └───────────┘                              │  publish)    │  │
│                                             └─────┬────────┘  │
└─────────────────────────────────────────────────┬─┴───────────┘
                                                  │
                                                  ▼ tx.decisions.v1
```

The `IngestService` is the only place writes happen. Everything else reads.

---

## 4. Data flow — happy path (sequence)

```
Client                API           IngestService    RuleEngine    PG       Redpanda
  │                   │                   │                │           │           │
  │ POST /tx          │                   │                │           │           │
  ├──────────────────►│                   │                │           │           │
  │       (JWT)       │ Idempotency-Key?  │                │           │           │
  │                   ├─Redis lookup────►Redis             │           │           │
  │                   │ miss              │                │           │           │
  │                   │ ingest(tx, REST)  │                │           │           │
  │                   ├──────────────────►│                │           │           │
  │                   │                   │ exists?────────────────────►│           │
  │                   │                   │ no             │           │           │
  │                   │                   │ @Tx begin                  │           │
  │                   │                   │ INSERT processed_events    │           │
  │                   │                   ├──────────────────────────► │           │
  │                   │                   │ INSERT transactions        │           │
  │                   │                   ├──────────────────────────► │           │
  │                   │                   │ evaluate ────► │           │           │
  │                   │                   │ ◄──────────── Decision     │           │
  │                   │                   │ INSERT decisions+rules     │           │
  │                   │                   ├──────────────────────────► │           │
  │                   │                   │ INSERT outbox              │           │
  │                   │                   ├──────────────────────────► │           │
  │                   │                   │ publishEvent(AuditEvent)   │           │
  │                   │                   │ @Tx commit                 │           │
  │                   │ Decision          │                │           │           │
  │                   │◄──────────────────│                │           │           │
  │ 202 + body        │ store cache       │                │           │           │
  │◄──────────────────│                   │                │           │           │
  │                                                                                │
  │ (out-of-band)                                                                  │
  │                   OutboxPoller @Scheduled (500ms)                              │
  │                       SELECT … FOR UPDATE SKIP LOCKED                          │
  │                       parallel kafka send                                      │
  │                       UPDATE outbox SET processed_at = now()                   │
  │                                                              tx.decisions.v1   │
  │                                                              ─────────────────►│
```

Two failure paths preserve the invariants:

- **Crash between commit and outbox publish.** The outbox row exists; the next poller tick claims it via SKIP LOCKED.
- **Crash before commit.** Nothing persisted; the Kafka offset isn't advanced; the consumer re-delivers.

---

## 5. Rule evaluation

`RuleEngine` evaluates against an `AtomicReference<RuleSet>` snapshot. Rules sort by `priority` desc with `id` as tiebreaker (Day-2.5 fix — non-determinism was a banking-grade red flag). The condition AST is sealed: `Atomic` / `All` / `Any`. Each predicate is a Spring `@Component` keyed by `id()`; the `PredicateRegistry` fails fast at startup on duplicates.

Status escalates `APPROVE < REVIEW < BLOCK` across matched rules; score is the max. `shortCircuit: true` on a matched rule stops further evaluation — used by `BLACKLISTED_MERCHANT` (priority 1000) and `VELOCITY_BURST` (priority 900).

Hot reload via `POST /admin/rules/reload` is atomic: validate → install new ref → old ref keeps serving in-flight evaluations until GC.

See [ADR-0005](docs/adr/0005-rule-representation-yaml.md).

---

## 6. Idempotency, rate limit, audit

- **Idempotency-Key** — Redis cache keyed by `idem:{jwt_sub}:{key}`, 24 h TTL. Body is canonical-JSON SHA-256 hashed; same key + different hash → 409. Subject-bound to neutralise cross-user replay.
- **Rate limit** — Bucket4j + Redis, 100 req/min per JWT subject. `ROLE_SERVICE` exempt. Exhausted → 429 + `Retry-After`.
- **Audit** — every `DECISION_WRITE` + every `/admin/rules/reload` publishes an `AuditEvent`; listener writes `REQUIRES_NEW` async. `audit_write_failed_total` counter wired so the rare "commit succeeded, audit failed" gap is observable. See [ADR-0007](docs/adr/0007-security-jwt-api-key.md).

---

## 7. Security posture

- JWT (HS256) for clients; demo `/auth/token` mints ONLY `ROLE_USER` (closing a Day-4.5 review hole). Token validator enforces `iss` claim.
- Service API key (`X-Service-Api-Key`) for admin + Prometheus. Constant-time compare. Day-4.5 review caught `@Component`+`OncePerRequestFilter` auto-registering the filter twice — fix: explicit `FilterRegistrationBean(setEnabled=false)` so the filter only runs inside the security chain.
- POPIA — `PiiRedactor.redactAccountId()` masks `ABC-****1234` in every log path that touches `accountId` (IngestService, KafkaListener). PII never leaves the JVM in plaintext logs.
- Production migration path documented in [ADR-0007](docs/adr/0007-security-jwt-api-key.md) §"Forward path": HS256→RS256 via JWKS, API key→mTLS.

---

## 8. Reliability — SLOs

| SLI | Target | Current | How measured |
|---|---|---|---|
| Ingest p99 latency | < 200 ms | k6 load test, 200 rps / 60 s — `k6/load.js` enforces `p(99)<200` as a script-side threshold | `http_server_requests_seconds_bucket` |
| Availability | 99.9 % monthly | Modular monolith on a single VM is hardware-bounded; multi-AZ is doc-only | Synthetic probe every 60 s |
| Decision recovery | RPO ≤ 5 min | Postgres WAL + outbox + Kafka replay | `outbox_lag_seconds` < 5 |
| Cluster recovery | RTO ≤ 15 min | DR runbook ([docs/runbooks/disaster-recovery.md](docs/runbooks/disaster-recovery.md)) | DR drill once / quarter |

Resilience4j circuit breakers on `database` (sliding-window 20, fail-rate 50 %) and `advisory` (window 10, fail-rate 50 %, 30 s open). Kafka producer is idempotent; consumer dedups on `processed_events` so replay is safe.

---

## 9. AI advisory

- **Never inline.** Separate `GET /api/v1/decisions/{id}/advisory` endpoint. Core ingest never touches Ollama.
- **2 s hard timeout.** `RestClient` with `JdkClientHttpRequestFactory.setReadTimeout(2s)` (Day-5.5 fix; the original config only enforced connect timeout). `@CircuitBreaker(name="advisory")` shields against persistent failure with fallback to `UNAVAILABLE`.
- **Always human-gated.** `AdvisoryResponse.humanReviewRequired` is always `true`. Set in code, not by the model.
- **No-PII prompt contract.** [Prompt template](src/main/resources/prompts/advisory-v1.md) explicitly forbids account-id / customer-name / amount inclusion.
- **Observable hallucination.** [Eval rubric](docs/advisory-eval.md) defines 20 expert-labelled fixtures + 6 metrics with thresholds.

See [ADR-0010](docs/adr/0010-advisory-ollama.md).

---

## 10. Observability

- **Logs.** JSON-structured with `correlationId` in MDC; `PiiRedactor` masks account ids.
- **Metrics.** Domain (`fraud_decisions_total{status}`, `rule_eval_duration_seconds` p50/p95/p99 histogram, `idempotency_cache_size`, `rate_limit_exceeded_total`, `outbox_lag_seconds`, `audit_write_failed_total`, `advisory_response_total{status}`) + actuator + Resilience4j.
- **Tracing.** Correlation-Id propagated as `X-Correlation-Id` request + response header. OpenTelemetry OTLP export is one config line away (`management.tracing.sampling.probability` + `management.otlp.tracing.endpoint`) — not wired in compose to keep the stack minimal.

---

## 11. What's intentionally NOT here

| Concern | Why deferred | Where documented |
|---|---|---|
| Multi-region active/active | Out of single-instance MVP scope | [ADR-0006](docs/adr/0006-reliability-resilience.md) §"Forward path" |
| mTLS service-to-service | Production migration step | [ADR-0007](docs/adr/0007-security-jwt-api-key.md) §"Forward path" |
| Kafka Streams windowed aggregations | Redis ZSET is sufficient for current rule set | [ADR-0006](docs/adr/0006-reliability-resilience.md) |
| Customer-facing UI / dashboard | Plan §14 — Grafana JSON is checked in, dashboards are optional `--profile observability` | [docs/observability/](docs/) |
| External IdP / OAuth2 Authorization Server | Demo path uses HS256; production uses Keycloak / Azure AD | [ADR-0007](docs/adr/0007-security-jwt-api-key.md) |

---

## 12. Glossary

- **Decision** — `(decisionId, txId, status, score, ruleSetVersion, matchedRules[], evaluatedAt)`. Persisted in `decisions` + `decision_rules`. The reviewer's permanent record of *why* a transaction was flagged.
- **RuleSet** — versioned, immutable, sorted-by-priority list of `Rule`s loaded from `rules/rule-set-vN.yml`.
- **Outbox** — durable buffer between the transactional decision write and the Kafka publish.
- **Advisory** — non-authoritative LLM commentary. Never affects a decision.
