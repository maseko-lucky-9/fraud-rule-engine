# Security controls matrix

Quick-reference table — every control implemented in the codebase, its purpose, and where to verify it.

| # | Control | Purpose | Where |
|---|---|---|---|
| 1 | JWT (HS256) | Client authentication | `JwtService`, `SecurityConfig.jwtDecoder` |
| 2 | JWT issuer validation | Reject foreign-iss tokens | `SecurityConfig.jwtDecoder` (`JwtValidators.createDefaultWithIssuer`) |
| 3 | Service API key (constant-time) | Service authentication | `ApiKeyAuthFilter` |
| 4 | API key length ≥ 16 fail-fast | Prevent weak demo keys | `ApiKeyAuthFilter` constructor throws on startup |
| 5 | JWT secret length ≥ 32 fail-fast | Prevent weak HS256 | `JwtService` constructor throws on startup |
| 6 | Stateless session policy | No session fixation surface | `SecurityConfig.filterChain` |
| 7 | Method-level `@PreAuthorize` | Belt-and-braces on admin paths | `AuditController` |
| 8 | Role hardcoded in demo token issuer | Block role escalation via `/auth/token` | `AuthController.ALLOWED_ROLES = {USER}` |
| 9 | Idempotency-Key + body SHA-256 | Replay protection on POST | `IdempotencyService` |
| 10 | Idempotency cache subject-bound | Cross-user key replay defence | `IdempotencyService.redisKey` |
| 11 | Idempotency conflict → 409 + `previousRequestId` | Distinguish replay from forgery | `TransactionController` |
| 12 | Bucket4j rate limit | Per-subject backpressure | `RateLimitFilter` |
| 13 | ROLE_SERVICE exempt from rate limit | Allow Prometheus scrape | `RateLimitFilter` |
| 14 | RFC 7807 problem-details | No internal-detail leak in 5xx | `ProblemDetailExceptionHandler` |
| 15 | Correlation-Id (request + response) | Forensic traceability | `CorrelationIdFilter` |
| 16 | PII redaction in logs | POPIA compliance | `PiiRedactor.redactAccountId()` — `IngestService`, `TransactionKafkaListener` |
| 17 | Audit log with SHA-256 hash | Tamper detection | `IngestService` + `AuditEntity.payload_hash` |
| 18 | `audit_write_failed_total` counter | Audit-gap observability | `AuditEventListener` (Day 5) |
| 19 | Localhost-only port bindings | Reduce demo attack surface | `docker-compose.yml` (Day 1.5) |
| 20 | Non-root container user | Defence in depth | `Dockerfile` |
| 21 | `.env` gitignored | Secret never committed | `.gitignore` |
| 22 | Constant-time API key compare | Defeat timing oracle | `ApiKeyAuthFilter.constantTimeEquals` |
| 23 | CSRF disabled (stateless API) | Token-based clients don't need CSRF | `SecurityConfig.filterChain` |
| 24 | OAuth2-resource-server JWT filter | Standard Spring Security path | `SecurityConfig.filterChain.oauth2ResourceServer` |
| 25 | Custom filter order via `addFilterBefore` | API key check before JWT | `SecurityConfig.filterChain` |
| 26 | `FilterRegistrationBean(enabled=false)` | Prevent servlet-container auto-registration | `SecurityConfig.apiKeyAuthFilterRegistration` |
| 27 | JSON-Schema rule validation | Reject malformed YAML | `RuleLoader` schema validator |
| 28 | Rule reload preserves old set on failure | Never an outage | `AdminController.reload` 422 path |
| 29 | Predicate id uniqueness fail-fast | Prevent silent rule shadowing | `PredicateRegistry` |
| 30 | Advisory `humanReviewRequired=true` invariant | LLM never authoritative | `AdvisoryResponse` factory methods |
| 31 | Advisory 2s read timeout | Prevent slow-LLM ingress | `OllamaAdvisoryService` (Day 5.5) |
| 32 | Advisory circuit breaker | Persistent failure auto-mitigation | `OllamaAdvisoryService` (Resilience4j) |
| 33 | Prompt template forbids PII inclusion | Defence-in-depth even if LLM lies | `prompts/advisory-v1.md` |

## Production gap acknowledgements

- HS256 → RS256 + JWKS (ADR-0007 §Forward path)
- API key → mTLS (ADR-0007)
- Audit retry queue for true durability (Day-7)
- WAF / DDoS / network-level controls (out of scope)
- HSM-backed key custody (out of scope)
