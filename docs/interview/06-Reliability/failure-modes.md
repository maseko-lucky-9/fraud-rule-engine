# Failure modes & responses

A "what happens when X breaks" cheat sheet. Each row references the runbook with detailed recovery.

| # | Failure | Detection | Customer impact | Internal behaviour | Runbook |
|---|---|---|---|---|---|
| 1 | Postgres down | `/health/readiness` 503; `db` component DOWN; circuit breaker OPEN | Ingest 503s. Idempotency cache still works for read; DB writes fail | Resilience4j shorts the path; outbox poller halts; Kafka consumer redelivers on restart | [db-down](../../runbooks/db-down.md) |
| 2 | Redis down | `health/readiness` 503 if Redis is in the readiness group | Idempotency cache misses; rate limit fails open (no buckets) | Predicates that depend on `StateStore` fall back to "no match" | (Day-7 polish: dedicated runbook) |
| 3 | Redpanda down | `tx.events.v1` consumer offline; outbox lag rises | REST ingest still works (sync path); Kafka ingest pauses | Consumer reconnects on restart; outbox poller backs off + retries | [kafka-lag](../../runbooks/kafka-lag.md) |
| 4 | Bad rule YAML | 422 from `/admin/rules/reload`; `rules_reload_failed_total` increments | None — old ruleset stays active | Operator fixes YAML and retries | [rule-misfire](../../runbooks/rule-misfire.md) |
| 5 | Rule misfire (false positive spike) | `fraud_decisions_total{status="BLOCK"}` unusually high | Customers wrongly declined | Hotfix via YAML edit + reload OR rollback to previous version | [rule-misfire](../../runbooks/rule-misfire.md) |
| 6 | Rule misfire (false negative) | Reviewer report; downstream fraud incidence | Fraud slips through | Same — YAML hotfix; consider adding a new predicate | [rule-misfire](../../runbooks/rule-misfire.md) |
| 7 | Bad request payload spike | `http_server_requests_seconds_count{status="400"}` spikes | The misbehaving client gets 400s; rest unaffected | Identify caller via correlation-Id; notify integration team | [bad-payload](../../runbooks/bad-payload.md) |
| 8 | Deploy regression | `health/readiness` won't come up post-deploy; or error rate spike post-deploy | Brief outage during rollback | Roll back to last green tag | [deploy-rollback](../../runbooks/deploy-rollback.md) |
| 9 | Audit-write failure | `audit_write_failed_total > 0`; ERROR logs | None to customer; audit gap | Day 5: counter + ERROR log. Day-7: durable retry queue | (Day-7 polish) |
| 10 | Ollama timeout | `advisory_response_total{status="TIMED_OUT"}` rises | None — advisory is non-blocking | Circuit breaker opens after sustained failure → all responses become `UNAVAILABLE` until breaker half-opens | [advisory-eval.md](../../advisory-eval.md) |
| 11 | Ollama returns malformed JSON | `advisory_response_total{status="MALFORMED"}` rises | None | Investigate prompt drift or Ollama version change | [advisory-eval.md](../../advisory-eval.md) |
| 12 | Region loss | Multi-region health checks; downstream alerts | Full outage in affected region | Failover per DR runbook | [disaster-recovery](../../runbooks/disaster-recovery.md) |
| 13 | Outbox poller stuck | `outbox_lag_seconds > 30` for 5 min | Decision events delayed downstream | Identify long-running tx blocking SKIP-LOCKED rows; cancel + restart poller | [kafka-lag](../../runbooks/kafka-lag.md) |
| 14 | JWT secret rotation gone wrong | Mass 401s | All authenticated calls fail until secret matches | Roll back secret; restart api; document the rotation procedure (Day 7+) | (Day-7 polish: secret-rotation runbook) |
| 15 | Hot-reload race | `HotReloadRaceTest` would catch in CI; live symptom = decisions with mixed `ruleSetVersion` in a batch | None visible — atomic swap | N/A — invariant proven by test | N/A |
| 16 | Out-of-memory in api | `jvm_memory_used_bytes` near max, container OOMKilled | Brief outage during restart | Restart picks up where it left off thanks to outbox + processed_events; bump JVM heap | (Day-7 polish) |
| 17 | Disk full | Postgres refuses writes; logs flooded | Same as #1 | Identify culprit volume; prune | [db-down](../../runbooks/db-down.md) |
| 18 | Network partition between api and Redis | Velocity predicate falls back to "no match" (fail-open); rate limit potentially fails | Some false negatives on velocity rule | Network heal; predicate behavior recovers automatically | (Day-7 polish) |
| 19 | Replica double-publish to `tx.decisions.v1` | Downstream sees duplicates | Bounded to single events; downstream consumer MUST dedup on `decisionId` per documented contract | SKIP-LOCKED outbox prevents intra-cluster duplication; idempotent Kafka producer prevents in-transit duplication; cross-broker partition rebalance is the residual case | N/A — published contract |
| 20 | Token forgery attempt | Spring Security 401 log line + correlation ID; counter `audit_write_failed_total` is unrelated, so a dedicated `jwt_validation_failed_total` is Day-7 polish (Spring's `org.springframework.security` MetricsFilterAutoConfiguration emits HTTP-level only today) | None — request 401s | Investigate source IP / token claims via correlation-ID grep | (Day-7 polish: WAF rules + dedicated counter) |

## Forward path

- **Wire #2 (Redis), #14 (secret rotation), #16 (OOM), #18 (network partition) to dedicated runbooks.** Day 7 polish.
- **Add chaos drills.** Toxiproxy or `docker pause` for each external — quarterly cadence aligned with DR drill.
- **Auto-rollback on health-check failure.** GitHub Actions workflow that re-tags `previous-stable` if `/actuator/health/readiness` fails for 60s post-deploy.
