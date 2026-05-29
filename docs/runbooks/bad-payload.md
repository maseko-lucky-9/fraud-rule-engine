# Runbook — Bad payload spike

**Severity:** P3 (no customer impact, but the 4xx ratio looks bad and may mask real outages).

## Symptoms

- Sudden spike in `http_server_requests_seconds_count{status="400"}` from `/api/v1/transactions`.
- Logs show `MethodArgumentNotValidException` clustering on specific fields.
- Caller's correlation IDs all share a prefix or `User-Agent` → likely a single client mis-wired.

## Immediate response

1. **Identify the offending client** — `correlationId` from any failing problem-details response → grep API logs:
   ```bash
   docker logs fraud-api 2>&1 | grep -E "corrId=$cid" | head
   ```
2. **Sample a few rejections** to find the failing field:
   ```bash
   curl -s -H "Authorization: Bearer $TOKEN" -X POST … | jq '.errors'
   # [{"field":"currency","message":"must match ..."}, ...]
   ```
3. **Notify the integration team** with: failing field, expected pattern (from `TransactionRequest` constraints), correlation ID.

## Common patterns

| Field | Validation | Typical client bug |
|---|---|---|
| `currency` | `[A-Z]{3}` | Lowercase or 4 chars |
| `country` / `ipCountry` | `[A-Z]{2}` | Lowercase, or country name |
| `mcc` | `[0-9]{4}` | 3 digits or alpha |
| `amount` | `>=0` + `Digits(integer=15, fraction=4)` | Sub-cent precision or negative |
| `timestamp` | ISO-8601 | Local-time format |

## Mitigation

- Do **not** loosen the validation. Bank context — every constraint is there to defend a downstream invariant.
- Provide a tighter Swagger / OpenAPI doc with examples (already in `springdoc` annotations).
- If the offender is internal, share the [TransactionRequest schema](../api/openapi.yaml) directly.

## Prevention

- Contract tests against `openapi.yaml` (Day 7 polish) catch a re-shaped DTO before deploy.
- Client SDK generation from the OpenAPI spec eliminates hand-rolled mistakes.

## Verification

```bash
# Send a known-bad payload, confirm 400 + RFC 7807 body with errors[]:
curl -s -X POST http://localhost:8090/api/v1/transactions \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"txId":"d6000099-0000-0000-0000-000000000099","accountId":"X","amount":1,"currency":"zar","mcc":"123","channel":"WEB","country":"za","ipCountry":"ZA","accountAgeDays":1,"timestamp":"2026-05-19T12:00:00Z"}' \
  | jq '.errors'
```
