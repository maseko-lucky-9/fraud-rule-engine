# ADR-0007: Security — JWT + Service API Key

**Status:** Accepted · **Date:** 2026-05-19

## Context

Day-4 locks down the API. Three identities exist in the running system:

- **Client/user** — authenticates with a Bearer JWT, accesses `/api/v1/**`.
- **Service** — authenticates with a static API key, accesses `/admin/**` and the Prometheus scrape endpoint.
- **Public** — `/actuator/health/**`, `/auth/token`, `/v3/api-docs/**`, `/swagger-ui/**`.

A banking system must also handle: rate limiting (per identity), idempotency (per identity, replay-resistant), audit (every decision and every admin action), and PII redaction in logs (POPIA / SARB).

## Decision

### AuthN

- **JWT, HS256** signed with a 32+ byte secret loaded from `app.security.jwt.secret` (env-only, never logged). `JwtService` issues tokens (demo only — `POST /auth/token`); `NimbusJwtDecoder` validates incoming Bearer tokens. The `roles` claim is mapped to `ROLE_*` authorities so method-level `@PreAuthorize` works.
- **Service API key** — static, ≥ 16 chars, loaded from `app.security.api-key`. `ApiKeyAuthFilter` runs **before** Spring Security's `BearerTokenAuthenticationFilter` in the chain; on `X-Service-Api-Key` match it stamps `ROLE_SERVICE` on the `SecurityContext`. Key compare is **constant-time** to defeat timing oracles.

### AuthZ

- Path-based: `/admin/**`, `/actuator/prometheus`, `/actuator/metrics/**` require `ROLE_SERVICE`. Everything else (except the public allowlist) requires `authenticated()`.
- Method-level `@PreAuthorize("hasRole('SERVICE')")` belt-and-braces on `AuditController`.

### Idempotency

- Redis-backed cache keyed on `idem:{subject}:{Idempotency-Key}`. The cache value is a JSON envelope `{ bodyHash, response }`.
- Cache TTL **24h** (configurable via `app.idempotency.ttl-hours`).
- Body hash is **SHA-256 of the canonical JSON serialisation** of the parsed request (Jackson with `LinkedHashMap`-style ordering on records) — so whitespace differences between client formattings still hit the cache.
- **Collision semantics:** same key + same hash → return cached response with HTTP 202. Same key + different hash → **409 Conflict** with RFC 7807 problem-details carrying the offending `idempotencyKey`. Binding the cache entry to the JWT `sub` neuters cross-subject key-replay attacks.

### Rate limiting

- **Bucket4j + Redis** via `bucket4j_jdk17-lettuce`. Bucket keyed by `rl:{sub}` so all API replicas share the counter.
- Default budget: **100 req/min per subject** (configurable). On exhaustion: HTTP **429**, `Retry-After` header, RFC 7807 body.
- `ROLE_SERVICE` is **exempt** (Prometheus scrape + admin paths can't be throttled).
- Counter exposed as `rate_limit_exceeded_total`.

### Audit

- Every decision write + every `/admin/rules/reload` publishes a Spring `ApplicationEvent` (`AuditEvent`). The listener runs **async** in a dedicated `auditExecutor` thread pool with `@Transactional(REQUIRES_NEW)` so audit-write latency doesn't block ingest.
- Surfaced via `GET /admin/audit?page&size`, `ROLE_SERVICE`-only.

### Error model

- **RFC 7807 Problem Details** for every error response, courtesy of `ProblemDetailExceptionHandler` (`@RestControllerAdvice`). Includes `type`, `title`, `status`, `detail`, `instance`, and a `correlationId` pulled from MDC. Validation errors carry a structured `errors[]` list.

### Observability

- `CorrelationIdFilter` reads or generates `X-Correlation-Id`, sets MDC, echoes the response header.
- `EngineMetrics` registers `fraud_decisions_total{status=...}`, `rule_eval_duration_seconds` (percentile histogram 50/95/99), `idempotency_cache_size`, `rate_limit_exceeded_total`, plus the existing Resilience4j / actuator metrics.
- Logs are structured JSON with `correlationId` in every line; `PiiRedactor` is the canonical helper for masking `accountId` (`ABC-****1234` shape) — POPIA + SARB requirement.

## Production upgrade path (out of scope for the submission)

1. **HS256 → RS256/EdDSA.** Replace `JwtService` with an external IdP issuance (Keycloak / Azure AD); replace `NimbusJwtDecoder.withSecretKey()` with `.withJwkSetUri()`.
2. **API key → mTLS.** The service-to-service path graduates from a shared secret to mutually-authenticated TLS with a private CA.
3. **JWT key rotation.** Rotate via JWKS endpoint; in-process cache invalidates on JWKS poll. Out of scope for demo.

## Consequences

**Positive.**
- Every request is authenticated; every state change is audited; every admin action is rate-limit-exempt but explicitly audited.
- Banking review can answer "who did this and when" for any decision or admin action.
- Idempotency contract is replay-attack resistant (subject-bound) and body-hash correct (whitespace tolerant).

**Negative.**
- Static service API key is a known smell — production migration to mTLS is documented but not implemented.
- JWT secret leakage is catastrophic — protected by env-only loading, gitleaks Day-7 gate, and `≥ 32 byte` validation at startup that fails the boot.

**Known footgun (recorded so the reviewer can verify the fix in git history):** `@Component` + `OncePerRequestFilter` causes Spring Boot to auto-register the filter via the servlet container, then the SecurityFilterChain registration becomes a no-op (the OncePer marker skips the second invocation). Fix: register a `FilterRegistrationBean` with `setEnabled(false)` for the filter, keeping it as a `@Component` only so Spring can inject it into `SecurityConfig`.
