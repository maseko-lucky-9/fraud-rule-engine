# Runbook — Postgres down

**Severity:** P1 · **SLO impact:** ingest unavailable, outbox stalls, audit gap (Day-7 polish: durable retry queue closes this).

## Symptoms

- `/actuator/health/readiness` returns 503; `db` component DOWN.
- 500 responses on `POST /api/v1/transactions` with `type=https://fraud-engine.example/problems/internal`.
- Resilience4j `database` circuit breaker transitions OPEN → metric `resilience4j_circuitbreaker_state{name=database}` = 2.
- `outbox_lag_seconds` rising.

## Immediate response (≤ 5 min)

1. **Confirm** — `docker compose ps postgres` shows `unhealthy` or exited.
2. **Check disk** — `docker compose exec postgres df -h /var/lib/postgresql/data`. PostgreSQL refuses writes when the data volume is full.
3. **Tail logs** — `docker compose logs --tail=200 postgres`. Look for `FATAL`, `out of disk`, `incompatible WAL`.
4. **Communicate** — page on-call (`POST` to PagerDuty / Slack), set status page.

## Recovery

### Disk-full
```bash
docker compose exec postgres bash -c "du -sh /var/lib/postgresql/data/*"
# remove obsolete archive segments (NEVER touch pg_wal directly)
```
If volume is host-mounted, prune host-side: `docker system df`, then `docker volume prune` (careful — only for non-data volumes).

### Container crashed
```bash
docker compose restart postgres
docker compose exec postgres pg_isready -U "$POSTGRES_USER"
```

### Data corruption
1. Stop API to prevent further writes: `docker compose stop api`.
2. Restore from the most recent WAL archive — see [disaster-recovery.md](disaster-recovery.md).
3. Replay the outbox after restore (rows with `processed_at IS NULL` will drain on next poller tick).

## Post-incident

- Confirm `outbox` drains to zero pending: `SELECT count(*) FROM outbox WHERE processed_at IS NULL`.
- Audit-gap check — count `DECISION_WRITE` rows missing audit entries during outage window (Day-7 polish).
- Update [postmortem.md](https://github.com/maseko-lucky-9/fraud-rule-engine/wiki/postmortems) with root cause + action items.

## Verification (drill, monthly)

```bash
docker compose pause postgres
sleep 30
curl -s http://localhost:8090/actuator/health/readiness   # 503
docker compose unpause postgres
sleep 30
curl -s http://localhost:8090/actuator/health/readiness   # 200
```
