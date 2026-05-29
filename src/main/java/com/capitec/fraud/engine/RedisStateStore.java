package com.capitec.fraud.engine;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Redis-backed {@link StateStore}. Selected via {@code @Profile("redis-state")}.
 * Behind a Resilience4j circuit-breaker named {@code redis-state} — if Redis
 * is unreachable the circuit opens and {@link #recordAndCountWithin} returns
 * 0 (so the velocity predicate fails open). The breaker is wired in
 * {@code application.yml}; the fallback is intentional fail-open behaviour
 * because over-blocking is worse than under-blocking for a single
 * stateful predicate.
 *
 * <p>Key shapes:
 * <ul>
 *   <li>{@code velocity:{accountId}} — Redis ZSET, score=epochMs, member=txId.
 *       Eviction by {@code ZREMRANGEBYSCORE}; {@code ZCARD} gives the count.
 *       Key TTL is 1h (24 × the largest plausible velocity window).</li>
 *   <li>{@code devices:{accountId}} — Redis SET, member=deviceId. TTL 90 days.</li>
 * </ul>
 */
@Component
@Primary
@Profile("redis-state")
public class RedisStateStore implements StateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisStateStore.class);

    private static final Duration VELOCITY_KEY_TTL = Duration.ofHours(1);
    private static final Duration DEVICE_KEY_TTL = Duration.ofDays(90);

    private final StringRedisTemplate redis;
    private final RedisScript<Long> velocityScript;
    private final Counter velocityFailOpenCounter;
    private final Counter deviceFailOpenCounter;
    private final Counter luaFailureCounter;

    public RedisStateStore(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        // Lua atomicity: replaces the previous three-call ZREMRANGE+ZADD+ZCARD
        // sequence which could interleave under millisecond-concurrent writes
        // to the same account, causing a double-count miss. The script runs
        // to completion before any other command executes against the same
        // shard, eliminating the race.
        var script = new DefaultRedisScript<Long>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/velocity.lua")));
        script.setResultType(Long.class);
        this.velocityScript = script;
        // Domain-named counter sits alongside Resilience4j's auto-exported
        // resilience4j_circuitbreaker_calls_total{name=redis-state,kind=failed}
        // — kept because operators grep this name (referenced in ADR-0006 +
        // failure-modes.md), and the tag distinguishes velocity vs device
        // fail-opens which the Resilience4j metric does not.
        this.velocityFailOpenCounter = Counter.builder("predicate_state_unavailable_total")
                .description("Count of stateful-predicate calls that fail-open because the StateStore (Redis) was unavailable.")
                .tag("operation", "velocity")
                .register(meters);
        this.deviceFailOpenCounter = Counter.builder("predicate_state_unavailable_total")
                .description("Count of stateful-predicate calls that fail-open because the StateStore (Redis) was unavailable.")
                .tag("operation", "device")
                .register(meters);
        // Lua-specific failure surface — distinguishes script errors (NOSCRIPT,
        // syntax, Lua runtime) from generic Redis unavailability so operators
        // can tell "Redis is down" from "we shipped a bad script".
        this.luaFailureCounter = Counter.builder("redis_lua_failure_total")
                .description("Lua-script execution failures on RedisStateStore (script error or shard failure).")
                .register(meters);
    }

    @Override
    @CircuitBreaker(name = "redis-state", fallbackMethod = "velocityFallback")
    public int recordAndCountWithin(String accountId, String txId, Instant at, Duration window) {
        String key = "velocity:" + accountId;
        long now = at.toEpochMilli();
        long floor = now - window.toMillis();
        try {
            Long count = redis.execute(velocityScript,
                    List.of(key),
                    Long.toString(now),
                    Long.toString(floor),
                    txId,
                    Long.toString(VELOCITY_KEY_TTL.toSeconds()));
            return count == null ? 0 : count.intValue();
        } catch (DataAccessException scriptError) {
            // A Lua-side error (syntax, runtime, NOSCRIPT after a Redis
            // restart) doesn't surface as a Resilience4j-counted failure,
            // so we fail-open here directly and record the dedicated
            // counter. Consistent semantic with the velocityFallback
            // (under-block beats over-block for one stateful predicate).
            luaFailureCounter.increment();
            log.warn("redis-state Lua script failed for velocity {} — returning 0 (fail-open). cause={}",
                    accountId, scriptError.toString());
            return 0;
        }
    }

    @Override
    @CircuitBreaker(name = "redis-state", fallbackMethod = "deviceFallback")
    public boolean isNewDevice(String accountId, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return false;
        String key = "devices:" + accountId;
        Long added = redis.opsForSet().add(key, deviceId);
        redis.expire(key, DEVICE_KEY_TTL);
        return added != null && added > 0;
    }

    @SuppressWarnings("unused")
    private int velocityFallback(String accountId, String txId, Instant at, Duration window, Throwable t) {
        velocityFailOpenCounter.increment();
        log.warn("redis-state circuit open / call failed for velocity {} — returning 0 (fail-open). cause={}",
                accountId, t.toString());
        return 0;
    }

    @SuppressWarnings("unused")
    private boolean deviceFallback(String accountId, String deviceId, Throwable t) {
        deviceFailOpenCounter.increment();
        log.warn("redis-state circuit open / call failed for devices {} — returning false (fail-open). cause={}",
                accountId, t.toString());
        return false;
    }

    /** Test helper — not used in production paths. */
    Set<String> debugMembers(String accountId) {
        return redis.opsForSet().members("devices:" + accountId);
    }
}
