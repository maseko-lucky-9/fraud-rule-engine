package com.capitec.fraud.security;

import com.capitec.fraud.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * Verifies the browser-hardening headers added in
 * {@link SecurityConfig#filterChain}. The API is JSON-first, but a
 * downstream reverse proxy or developer portal may surface responses to a
 * browser, so we set the baseline that protects against clickjacking,
 * MIME-sniffing, mixed-content downgrades, and referrer leakage.
 *
 * <p>Hitting a public path keeps the test independent of auth wiring.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityHeadersTest {

    @Autowired
    MockMvc mvc;

    @Test
    void publicEndpointCarriesAllHardeningHeaders() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(header().exists("Strict-Transport-Security"))
                .andExpect(header().string("Strict-Transport-Security",
                        org.hamcrest.Matchers.containsString("max-age=31536000")))
                .andExpect(header().string("Strict-Transport-Security",
                        org.hamcrest.Matchers.containsString("includeSubDomains")))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'self'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("Referrer-Policy"));
    }
}
