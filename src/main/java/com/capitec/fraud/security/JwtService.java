package com.capitec.fraud.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Issues HS256 JWTs for the demo. The signing key comes from
 * {@code app.security.jwt.secret} (env-only, never logged). Production
 * deployment switches to an asymmetric key (RS256/EdDSA) and an external
 * IdP — see ADR-0007.
 */
@Service
public class JwtService {

    private final byte[] secret;
    private final String issuer;
    private final Duration ttl;

    public JwtService(@Value("${app.security.jwt.secret}") String secret,
                      @Value("${app.security.jwt.issuer:fraud-rule-engine}") String issuer,
                      @Value("${app.security.jwt.ttl-seconds:3600}") long ttlSeconds) {
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalStateException(
                    "JWT secret must be ≥ 32 bytes for HS256. Set app.security.jwt.secret in env.");
        }
        this.secret = secret.getBytes();
        this.issuer = issuer;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public String issue(String subject, Set<String> roles) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(issuer)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(ttl)))
                    .jwtID(UUID.randomUUID().toString())
                    .claim("roles", List.copyOf(roles))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
    }
}
