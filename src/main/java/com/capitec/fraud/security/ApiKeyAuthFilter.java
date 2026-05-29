package com.capitec.fraud.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.io.IOException;
import java.util.List;

/**
 * Service-to-service API key for admin + scrape endpoints. Checks
 * {@code X-Service-Api-Key} header against {@code app.security.api-key}.
 * On match, sets a {@code SecurityContext} with role {@code SERVICE}.
 * Does NOT short-circuit on miss — Spring Security's OAuth2 resource server
 * runs after this filter and may still authenticate via JWT.
 *
 * <p>The key compare is constant-time to avoid timing oracles.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Service-Api-Key";

    private final byte[] expected;

    /** Banking-grade minimum: 32 chars gives ~192 bits of entropy and matches the
     * length of {@code openssl rand -base64 24}. The previous 16-char floor was
     * insufficient for a static service credential. */
    static final int MIN_API_KEY_LENGTH = 32;

    public ApiKeyAuthFilter(@Value("${app.security.api-key}") String apiKey) {
        if (apiKey == null || apiKey.length() < MIN_API_KEY_LENGTH) {
            throw new IllegalStateException(
                    "Service API key must be >= " + MIN_API_KEY_LENGTH
                            + " chars. Set app.security.api-key (env SERVICE_API_KEY).");
        }
        this.expected = apiKey.getBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String provided = req.getHeader(HEADER);
        if (provided != null && constantTimeEquals(provided.getBytes(), expected)) {
            var auth = new UsernamePasswordAuthenticationToken(
                    "service",
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(req, res);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
