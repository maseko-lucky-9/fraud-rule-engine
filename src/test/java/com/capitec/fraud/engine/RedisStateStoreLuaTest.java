package com.capitec.fraud.engine;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level proof of the fail-open contract on the Lua-script path. The
 * full integration semantics (real Redis, real script atomicity) sit in the
 * Testcontainers-backed RedisStateStoreTest; this test sits in the unit
 * loop so a regression in the fallback or the counter wiring surfaces in
 * seconds.
 *
 * <p>Why this matters: a script-side error (syntax bug we shipped, NOSCRIPT
 * after a Redis restart wiping the script cache, Lua runtime error) is NOT
 * counted as a Resilience4j circuit-breaker failure — the call returned
 * normally from the breaker's perspective. Without an explicit catch +
 * fail-open in the body, the exception would bubble out and surface as a
 * 500 on the request, which is strictly worse than the under-block we get
 * by returning a count of 0.
 */
class RedisStateStoreLuaTest {

    @Test
    @SuppressWarnings("unchecked")
    void scriptFailureReturnsZeroAndIncrementsCounter() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("simulated Lua runtime error"));

        MeterRegistry metrics = new SimpleMeterRegistry();
        RedisStateStore store = new RedisStateStore(redis, metrics);

        int count = store.recordAndCountWithin(
                "ACC-001",
                "tx-1",
                Instant.parse("2026-05-19T12:00:00Z"),
                Duration.ofMinutes(1));

        assertThat(count).as("fail-open returns 0 on script error").isZero();
        assertThat(metrics.counter("redis_lua_failure_total").count())
                .as("redis_lua_failure_total ticks")
                .isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void scriptSuccessReturnsCount() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(7L);

        MeterRegistry metrics = new SimpleMeterRegistry();
        RedisStateStore store = new RedisStateStore(redis, metrics);

        int count = store.recordAndCountWithin(
                "ACC-001",
                "tx-1",
                Instant.parse("2026-05-19T12:00:00Z"),
                Duration.ofMinutes(1));

        assertThat(count).isEqualTo(7);
        assertThat(metrics.counter("redis_lua_failure_total").count()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullScriptReturnIsTreatedAsZero() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        MeterRegistry metrics = new SimpleMeterRegistry();
        RedisStateStore store = new RedisStateStore(redis, metrics);

        int count = store.recordAndCountWithin(
                "ACC-001",
                "tx-1",
                Instant.parse("2026-05-19T12:00:00Z"),
                Duration.ofMinutes(1));

        assertThat(count).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void scriptInvokedWithCorrectArgsAndKeyShape() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        MeterRegistry metrics = new SimpleMeterRegistry();
        RedisStateStore store = new RedisStateStore(redis, metrics);

        Instant at = Instant.parse("2026-05-19T12:00:00Z");
        Duration window = Duration.ofMinutes(1);
        store.recordAndCountWithin("ACC-001", "tx-1", at, window);

        verify(redis).execute(any(RedisScript.class),
                org.mockito.ArgumentMatchers.eq(List.of("velocity:ACC-001")),
                org.mockito.ArgumentMatchers.eq(Long.toString(at.toEpochMilli())),
                org.mockito.ArgumentMatchers.eq(Long.toString(at.toEpochMilli() - window.toMillis())),
                org.mockito.ArgumentMatchers.eq("tx-1"),
                org.mockito.ArgumentMatchers.eq(Long.toString(Duration.ofHours(1).toSeconds())));
    }
}
