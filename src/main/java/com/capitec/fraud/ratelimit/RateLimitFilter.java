package com.capitec.fraud.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Per-subject rate limit. Default budget is 100 requests / 60 seconds —
 * configurable via {@code app.rate-limit.requests-per-minute}. The bucket
 * lives in Redis so all API replicas share the same counter; on exhaustion,
 * the response is 429 with a {@code Retry-After} header.
 *
 * <p>The bucket key is the authenticated JWT {@code sub} (subject) so an
 * authenticated burst from one identity can't impact another. Anonymous
 * requests (those reaching here before the JWT filter runs, e.g. for
 * pre-auth paths) bypass the limiter — Spring Security blocks them with 401.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)  // after auth, before controllers
public class RateLimitFilter extends OncePerRequestFilter {

    private final DataRedisConnectionDetails redisConnectionDetails;
    private final BucketConfiguration cfg;
    private final Counter exceededCounter;
    // Lazily constructed on first request so the constructor doesn't block
    // on Redis at boot time. Without lazy init, a test context that brings
    // up Redis via Testcontainers AFTER the Spring context starts (the
    // canonical timing in the Spring Boot 4 test framework) sees the
    // filter constructor try to connect to localhost:6379 before
    // @ServiceConnection has published the container's host/port.
    private volatile ProxyManager<String> bucketManager;

    public RateLimitFilter(DataRedisConnectionDetails redisConnectionDetails,
                           @Value("${app.rate-limit.requests-per-minute:100}") int rpm,
                           MeterRegistry metrics) {
        // DataRedisConnectionDetails is what @ServiceConnection populates with the
        // Testcontainers Redis host/port. Spring Data Redis uses it; pulling
        // the same bean here means the rate limiter shares the live address
        // resolution path instead of reading raw spring.data.redis.host
        // properties that @ServiceConnection doesn't touch.
        this.redisConnectionDetails = redisConnectionDetails;
        this.cfg = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(rpm).refillIntervally(rpm, Duration.ofMinutes(1)).build())
                .build();
        this.exceededCounter = Counter.builder("rate_limit_exceeded_total")
                .description("Requests rejected by the rate limiter")
                .register(metrics);
    }

    private ProxyManager<String> bucketManager() {
        ProxyManager<String> mgr = bucketManager;
        if (mgr == null) {
            synchronized (this) {
                mgr = bucketManager;
                if (mgr == null) {
                    var standalone = redisConnectionDetails.getStandalone();
                    String redisHost = standalone.getHost();
                    int redisPort = standalone.getPort();
                    RedisClient client = RedisClient.create("redis://" + redisHost + ":" + redisPort);
                    StatefulRedisConnection<String, byte[]> connection =
                            client.connect(io.lettuce.core.codec.RedisCodec.of(
                                    io.lettuce.core.codec.StringCodec.UTF8,
                                    io.lettuce.core.codec.ByteArrayCodec.INSTANCE));
                    mgr = LettuceBasedProxyManager.builderFor(connection).build();
                    bucketManager = mgr;
                }
            }
        }
        return mgr;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            chain.doFilter(req, res);
            return;
        }
        // Skip rate limiting for the SERVICE role (Prometheus scrape, admin) — internal use.
        boolean isService = auth.getAuthorities().stream()
                .anyMatch(g -> "ROLE_SERVICE".equals(g.getAuthority()));
        if (isService) {
            chain.doFilter(req, res);
            return;
        }

        String key = "rl:" + auth.getName();
        var bucket = bucketManager().builder().build(key, () -> cfg);
        var probe = bucket.tryConsumeAndReturnRemaining(1);
        res.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
        if (probe.isConsumed()) {
            chain.doFilter(req, res);
        } else {
            exceededCounter.increment();
            long retryAfterSec = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000);
            res.setHeader("Retry-After", String.valueOf(retryAfterSec));
            res.setStatus(429);
            res.setContentType("application/problem+json");
            res.getWriter().write(String.format(
                    "{\"type\":\"https://fraud-engine.example/problems/rate-limited\","
                            + "\"title\":\"Too Many Requests\","
                            + "\"status\":429,"
                            + "\"detail\":\"Rate limit of %d req/min exceeded for subject %s.\","
                            + "\"retryAfterSeconds\":%d}",
                    cfg.getBandwidths()[0].getCapacity(), auth.getName(), retryAfterSec));
        }
    }
}
