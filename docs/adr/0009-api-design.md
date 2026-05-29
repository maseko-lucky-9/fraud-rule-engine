# ADR-0009: API Design — Contract-First, RFC 7807, Cursor Pagination

**Status:** Accepted · **Date:** 2026-05-19

## Context

The submission's HTTP surface is the reviewer's primary point of contact. It must be: easy to call (Swagger UI + clear errors), idempotent where it matters (financial transactions), paginated correctly for any list endpoint that could grow, and consistent in error shape across every code path.

## Decision

### Contract-first

- **springdoc-openapi 2.7.0** generates a live OpenAPI 3 spec from controller signatures + `@Operation`/`@Tag` annotations. Swagger UI hosted at `/swagger-ui.html` (public — reviewer can browse without auth).
- Endpoints documented inline; no separate hand-maintained spec file (deliberate — drift kills contracts).

### Endpoints (Day 4 surface)

| Method | Path | Auth | Notes |
|---|---|---|---|
| `POST` | `/auth/token` | public | Demo-only token issuer. Production = external IdP. |
| `POST` | `/api/v1/transactions` | JWT | Submit single event. Supports `Idempotency-Key` (24h cache). |
| `GET`  | `/api/v1/decisions` | JWT | Paginated list, newest first. |
| `GET`  | `/api/v1/decisions/{id}` | JWT | Single decision with full matched-rule trace. |
| `POST` | `/admin/rules/reload` | API key (`ROLE_SERVICE`) | Hot-reload rule YAML. 200 on success; 422 with problem-details on validation failure. |
| `GET`  | `/admin/audit` | API key (`ROLE_SERVICE`) | Audit-log read; paginated, newest first. |
| `GET`  | `/actuator/health/**` | public | Liveness + readiness probes. |
| `GET`  | `/actuator/prometheus` | API key | Domain metrics. |

### Error model — RFC 7807 Problem Details everywhere

Every error response is `application/problem+json` with this shape:

```json
{
  "type": "https://fraud-engine.example/problems/<slug>",
  "title": "Human-readable summary",
  "status": 4xx|5xx,
  "detail": "Specific context for this occurrence",
  "instance": "/path/that/failed",
  "correlationId": "<X-Correlation-Id>",
  "errors": [{ "field": "...", "message": "..." }]
}
```

`ProblemDetailExceptionHandler` (a `@RestControllerAdvice`) maps:
- `MethodArgumentNotValidException` → 400 with field-level `errors[]`.
- `IllegalArgumentException` → 400.
- `RuleValidationException` → 422.
- `AuthenticationException` → 401.
- `AccessDeniedException` → 403.
- Everything else → 500 (with a logged stack, sanitised body).

### Pagination

- All list endpoints use **`(page, size)` with `size` clamped to `[1, 500]`**. Returns `{ items, page, size, total }`.
- Cursor-style pagination via `after=<evaluatedAt>` was considered but deferred: page-based is simpler for the reviewer to demo, and the volumes Day-4 ships against don't yet require an append-only cursor.

### Idempotency

- `Idempotency-Key` header is **optional** but recommended for non-idempotent verbs (`POST /transactions`).
- Cache entry is bound to the JWT subject so replay across users isn't possible.
- Conflict semantics: same key + different body hash → 409 with the previous key surfaced in the problem-details body.

### Layering — `api ↛ persistence`

Hard ArchUnit rule. Read controllers (`DecisionController`, `AuditController`) call thin query services (`DecisionQueryService`, `AuditQueryService`) that own the entity↔DTO translation. The `api` package never imports `persistence` directly — entities stay behind the service boundary, eliminating the surface area for a future schema change to leak into the wire format.

### Versioning

- Path-based versioning: `/api/v1/...`. v2 will be a separate prefix when (if) a breaking change lands.
- Kafka topics are independently versioned: `tx.events.v1`, `tx.decisions.v1`. A v2 topic ships when the schema breaks; v1 stays for the migration window.

## Consequences

**Positive.**
- Reviewer hits Swagger UI, sees every endpoint with descriptions + examples + auth requirements — no separate doc to maintain.
- Every error in the system speaks the same JSON dialect (RFC 7807). Clients write one error-handling pattern, not one per route.
- Layering rule prevents the slow rot of "controllers know too much about the database" that plagues mature codebases.

**Negative.**
- Page-based pagination is less robust than cursor under concurrent writes — flagged for Day-7 polish if the volume grows.
- OpenAPI annotations on every endpoint add code surface — but vs. hand-maintained YAML the drift cost is lower.

## Forward path

- Day 5: contract tests against `openapi.yaml` (Spring REST Docs or `springdoc` schema-diff) to lock the contract in CI.
- Day 6: full curl walkthrough in README with expected JSON outputs.
- Day 7: rate limit headers on every response (`X-RateLimit-Remaining` is already there; add `X-RateLimit-Limit`); SLA documentation.
