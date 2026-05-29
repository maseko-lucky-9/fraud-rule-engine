# ADR-0012: JWT key rotation — JWKS roadmap

## Status

Proposed — documentation only. Not implemented in this PR.

## Context

ADR-0007 ships an HS256 demo with a single symmetric secret loaded from
`JWT_HS256_SECRET`. The post-overhaul `application.yml` fails-fast if the
secret is missing, and commit `eea9cfb` (chore: security) bumps API-key
and headers to a banking-grade baseline. But the JWT key itself remains
shared, symmetric, and rotation-free:

1. Every API replica boots with the same byte[] in heap. A heap dump
   from any replica leaks the signing key to anyone with the artifact.
2. There is no rotation mechanism. Operators who suspect compromise
   must roll every client simultaneously and restart every replica.
3. There is no asymmetric verification surface; downstream consumers
   that want to verify a token without holding the signing key cannot.

The current scope explicitly accepts this trade-off. Production
deployment must lift the engine onto an asymmetric, rotation-friendly
JWT path.

## Decision

Plan, but do not implement here, a migration to RS256 + JWKS:

1. **Identity provider** — adopt one of:
   - **Keycloak** (self-hosted; cleanest for on-prem deployment),
   - **Azure AD / Entra ID** (if consolidating on an M365 IdP),
   - **AWS Cognito** (if an AWS estate is the dominant target).
   Decision deferred to the production-handoff conversation.

2. **Token verification** — replace the in-process `NimbusJwtDecoder
   .withSecretKey(…)` with `NimbusJwtDecoder.withJwkSetUri(…)` pointing
   at the IdP's `.well-known/jwks.json`. Nimbus auto-refreshes the JWK
   cache; default TTL 5 min.

3. **Key rotation cadence** — quarterly with a 7-day overlap window
   where both the old and the new key are present in the JWK set. The
   in-process cache picks up the new key on next refresh (≤ 5 min) and
   the IdP keeps the old key signed-by present until the overlap closes.

4. **Algorithm** — RS256 (2048-bit RSA). Asymmetric so verifiers don't
   hold the signing key; widely supported by every modern client library.

5. **Backward compatibility** — disable HS256 path entirely once JWKS
   is live. The demo `/auth/token` endpoint is removed in the same
   change (interview-only endpoint that mints arbitrary subjects with
   ROLE_USER).

## Consequences

* The current Phase 0d Actions secret `JWT_HS256_SECRET` will be retired.
  CI / dev workflows that still need to mint tokens for tests must move
  to a fixed test-only RSA keypair shipped in `src/test/resources/`
  (private + public PEM), with the test profile pointing at it.
* `SecurityConfig.jwtDecoder()` becomes a `NimbusJwtDecoder.withJwkSetUri`
  bean — ~6 lines of code change plus the IdP wiring.
* Audit log writes are unaffected (the payload_hash already covers the
  decision content; the actor field will now carry the IdP-issued
  subject claim).

## References

* ADR-0007 (current JWT decision)
* https://datatracker.ietf.org/doc/html/rfc7517 (JWK)
* https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
