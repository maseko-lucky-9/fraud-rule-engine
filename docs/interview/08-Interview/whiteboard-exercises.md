# 3 whiteboard exercises (with answers I'd actually give)

These are the prompts a senior panel typically opens with. Each answer is what I'd whiteboard live — terse, no buzzwords, with the trade-off named explicitly.

---

## Exercise 1 — "Sketch a fraud-detection system that handles 10k tps and survives a region outage"

**My whiteboard (5 minutes):**

```
       cards ──► API LB (multi-region, anycast)
                   │
                   ├──► Region A: API replicas (HPA)
                   │      │
                   │      ├──► Kafka cluster (3 brokers, RF=3, IDM acks=all)
                   │      ├──► Postgres primary + replica (WAL → S3 archive every 1 min)
                   │      └──► Redis cluster (3 master, 3 replica)
                   │
                   └──► Region B: same shape, hot standby
                          (postgres replica trails primary via WAL replay)

Failure scenarios:
   Region A loses postgres   → API circuit-breaks DB; ingest 503s.
                                Cards retry against API LB which routes B.
                                B postgres promoted; WAL replay finishes; ingest resumes.
                                RPO bounded by WAL lag (~30s)
                                RTO bounded by promote + DNS TTL (~5min)
```

**Trade-offs I'd name:**
- Active-passive vs active-active. Active-active needs eventual-consistency tolerance in the rule state (velocity ZSETs). I'd argue active-passive is correct for fraud — losing 30s of velocity history is fine, splitting decisions across regions is not.
- Synchronous vs async replication. Sync gives RPO ≈ 0 but cross-region latency tax on every commit. Async + WAL archive is the standard compromise.
- The rule engine is stateless (rules live in YAML + git); replicas trivially.

**Question I'd ask back:** what's the SLO for false-negative discovery latency? That changes how aggressive the replay-on-failover needs to be.

---

## Exercise 2 — "A customer says their transaction was wrongly declined 3 months ago. How do you reproduce it?"

**My whiteboard:**

```
1. Find the decision
   SELECT decisionId, rule_set_version, evaluated_at
   FROM decisions
   WHERE tx_id IN (
     SELECT tx_id FROM transactions
     WHERE account_id = '<id>' AND received_at BETWEEN ? AND ?
   )

2. Pull the matched-rule trace
   SELECT rule_id, reason, matched_priority
   FROM decision_rules WHERE decision_id = ?

3. Find the rule definition for THAT version
   - rule_versions table → yaml_hash for version N
   - git log src/main/resources/rules/rule-set-v1.yml → commit matching that hash
   - git show <commit>:src/main/resources/rules/rule-set-v1.yml

4. Reproduce locally
   - Load that exact YAML into a fresh engine
   - Replay transactions.payload (we kept the full event)
   - Assert decision matches what was persisted
```

**Why this works:**
- Every decision row carries `rule_set_version` (atomicity invariant — Day 2).
- Every rule version's YAML hash is in `rule_versions` (audit trail).
- The raw event lives in `transactions.payload` (JSONB), not just the typed columns.
- ArchUnit ensures `domain` has no framework deps, so the replay can be done in a unit test without spinning up the full stack.

**Trade-off I'd name:** keeping the raw payload doubles storage on `transactions`. I'd partition the table monthly and detach + archive >90-day partitions to cheaper storage. Documented in [V1__init.sql](../../../src/main/resources/db/migration/V1__init.sql).

---

## Exercise 3 — "Add a new fraud rule: block transactions from customers in two specific MCC categories during a fraud incident window."

**My whiteboard:**

```yaml
# rule-set-v2.yml — bumped version
ruleSet:
  id: default
  version: 2
  rules:
    - id: INCIDENT_MCC_BLOCK
      priority: 1100      # Above BLACKLISTED_MERCHANT so it short-circuits first
      severity: CRITICAL
      shortCircuit: true
      condition:
        all:
          - { predicate: timeOfDay
              args: { start: "13:00", end: "16:00", zone: "Africa/Johannesburg" } }
          - { predicate: mccIn
              args: { in: ["5411", "5912"] } }
      action:
        flag: BLOCK
        score: 0.99
        reason: "Active fraud incident — MCC group temporarily blocked."
```

**Steps:**

1. Add the predicate (no existing `mccIn` — write it):
   ```java
   @Component
   class MccInPredicate implements Predicate {
     public String id() { return "mccIn"; }
     public boolean test(PredicateContext ctx, Map<String,Object> args) {
       List<?> in = PredicateArgs.requireList(args, "in");
       return in.stream().map(String::valueOf).anyMatch(ctx.transaction().mcc()::equals);
     }
   }
   ```
2. Add 2 golden-case fixtures (match + no-match) to `golden-cases.json`.
3. Bump rule-set version, push.
4. Hot-reload: `curl -X POST /admin/rules/reload`. New decisions carry `ruleSetVersion: 2`. Old ruleset gc'd after the swap. Zero downtime.

**Roll-back:**
Revert the YAML to the prior commit, hot-reload again. Or use the audit trail to find the exact decisions during the incident and reverse-charge if needed (out of scope — that's a separate ledger system).

**What I'd not do:**
- Don't add the MCC list to the predicate hard-coded. Always config-driven.
- Don't skip the golden cases. Every shipped rule has ≥ 2 fixtures in CI.
- Don't deploy a new container for a rule change. The hot-reload endpoint is the difference between "30 minute change" and "30 second change".
