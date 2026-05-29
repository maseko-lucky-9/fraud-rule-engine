package com.capitec.fraud.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that {@code application.yml} uses fail-fast placeholder syntax
 * ({@code ${VAR:?error}}) for every required secret, with no dev-default
 * fallback string left behind.
 *
 * <p>A regression here (re-introducing a dev default) would let a misconfigured
 * production deployment boot silently with a known-weak secret — exactly the
 * heap-dump / source-leak vector the Day-6 review surfaced.
 *
 * <p>We assert against the YAML text rather than booting a stripped-down
 * context because Spring's {@code PlaceholderResolver} would otherwise be
 * shadowed by the test-side {@code application.yml} override that provides
 * deterministic values to the Testcontainers boot.
 */
class SecretsFailFastTest {

    private static final Path APP_YML = Path.of("src/main/resources/application.yml");

    @Test
    void jwtSecretHasFailFastPlaceholder() throws Exception {
        String yml = Files.readString(APP_YML);
        assertThat(yml)
                .as("JWT_HS256_SECRET must use fail-fast ${...:?...} syntax")
                .containsPattern(Pattern.compile("\\$\\{JWT_HS256_SECRET:\\?[^}]+\\}"));
    }

    @Test
    void postgresPasswordHasFailFastPlaceholder() throws Exception {
        String yml = Files.readString(APP_YML);
        assertThat(yml)
                .as("POSTGRES_PASSWORD must use fail-fast ${...:?...} syntax")
                .containsPattern(Pattern.compile("\\$\\{POSTGRES_PASSWORD:\\?[^}]+\\}"));
    }

    @Test
    void serviceApiKeyHasFailFastPlaceholder() throws Exception {
        String yml = Files.readString(APP_YML);
        assertThat(yml)
                .as("SERVICE_API_KEY must use fail-fast ${...:?...} syntax")
                .containsPattern(Pattern.compile("\\$\\{SERVICE_API_KEY:\\?[^}]+\\}"));
    }

    @Test
    void noDevOnlyFallbackStrings() throws Exception {
        String yml = Files.readString(APP_YML);
        assertThat(yml)
                .as("dev-only / changeme strings must not appear as defaults")
                .doesNotContain("dev-only-secret")
                .doesNotContain("dev-only-api-key")
                .doesNotContain("changeme-local-only");
    }
}
