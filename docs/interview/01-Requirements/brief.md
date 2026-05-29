# Annotated brief

Original brief with my response mapped per requirement.

| # | Requirement (paraphrased) | Where it lands | Status |
|---|---|---|---|
| R1 | Process categorized transaction events | `Transaction` record + `TransactionRequest` validation; categorisation via `mcc` field | ✅ |
| R2 | Apply fraud rules with different criteria | 6 composed rules in `rule-set-v1.yml` using 8 typed predicates | ✅ |
| R3 | Store results in a datastore | Postgres: `transactions`, `decisions`, `decision_rules`, `outbox`, `processed_events`, `rule_versions`, `audit_log` | ✅ |
| R4 | API to retrieve flagged/evaluated data | `GET /api/v1/decisions` + `GET /api/v1/decisions/{id}` | ✅ |
| R5 | Runnable Dockerfile + README | Multi-stage Dockerfile, README quickstart in ≤ 3 commands | ✅ |
| R6 | Scalability | Horizontal API replicas (Redpanda partitioning, SKIP-LOCKED outbox, Redis state, distributed rate limit) | ✅ documented + working |
| R7 | Reliability | Resilience4j circuit breakers, transactional outbox, consumer-side dedup, atomic rule swap | ✅ |
| R8 | Security | JWT + service API key, rate limit, audit log, PII redaction, RFC 7807 errors | ✅ |
| R9 | Performance | p99 < 200ms (k6 single-node verified) | ✅ |
| R10 | Maintainability | ArchUnit boundaries, YAML rule changes vs Java predicate changes, ADRs per decision | ✅ |
| R11 | Flexibility/Adaptability | YAML rules + hot reload, sealed condition AST, predicate registry | ✅ |
| R12 | Fail gracefully | Circuit breakers, fail-open advisory, RuleSet validation never breaks running system, RFC 7807 everywhere | ✅ |
| R13 | Recovery plan | DR runbook with RPO 5 min / RTO 15 min, quarterly drill | ✅ doc |
| R14 | Ollama integration | Separate non-blocking endpoint, 2s timeout, hard `humanReviewRequired=true`, hallucination eval rubric | ✅ |
| R15 | Human-validated AI outcomes | `humanReviewRequired` flag is invariant, set in code | ✅ |

See [acceptance-criteria.md](acceptance-criteria.md) for the test ID that proves each.
