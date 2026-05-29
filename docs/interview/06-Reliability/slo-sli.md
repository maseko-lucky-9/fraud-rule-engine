# SLOs / SLIs

| Service Level Indicator | Target | Error budget / consequence | How measured |
|---|---|---|---|
| **Ingest p99 latency** | < 200 ms | > 200 ms for 5 min → page on-call | `http_server_requests_seconds_bucket{uri="/api/v1/transactions",status="202"}` |
| **Ingest success rate** | ≥ 99.9 % | < 99.5 % over 10 min → page | `http_server_requests_total` 2xx / total |
| **Rule eval latency p99** | < 50 ms | > 50 ms for 5 min → page | `rule_eval_duration_seconds` histogram |
| **Decision write availability** | ≥ 99.9 % monthly | Burned-budget alerts at 25/50/75 % | Synthetic POST every 30s + actual error rate |
| **Decision delivery (outbox → Kafka)** | RPO ≤ 5 min | `outbox_lag_seconds > 30` for 5 min → page | `outbox_lag_seconds` gauge |
| **Audit-row coverage** | 100 % of decisions audited | Any `audit_write_failed_total > 0` for 1 hr → page | Counter |
| **Rule reload safety** | 100 % old-set-preserved-on-failure | Any failed reload that bricks decisioning → SEV1 | `rules_reload_failed_total` + alert on next decision error spike |
| **DR (region loss)** | RPO ≤ 5 min, RTO ≤ 15 min | Quarterly drill must meet target | DR runbook + drill log |
| **Advisory availability** | Best-effort (no SLO) | N/A — non-blocking | `advisory_response_total{status}` counters |

## Why these and not others

**Why no SLO on Kafka consumer lag for `tx.events.v1`?**
The Kafka path is one of two ingest channels. The SLO on ingest latency + error rate covers it from the customer's perspective. If `tx.events.v1` consumer lags, that's an internal queue depth problem, not a customer-facing SLO miss.

**Why no SLO on advisory?**
By design — the advisory is non-blocking and never authoritative. Slow Ollama doesn't impact ingest. We do alert on `advisory_response_total{status="MALFORMED"}` because a sudden spike indicates either Ollama is broken or the prompt template needs an update.

**Why error budgets instead of strict SLAs?**
The submission targets internal SLOs, not customer-facing SLAs (those would include contractual penalties + multi-region active-active). The error-budget posture frames missed SLOs as a signal to slow feature development and pay down reliability debt — which is the right relationship between dev and ops in a bank.

## Burn-rate alerts

Per Google SRE workbook: alert on **page-rate** (1-hour budget burned in 5 min) and **ticket-rate** (5 % budget burned in 6 hr).

For 99.9 % availability (43.2 min/month budget):
- **Page:** error rate > 14.4 % for 5 min.
- **Ticket:** error rate > 0.5 % for 6 h.

Wired via Prometheus alerting rules — Day 7 polish.

## Recovery point + time objectives

| Failure | RPO | RTO | Source |
|---|---|---|---|
| Postgres primary loss | 5 min (WAL archive cadence) | 15 min (DR procedure) | [runbooks/disaster-recovery.md](../../runbooks/disaster-recovery.md) |
| Single API instance crash | 0 (idempotent + outbox) | < 30 s (k8s/compose restart) | OS-level restart |
| Redpanda single-node loss (demo) | All in-flight events between commit + publish; outbox replays | Restart broker; replay outbox | `runbooks/kafka-lag.md` |
| Region loss | 5 min | 15 min | DR runbook |

The 5-min RPO is bounded by **the longer** of: WAL archive lag, Kafka replication lag. Production tunes both to ≤ 1 min.
