# Runbook — Deploy rollback

**Severity:** P1 during outage, P2 for failed canary.

## Triggers for rollback

- New tag pushed; `/actuator/health/readiness` stays 503 after 60 s.
- Post-deploy error rate > 1 % for 5 min.
- New rule version flagging > 10× baseline (cross-check with [rule-misfire.md](rule-misfire.md)).
- A migration ran but the application can't start due to entity / schema mismatch.

## Rollback steps

### Application (Docker / compose)

```bash
# 1. Identify the last green tag
git tag --sort=-version:refname | grep checkpoint | head -5

# 2. Check out the target
git checkout day-X.Y-checkpoint

# 3. Rebuild + restart
docker compose up -d --build api

# 4. Wait for readiness
until curl -s http://localhost:8090/actuator/health/readiness | grep -q UP; do sleep 2; done

# 5. Verify the running version
curl -s http://localhost:8090/actuator/info
```

### Rule set

The rule reload endpoint is reversible — the old ruleset is gc'd only after a successful swap. Reload the prior YAML:

```bash
git show day-X.Y-checkpoint:src/main/resources/rules/rule-set-v1.yml \
  > src/main/resources/rules/rule-set-v1.yml
docker compose up -d --build api
# OR if no class changes: just hot-reload
curl -s -X POST http://localhost:8090/admin/rules/reload -H "X-Service-Api-Key: $API_KEY"
```

### Database migration

Flyway is the source of truth. If the new version introduced a migration that breaks the old app:

1. **Forward-only migrations.** Roll back code only; the schema stays.
2. **Destructive change (DROP COLUMN).** Restore from WAL — see [disaster-recovery.md](disaster-recovery.md). The "expand-then-contract" pattern keeps us out of this scenario; if you hit it, the migration broke the rule.

## Communication

- Status page: "Investigating elevated errors on the fraud engine".
- Slack #fraud-platform: rollback target tag + ETA.
- Post-incident: schedule retro within 5 working days.

## Verification

```bash
# Run the post-rollback smoke pack
./scripts/smoke.sh  # Day-7 deliverable; for now manual via README curl block
```
