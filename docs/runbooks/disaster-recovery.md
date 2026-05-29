# Runbook — Disaster Recovery (RPO 5 min · RTO 15 min)

**Severity:** P1 — full-region outage / data loss. ADR-0006 commits to RPO ≤ 5 min, RTO ≤ 15 min.

This runbook is the **paper plan**. Real implementation (continuous WAL archive to off-site object storage, Redpanda topic replication, automated failover) is documented as Day-7+ work.

## Failure domains

| Component | RPO mechanism | RTO mechanism |
|---|---|---|
| Postgres | WAL archive every 5 min to S3-compatible store | Standby restored from WAL + base backup |
| Redpanda topics | Topic replication factor = 3 in production; demo runs single-node | Re-bootstrap from replica; replay missing from outbox |
| Redis (idempotency + velocity) | None — cache only | Cold start; idempotent inserts safe via `processed_events` |
| Rule YAML | git is source of truth | Re-pull, rebuild, reload |

## Procedure

### 1. Declare incident (T+0)
Page on-call. Customer-facing status page → "Major outage on fraud engine".

### 2. Stand up new infra (T+5)
- Provision new VM / Kubernetes namespace.
- `git clone` + `docker compose up -d` against new Postgres + Redpanda + Redis.

### 3. Restore Postgres (T+10)
```bash
# Stop the new api container so it can't write yet
docker compose stop api

# Pull the latest base backup + WAL segments from S3
aws s3 sync s3://fraud-engine-wal/<env>/ /var/lib/postgresql/restore/
# Configure recovery target
echo "restore_command = 'cp /restore/%f %p'" >> postgresql.conf
echo "recovery_target_time = '<T-1 minute>'" >> postgresql.conf

# Start postgres in recovery, wait for "consistent recovery state reached"
docker compose up -d postgres
docker compose logs postgres | grep "consistent recovery state"
```

### 4. Recover Redpanda topics (T+12)
```bash
# Re-bootstrap on the new VM (single-node demo)
docker compose up -d redpanda

# In production: configure with topic mirroring from a replica cluster.
# For the demo: replay missing decisions from outbox once api is up.
```

### 5. Bring API up + drain outbox (T+13)
```bash
docker compose up -d api
# Outbox poller will pick up any rows where processed_at IS NULL — incl. anything
# that committed in postgres but never made it onto the wire pre-disaster.
```

### 6. Validate (T+14)
```bash
# Decision count vs. expected baseline
psql -c "SELECT count(*), max(evaluated_at) FROM decisions WHERE evaluated_at > now() - interval '1 hour'"

# Outbox lag
psql -c "SELECT count(*) FROM outbox WHERE processed_at IS NULL"

# Audit continuity
psql -c "SELECT max(occurred_at) FROM audit_log"
```

### 7. Reopen traffic (T+15)
- DNS cutover / load-balancer flip to new endpoint.
- Watch error rate + p99 for 30 min before declaring resolved.

## RPO/RTO calculation

- **RPO 5 min** — WAL archive cadence + Kafka replication lag (the longer of the two). Anything written in the final 5 min before failure is lost unless it sat in the outbox AND the outbox was already replicated.
- **RTO 15 min** — bounded by: provisioning (5) + restore (5) + drain + validate (5).

## DR drill cadence

- **Quarterly.** Pause production traffic to a canary instance; restore from off-site backup; measure RTO.
- **Post-drill:** record RPO/RTO actuals + any gaps in [postmortem](https://github.com/maseko-lucky-9/fraud-rule-engine/wiki/postmortems).

## Open work (Day 7+)

- Automate WAL archive (currently manual procedure).
- Wire S3 credentials into compose for self-contained demo.
- Add `dr-drill.sh` script that simulates region loss in CI.
