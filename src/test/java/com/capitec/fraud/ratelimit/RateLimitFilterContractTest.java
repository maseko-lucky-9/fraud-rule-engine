package com.capitec.fraud.ratelimit;

import com.capitec.fraud.TestcontainersConfiguration;
import com.capitec.fraud.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Bucket4j enforcement contract end-to-end: an authenticated subject
 * may fire N requests within its per-minute budget, but the (N+1)st is rejected
 * with 429, a {@code Retry-After} header and a {@code problem+json} body.
 *
 * <p>The budget is set very small for this test
 * ({@code app.rate-limit.requests-per-minute=5}) so the 6th request trips it
 * without dispatching hundreds of requests.
 *
 * <p><b>Flake history:</b> previously {@code @Disabled} because the first
 * request could race the Testcontainers Redis becoming reachable and surface a
 * transient 500 (the Lettuce {@code ProxyManager} is built lazily on first
 * use). {@link #awaitRateLimiterReady()} now gates the contract on the limiter
 * actually answering from its Redis-backed path, which removes the race.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.rate-limit.requests-per-minute=5")
class RateLimitFilterContractTest {

    @LocalServerPort int port;
    @Autowired JwtService jwt;

    // Never throw on 4xx/5xx: we assert on status codes directly. The contract
    // is "allowed vs 429", independent of the probed endpoint's own routing
    // (actuator binds to a separate management port, so /actuator/info is not
    // 200 on the main server port — but the SecurityFilterChain, and therefore
    // the rate limiter, still runs for it).
    private final RestTemplate http = newNonThrowingTemplate();

    @Test
    void allowsFiveRequestsRejectsTheSixth() {
        awaitRateLimiterReady();
        String token = jwt.issue("alice", Set.of("USER"));

        // The first five are within budget → must never be rate-limited.
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> res = call(token);
            assertThat(res.getStatusCode().value())
                    .as("request %d within budget must not be rate-limited", i + 1)
                    .isNotEqualTo(429);
        }

        // The sixth crosses the budget → 429 + Retry-After + problem+json.
        ResponseEntity<String> over = call(token);
        assertThat(over.getStatusCode().value()).as("6th request").isEqualTo(429);
        assertThat(over.getHeaders().getFirst("Retry-After")).isNotBlank();
        // Compare the media type ignoring any charset parameter the servlet
        // container appends (e.g. "...+json;charset=ISO-8859-1"): the contract
        // is the problem+json type, not a specific charset.
        MediaType contentType = over.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(contentType))
                .as("6th response content-type %s should be application/problem+json", contentType)
                .isTrue();
    }

    /**
     * Closes the flake's root cause. The Bucket4j Lettuce {@code ProxyManager}
     * is built lazily on the first request, which can race the Testcontainers
     * Redis becoming reachable and surface a transient 500. We poll a throwaway
     * subject ({@code ratelimit-warmup} — its own bucket, never alice's) until
     * the limiter answers from its Redis-backed path, proven by the
     * {@code X-RateLimit-Remaining} header that {@link RateLimitFilter} sets
     * only once counting is live. That header is present on both the allowed
     * and the 429 paths, so a single header-bearing response at any status
     * means "ready".
     */
    private void awaitRateLimiterReady() {
        String warm = jwt.issue("ratelimit-warmup", Set.of("USER"));
        Throwable last = null;
        for (int attempt = 0; attempt < 40; attempt++) {   // ~10s @ 250ms cadence
            try {
                ResponseEntity<String> r = call(warm);
                if (r.getHeaders().getFirst("X-RateLimit-Remaining") != null) {
                    return;
                }
            } catch (RuntimeException ex) {
                last = ex;   // Redis not reachable yet (e.g. connection refused) — retry.
            }
            sleep(250);
        }
        throw new IllegalStateException("rate limiter not ready after warmup", last);
    }

    private ResponseEntity<String> call(String token) {
        URI uri = URI.create("http://localhost:" + port + "/actuator/info");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return http.exchange(RequestEntity.get(uri).headers(h).build(), String.class);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting rate limiter", ie);
        }
    }

    private static RestTemplate newNonThrowingTemplate() {
        RestTemplate t = new RestTemplate();
        // Treat every response as non-error so 4xx/5xx are returned as
        // ResponseEntity (we assert on status) instead of throwing. Overriding
        // hasError alone is version-proof across the evolving ResponseErrorHandler
        // handleError(...) signatures.
        t.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) { return false; }
        });
        return t;
    }
}
