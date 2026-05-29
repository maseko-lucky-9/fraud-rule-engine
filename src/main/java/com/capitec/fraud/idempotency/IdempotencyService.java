package com.capitec.fraud.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed idempotency cache for the {@code Idempotency-Key} header.
 *
 * <p>Key shape: {@code idem:{subject}:{idempotencyKey}}. Binding the cache
 * entry to the JWT subject prevents an attacker from replaying another user's
 * key. The cached value is a JSON envelope of the original response body +
 * the SHA-256 hash of the original request body.
 *
 * <p>Collision semantics:
 * <ul>
 *   <li>No cache entry → caller proceeds, then caches the response.</li>
 *   <li>Cache hit, body hash matches → caller returns the cached response.</li>
 *   <li>Cache hit, body hash differs → caller returns 409 Conflict
 *       (handled by the controller; this service surfaces the conflict).</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;

    public IdempotencyService(StringRedisTemplate redis,
                              ObjectMapper json,
                              MeterRegistry metrics,
                              @Value("${app.idempotency.ttl-hours:24}") long ttlHours) {
        this.redis = redis;
        this.json = json;
        this.ttl = Duration.ofHours(ttlHours);
        Gauge.builder("idempotency_cache_size", () -> safeCount(redis))
                .description("Approximate count of idempotency cache entries")
                .register(metrics);
    }

    /** Lookup. Empty → cache miss. */
    public Optional<CachedResponse> lookup(String subject, String key) {
        String raw = redis.opsForValue().get(redisKey(subject, key));
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(json.readValue(raw, CachedResponse.class));
        } catch (JsonProcessingException e) {
            log.warn("idempotency: malformed cache entry for {} — evicting", key);
            redis.delete(redisKey(subject, key));
            return Optional.empty();
        }
    }

    public void store(String subject, String key, String bodyHash, Object response) {
        try {
            String envelope = json.writeValueAsString(new CachedResponse(bodyHash, response));
            redis.opsForValue().set(redisKey(subject, key), envelope, ttl);
        } catch (JsonProcessingException e) {
            log.warn("idempotency: failed to serialize response for cache — skipping", e);
        }
    }

    public static String hashBody(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(body);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static byte[] toBytes(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    private static String redisKey(String subject, String key) {
        return "idem:" + (subject == null ? "anon" : subject) + ":" + key;
    }

    private static long safeCount(StringRedisTemplate redis) {
        try {
            Long n = redis.execute((org.springframework.data.redis.core.RedisCallback<Long>)
                    cx -> cx.serverCommands().dbSize());
            return n == null ? 0L : n;
        } catch (Exception ignored) {
            return -1;
        }
    }

    public record CachedResponse(String bodyHash, Object response) {}
}
