# Threat model — STRIDE

Per-component STRIDE pass on the running system. Mitigation status: ✅ in code, 📝 in docs/runbook, 🔮 forward-path.

## Component: REST API surface (`/api/v1/**`)

| Threat | Vector | Mitigation | Status |
|---|---|---|---|
| **S**poofing | Forged Bearer token | NimbusJwtDecoder validates HS256 signature + issuer claim + exp + nbf | ✅ |
| | Replay an intercepted token | TTL 1 h; doc'd RS256/JWKS migration | 🔮 |
| **T**ampering | Request body modified in transit | HTTPS in production; canonical body hash for idempotency replay-attack defence | 📝 |
| **R**epudiation | Caller denies a transaction | Audit log row per `DECISION_WRITE` with SHA-256 payload hash + actor (`rest-api`) + correlation ID | ✅ |
| **I**nformation disclosure | PII in logs | `PiiRedactor.redactAccountId()` on every accountId log site | ✅ |
| | Internal error details leaked | RFC 7807 generic message for 5xx; full stack only in server log | ✅ |
| **D**oS | Rate-limit bypass via unauthenticated path | Spring Security rejects unauth before the bucket filter; ROLE_SERVICE exempt for legitimate scrape | ✅ |
| | Cache flooding via random keys | Idempotency cache TTL 24h; size metric watched | ✅ |
| **E**oP | Role escalation via `/auth/token` | Endpoint hardcodes `ROLE_USER`; SERVICE only via API key (Day 4.5 fix) | ✅ |

## Component: Admin surface (`/admin/**`)

| Threat | Vector | Mitigation | Status |
|---|---|---|---|
| **S** | Compromised API key | Key length ≥ 16 enforced at startup; constant-time compare; documented rotation via env reload | ✅ |
| **T** | Malicious rule YAML pushed | JSON-Schema validation → 422; old set stays active; reload audited; YAML hash logged | ✅ |
| **R** | "Who reloaded the rule?" | Audit `RULES_RELOAD` action + actor + YAML hash | ✅ |
| **I** | Audit log endpoint leaks sensitive ops data | ROLE_SERVICE-gated; `actor` is "service" not the human; correlation ID in every line | ✅ |
| **D** | Reload spam | Day-7 polish — ROLE_SERVICE is currently exempt from rate limit (legitimate use case for scrape). Reload-specific limit per source IP is a forward path | 🔮 |
| **E** | Privilege escalation to ADMIN | No ADMIN role exists in the system; only USER + SERVICE. ROLE_SERVICE cannot be obtained via the user JWT path (Day 4.5 fix) | ✅ |

## Component: Postgres

| Threat | Vector | Mitigation | Status |
|---|---|---|---|
| **S** | Stolen DB credentials | Env-only loading; never committed to repo; gitleaks Day-7 gate | ✅ |
| **T** | Audit row tampered in place | `payload_hash` is a SHA-256 of the canonical decision; re-deriving it from the linked decision detects tampering (Day 4.5 fix) | ✅ |
| **R** | Audit gap due to async write failure | `audit_write_failed_total` counter (Day 5); ERROR log with `resourceId`. Durable retry queue is Day-7 polish | ✅ / 🔮 |
| **I** | Decision data exfiltration | DB on private network; principle of least privilege; backups encrypted (production) | 📝 |
| **D** | Disk-full | Documented in [runbook db-down](../../runbooks/db-down.md) | 📝 |
| **E** | SQL injection | Parametrized queries everywhere (Spring Data JPA + native `:param` binding); no raw `Statement` calls | ✅ |

## Component: Kafka / Redpanda

| Threat | Vector | Mitigation | Status |
|---|---|---|---|
| **S** | Foreign producer to `tx.events.v1` | mTLS or SASL/SCRAM in production; not configured in demo | 🔮 |
| **T** | Message tampered in transit | mTLS encrypts; idempotent producer (`enable.idempotence=true`) prevents duplicate sequence numbers | ✅ |
| **R** | Lost decision events | Transactional outbox + processed_events dedup table; replay-safe | ✅ |
| **I** | Topic data readable by anyone | ACLs in production; network-isolated in demo | 📝 |
| **D** | Consumer lag attacking the broker | Producer back-pressure via `acks=all`; DLT (`tx.events.dlt`) for poison events | ✅ |
| **E** | Privileged operations on the cluster | Demo has `--mode=dev-container`; production uses RBAC | 🔮 |

## Component: Redis

| Threat | Vector | Mitigation | Status |
|---|---|---|---|
| **S** | Open Redis port | `127.0.0.1:6379` binding in demo; private network in production | ✅ |
| **T** | Idempotency cache tampering | Cache is read-only from the API's perspective; entries TTL 24h. A compromised Redis = much bigger problem | 📝 |
| **R** | Lost idempotency record | Cache is a hint; the underlying `processed_events` table is authoritative | ✅ |
| **I** | PII in cache | Cache stores the response (not the request); response has redacted fields | 📝 |
| **D** | Cache exhaustion | TTL eviction; `idempotency_cache_size` metric | ✅ |
| **E** | Lateral movement | Redis runs as unprivileged; production isolates by VPC | 🔮 |

## Component: Ollama (optional advisory)

| Threat | Vector | Mitigation | Status |
|---|---|---|---|
| **S** | Adversary impersonates Ollama | Ollama only reachable on internal compose network; demo is loopback | ✅ |
| **T** | Adversary controls Ollama → injects misleading advice | `humanReviewRequired=true` is set in code, not derived from response; deterministic decision is unchanged. Even if Ollama lies, no harm. | ✅ |
| **R** | "Why did the advisory recommend X?" | Prompt template + model + Ollama version pinned in `docker-compose.advisory.yml` + ADR-0010 | ✅ |
| **I** | PII leaks to the LLM | Prompt template forbids account-id / customer-name / amount inclusion; only verdict + score + rule ids sent | ✅ |
| **D** | Ollama times out → blocks core path | Separate endpoint; 2s read timeout; circuit breaker; 503 fallback | ✅ |
| **E** | LLM "decides" to approve a fraud | Structurally impossible — deterministic decision pre-exists and is authoritative | ✅ |

## Out of scope (documented in ADRs)

- DDoS protection at the edge (Cloudflare/CDN — production).
- WAF rules (production).
- HSM-backed signing keys (RS256 production migration).
- mTLS for all internal service-to-service hops.
