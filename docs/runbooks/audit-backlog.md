# Runbook: audit_pending backlog

> Symptom: `audit_pending_size` gauge is rising or
> `audit_pending_promoted_total` rate is below the
> `audit_write_pending_persisted_total` rate.

## What it means

Live-path audit writes are failing and the rows are queueing into
`audit_pending`. The retry poller drains them back into `audit_log`
under `ON CONFLICT (resource_id, payload_hash) DO NOTHING`, but if the
queue grows faster than it drains, there is an underlying issue with
either the database or the retry poller.

## Severity

* `audit_pending_size > 100` for 5m → **page** (covered by
  `AuditPendingGrowing` alert in `prometheus/alerts.yml`).
* `audit_pending_give_up_total` rate > 0 → **page** — rows abandoned
  after `app.audit.retry-max-attempts` is an actual audit-loss event.

## First-look checks

1. Is the database reachable?
   ```
   docker compose exec postgres pg_isready -U fraud -d fraud_engine
   ```
2. Is the retry poller still ticking? Filter logs for
   `AuditRetryPoller` lines on the affected replica. A silent poller
   suggests bulkhead rejection (we should see
   "audit-retry: drain returned exceptionally" log lines) or a stuck
   scheduler thread.
3. What is the live-path failure mode? Look at
   `audit_write_failed_total` and the most recent error log line —
   the `failure` column on the pending row carries the exception text.

## Common causes

| Symptom | Likely cause | Mitigation |
|---|---|---|
| `audit_pending_size` rises in lockstep with `audit_write_failed_total` | live save is failing on something deterministic (e.g. unique-constraint regression, schema drift after a partial migration) | Inspect the `failure` column on a sample pending row; fix the underlying error and re-deploy. The retry poller will drain the backlog automatically. |
| `audit_pending_size` rises but `audit_write_failed_total` does NOT | Burst-induced `CallerRunsPolicy` saturating the calling thread. Audit writes succeed eventually but the live path runs into the saturating audit executor | Add replicas, or raise `auditExecutor.queueCapacity` in `AsyncConfig.java`. |
| `audit_pending_promoted_total` is 0 over a long window | Retry poller is jammed | Restart the affected replica. If multi-replica, check whether ALL replicas show the symptom (suggests a DB lock on the `audit_pending` table). |
| `audit_pending_give_up_total > 0` | Rows are exhausting `app.audit.retry-max-attempts` (default 20). The error is persistent, not transient | Investigate the most recent give-up log line. The row is permanently lost from `audit_log`; manual reconciliation from the live-path log files is the only recovery. |

## Manual drain

```sql
-- Inspect the oldest pending row and its failure
SELECT id, actor, action, resource_id, retry_count, failure, created_at
FROM audit_pending
ORDER BY created_at ASC
LIMIT 5;

-- Manually promote a specific row (idempotent)
INSERT INTO audit_log (actor, action, resource_id, payload_hash, occurred_at)
SELECT actor, action, resource_id, payload_hash, created_at
FROM audit_pending
WHERE id = '<pending-row-id>'
ON CONFLICT (resource_id, payload_hash) DO NOTHING;

-- After confirming the row is in audit_log, delete the pending row
DELETE FROM audit_pending WHERE id = '<pending-row-id>';
```

## Long-term

Persistent `audit_pending` growth points at an under-sized audit
executor or a chronically-slow DB. Capacity-plan the executor against
the steady-state ingest rate × audit-write latency. If executor
saturation is the root cause, raise `corePoolSize` / `maxPoolSize` /
`queueCapacity` in `AsyncConfig.auditExecutor()` and re-deploy.
