package com.capitec.fraud.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Demo-only token issuer. In production this lives behind an IdP
 * (Keycloak / Azure AD) — see ADR-0007. Kept here so reviewers can curl
 * a token without standing up an external service.
 *
 * <p><b>Security note (banking-critical):</b> this endpoint hard-codes
 * {@code ROLE_USER} on every issued token. Callers cannot escalate to
 * {@code SERVICE} or any other role via the request body — that would
 * defeat the {@link ApiKeyAuthFilter} entirely (a public unauthenticated
 * endpoint cannot be allowed to mint admin authority). Service callers
 * must use the {@code X-Service-Api-Key} header path, not this endpoint.
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "auth", description = "Demo-only token issuance. Production deployment uses an external IdP.")
public class AuthController {

    /** The ONLY role this demo endpoint will mint. */
    private static final Set<String> ALLOWED_ROLES = Set.of("USER");

    private final JwtService jwt;

    public AuthController(JwtService jwt) {
        this.jwt = jwt;
    }

    @Operation(summary = "Issue a short-lived demo JWT. Always with role USER — "
            + "this endpoint cannot mint elevated roles regardless of request body.")
    @PostMapping("/token")
    public TokenResponse token(@Valid @RequestBody TokenRequest req) {
        return new TokenResponse(jwt.issue(req.subject(), ALLOWED_ROLES), 3600);
    }

    public record TokenRequest(@NotBlank @Size(max = 128) String subject) {}
    public record TokenResponse(String accessToken, long expiresInSeconds) {}
}
