package com.capitec.fraud.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import org.springframework.boot.web.servlet.FilterRegistrationBean;

import javax.crypto.spec.SecretKeySpec;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Day-4 lock-down. Three identities exist:
 *
 * <ul>
 *   <li><b>JWT bearer</b> — required for every {@code /api/v1/**} endpoint.
 *       The {@code roles} claim feeds Spring's {@code GrantedAuthority} set so
 *       method-level {@code @PreAuthorize} works.</li>
 *   <li><b>Service API key</b> — required for {@code /admin/**}. Validated by
 *       {@link ApiKeyAuthFilter} before this chain reaches Spring Security's
 *       JWT filter.</li>
 *   <li><b>Public</b> — {@code /actuator/health/**}, {@code /v3/api-docs/**},
 *       {@code /swagger-ui/**}, {@code /auth/token}.</li>
 * </ul>
 *
 * The actuator scrape endpoint ({@code /actuator/prometheus}) is gated by the
 * same API key as admin — Prometheus must include the header on scrape.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final String jwtSecret;
    private final String jwtIssuer;

    public SecurityConfig(@Value("${app.security.jwt.secret}") String jwtSecret,
                          @Value("${app.security.jwt.issuer:fraud-rule-engine}") String jwtIssuer) {
        this.jwtSecret = jwtSecret;
        this.jwtIssuer = jwtIssuer;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ApiKeyAuthFilter apiKeyFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Browser-hardening headers. These don't change API behavior
                // (this is a JSON API; CSP applies only to error pages and
                // Swagger UI) but they raise the floor for any downstream
                // proxy / portal that consumes the same response.
                .headers(h -> h
                        // Default RequestMatcher for HSTS is "secure-only" (HTTPS),
                        // which would drop the header in MockMvc + local-HTTP test
                        // environments and on any TLS-terminating proxy upstream
                        // of the API. Override to AnyRequestMatcher so the
                        // header is set unconditionally — the worst case on a
                        // misconfigured plain-HTTP environment is a harmless
                        // header the browser ignores.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .requestMatcher(org.springframework.security.web.util.matcher.AnyRequestMatcher.INSTANCE)
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; frame-ancestors 'none'"))
                        .frameOptions(f -> f.deny())
                        .contentTypeOptions(c -> {})
                        .referrerPolicy(r -> r.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/auth/token"
                        ).permitAll()
                        // Read advisory commentary uses JWT just like /api/v1/decisions.
                        .requestMatchers("/api/v1/decisions/*/advisory").authenticated()
                        .requestMatchers("/admin/**", "/actuator/prometheus", "/actuator/metrics/**").hasAuthority("ROLE_SERVICE")
                        .anyRequest().authenticated())
                .addFilterBefore(apiKeyFilter, org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * Disable Spring Boot's auto-registration of the {@code @Component} filter
     * so it ONLY runs inside the SecurityFilterChain. Without this the
     * {@code OncePerRequestFilter} marker causes the in-chain invocation to
     * be skipped (the servlet-container registration already ran the filter).
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(ApiKeyAuthFilter filter) {
        FilterRegistrationBean<ApiKeyAuthFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        var key = new SecretKeySpec(jwtSecret.getBytes(), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // Day-5 hardening: enforce iss claim + default exp/nbf checks.
        // Without this any token signed with the shared secret was accepted
        // regardless of issuer — see Day-4.5 review item #4.
        OAuth2TokenValidator<Jwt> validators = JwtValidators.createDefaultWithIssuer(jwtIssuer);
        decoder.setJwtValidator(validators);
        return decoder;
    }

    /** Maps the {@code roles} claim → {@code ROLE_*} authorities so {@code @PreAuthorize("hasRole('ADMIN')")} works. */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        Converter<Jwt, Collection<GrantedAuthority>> rolesConverter = jwt -> {
            Object roles = jwt.getClaim("roles");
            if (!(roles instanceof List<?> list)) return List.of();
            return list.stream()
                    .map(String::valueOf)
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                    .collect(Collectors.<GrantedAuthority>toUnmodifiableSet());
        };
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(rolesConverter);
        return converter;
    }
}
