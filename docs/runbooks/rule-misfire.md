# Runbook — Rule mis-fire

**Severity:** P1 if false negatives slip a known fraud pattern · P2 if false positives ingest a customer-impact spike.

## Symptoms

- Sudden spike in `fraud_decisions_total{status="BLOCK"}` without a corresponding fraud incident → false-positive.
- Reviewer reports a transaction was approved that obviously should not have been → false-negative.
- `rules_reload_failed_total` non-zero → the operator tried to push a fix and it was rejected.

## Immediate response

1. **Snapshot the active rule set version** —
   ```bash
   curl -s -H "X-Service-Api-Key: $API_KEY" \
     http://localhost:8090/admin/audit?size=1 | jq '.items[0]'
   # payloadHash + ruleSetVersion in the decision give you the exact version that fired
   ```
2. **Identify the rule** — query a recent affected decision:
   ```bash
   curl -s -H "Authorization: Bearer $TOKEN" \
     http://localhost:8090/api/v1/decisions/<id> | jq '.matchedRules'
   ```
3. **Decide: hotfix or rollback** — see below.

## Recovery — hotfix

1. Edit `src/main/resources/rules/rule-set-v1.yml`. Bump `version`.
2. `POST /admin/rules/reload` — picks up changes atomically. In-flight evaluations finish on the OLD ruleset.
3. Confirm via the next `DECISION_WRITE` carrying the new `ruleSetVersion`.

## Recovery — rollback to prior version

The previous YAML is in git history. The reload endpoint reads from the classpath, so for a hot rollback:
1. Replace `rules/rule-set-v1.yml` content with the prior good version (cherry-pick from git).
2. `POST /admin/rules/reload`.
3. Commit the rollback so the running state matches HEAD.

## Recovery — reload rejected (422)

```bash
curl -s -X POST http://localhost:8090/admin/rules/reload -H "X-Service-Api-Key: $API_KEY"
# {"type":".../rule-validation-failed", "errors":["..."]}
```

The response carries field-level diagnostics. Fix the YAML, retry. The OLD ruleset keeps serving — never a service outage.

## Prevention

- **Golden cases** — every shipped rule has ≥ 2 fixtures in `test/resources/fixtures/golden-cases.json`. Adding a rule without a fixture is a CI failure.
- **Mutation testing (Pitest)** — run `./mvnw org.pitest:pitest-maven:mutationCoverage` to surface mutations that don't get caught by the existing tests.
- **Pre-prod canary** — Day-7 polish work: dual-write decisions to a shadow ruleset and compare before promoting.

## Verification

Any reload failure must result in:
- 422 response,
- Old ruleset still active (`/admin/rules/reload` re-issue with the BAD YAML returns 422, then with the GOOD YAML returns 200 — verify the active version increases),
- `rules_reload_failed_total` incremented.
