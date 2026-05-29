# Runbook — Kafka (Redpanda) consumer lag

**Severity:** P2 · **SLO impact:** decision events not flowing to downstream consumers; outbox grows.

## Symptoms

- `outbox_lag_seconds` > 5 s sustained.
- Downstream consumers of `tx.decisions.v1` falling behind.
- Redpanda `under_replicated_partitions` > 0 (multi-broker only).

## Immediate response

1. **Verify lag** —
   ```bash
   docker compose exec redpanda rpk topic describe tx.decisions.v1
   docker compose exec redpanda rpk group describe fraud-engine
   ```
2. **Outbox health** —
   ```sql
   SELECT count(*), max(now() - created_at) AS oldest
   FROM outbox WHERE processed_at IS NULL;
   ```
3. **Poller alive?** — `docker logs fraud-api 2>&1 | grep -i "OutboxPoller"`. Look for stalled `drain()` calls.
4. **Producer health** — check `kafka_producer_record_error_total` metric.

## Recovery

### Poller stuck on `SELECT FOR UPDATE`
A long-running transaction blocking the SKIP-LOCKED poll. Identify + kill the offender:
```sql
SELECT pid, now() - xact_start AS duration, query
FROM pg_stat_activity
WHERE state = 'active' AND query LIKE '%outbox%'
ORDER BY xact_start;
SELECT pg_cancel_backend(<pid>);
```

### Producer can't reach broker
- `docker compose ps redpanda` — confirm healthy.
- Network: `docker compose exec api ping -c 1 redpanda`.
- Listener mis-advertised: check `--advertise-kafka-addr` in compose; restart redpanda.

### Backlog draining slowly
Bump poller batch size + parallelism via env:
```bash
OUTBOX_BATCH=500 OUTBOX_PUBLISH_TIMEOUT_MS=2000 docker compose up -d api
```

## Post-incident

- Set `outbox_lag_seconds > 30 for 5 min` as the alert threshold.
- If lag was downstream-driven (a sluggish consumer), partition `tx.decisions.v1` and scale that consumer.

## Verification

```bash
docker compose pause redpanda
# produce some traffic via REST — outbox should grow
sleep 30
docker compose unpause redpanda
# poller drains; outbox_lag_seconds returns to near-zero
```
