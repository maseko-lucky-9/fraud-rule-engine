# Demo script — exact commands + expected outputs

Use this as a teleprompter. Every command has the expected output documented so a misalignment is immediately visible.

## Setup (run BEFORE the interview)

```bash
# 1. Make sure the stack is healthy
cd ~/Repo/experiments/fraud-rule-engine
docker compose ps   # all 4 healthy
curl -s http://localhost:8090/actuator/health   # {"status":"UP",...}

# 2. Issue a fresh token so the demo doesn't fail on expiry
export TOKEN=$(curl -s -X POST http://localhost:8090/auth/token \
  -H 'Content-Type: application/json' -d '{"subject":"alice"}' | jq -r .accessToken)
export API_KEY='dev-only-api-key-change-in-prod-min-16-chars-required'

# 3. Pre-write the demo transaction JSON to a file (faster to read aloud)
cat > /tmp/demo-tx.json <<'JSON'
{"txId":"DEMO0001-0000-0000-0000-000000000001","accountId":"ACC-NEW-12","amount":12500,"currency":"ZAR","mcc":"5411","channel":"WEB","country":"ZA","ipCountry":"ZA","deviceId":"d1","accountAgeDays":12,"timestamp":"2026-05-19T12:00:00Z"}
JSON
```

## Cue cards

### Card 1 — "Show me a transaction being evaluated"

```bash
curl -s -X POST http://localhost:8090/api/v1/transactions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-$(date +%s)" \
  -H "Content-Type: application/json" \
  -d @/tmp/demo-tx.json | jq
```

**Expected (sample, decisionId differs each run):**
```json
{
  "decisionId": "abcdef12-...",
  "txId": "DEMO0001-0000-0000-0000-000000000001",
  "status": "REVIEW",
  "score": 0.85,
  "ruleSetVersion": 1,
  "matchedRules": [{
    "ruleId": "HIGH_AMOUNT_NEW_ACCOUNT",
    "priority": 800,
    "reason": "High amount on a young account."
  }],
  "evaluatedAt": "2026-..."
}
```

**Talk point:** "Two rules' worth of composition, deterministic, fully explainable."

---

### Card 2 — "Same key + same body returns cached response"

```bash
KEY="demo-fixed-$(date +%s)"
curl -s -X POST http://localhost:8090/api/v1/transactions \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" \
  -H "Content-Type: application/json" -d @/tmp/demo-tx.json | jq -r .decisionId
# Save as ID1.

curl -s -X POST http://localhost:8090/api/v1/transactions \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" \
  -H "Content-Type: application/json" -d @/tmp/demo-tx.json | jq -r .decisionId
# Save as ID2. ID1 == ID2.
```

**Talk point:** "Subject-bound cache, SHA-256 of canonical body. Whitespace differences in the client request still match."

---

### Card 3 — "Same key + different body → 409"

```bash
echo '{"txId":"DEMO0002-0000-0000-0000-000000000002","accountId":"ACC-X","amount":777,"currency":"ZAR","mcc":"5411","channel":"WEB","country":"ZA","ipCountry":"ZA","accountAgeDays":12,"timestamp":"2026-05-19T12:00:00Z"}' > /tmp/different-tx.json

curl -s -X POST http://localhost:8090/api/v1/transactions \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" \
  -H "Content-Type: application/json" -d @/tmp/different-tx.json
# 409 Conflict, RFC 7807 problem-details body
```

**Talk point:** "Replay-attack resistant. Client gets a precise error, not silent success."

---

### Card 4 — "Hot reload, atomic swap"

```bash
curl -s -X POST http://localhost:8090/admin/rules/reload \
  -H "X-Service-Api-Key: $API_KEY" | jq
# {"previousVersion": 1, "currentVersion": 1, "ruleCount": 6}
```

**Talk point:** "POST one endpoint. No restart. If the YAML is bad, 422 with field-level errors and the OLD set keeps serving."

---

### Card 5 — "Audit trail with real SHA-256 hash"

```bash
curl -s -H "X-Service-Api-Key: $API_KEY" \
  'http://localhost:8090/admin/audit?size=1' | jq '.items[0]'
```

**Expected:**
```json
{
  "id": ...,
  "actor": "rest-api",
  "action": "DECISION_WRITE",
  "resourceId": "<decision uuid>",
  "payloadHash": "<64 hex chars — real SHA-256>",
  "occurredAt": "..."
}
```

**Talk point:** "Hash is over the canonical decision payload, not the txId. Tampering with the audit row in place would change the row but not the hash."

---

### Card 6 — "Domain metrics"

```bash
curl -s -H "X-Service-Api-Key: $API_KEY" http://localhost:8090/actuator/prometheus \
  | grep -E "^(fraud_decisions_total|rule_eval_duration_seconds_count|audit_write_failed_total|idempotency_cache_size)"
```

**Talk point:** "Per-status decision counter, evaluation latency histogram with p50/p95/p99, audit-write failure counter for the rare commit-succeeded/audit-failed gap."

---

### Card 7 — "Advisory falls back to 503 without Ollama"

```bash
# Capture a decision id first
DECISION_ID=$(curl -s -X POST http://localhost:8090/api/v1/transactions \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: demo-adv-$(date +%s)" \
  -H "Content-Type: application/json" -d @/tmp/demo-tx.json | jq -r .decisionId)

curl -s -D - "http://localhost:8090/api/v1/decisions/$DECISION_ID/advisory" \
  -H "Authorization: Bearer $TOKEN" | grep -iE "(HTTP/|Retry-After)"
# HTTP/1.1 503
# Retry-After: 10
```

**Talk point:** "The advisory layer is opt-in. Without it the endpoint returns 503 with Retry-After. The deterministic decision is unchanged."

---

### Card 8 — "Tests"

```bash
./mvnw verify 2>&1 | grep -E "Tests run:" | tail -3
# [INFO] Tests run: 78, Failures: 0, Errors: 0, Skipped: 0
# [INFO] BUILD SUCCESS

cat target/site/jacoco/jacoco.csv \
  | awk -F',' 'NR>1 && $2~/engine/{m+=$8;c+=$9} END{printf "%d/%d = %.1f%%\n", c, m+c, 100.0*c/(m+c)}'
# 260/265 = 98.1%
```

**Talk point:** "Engine coverage is 98%, gate at 95% enforced in jacoco:check. Mutation testing wired via Pitest; Day-7 promotes it into verify."
