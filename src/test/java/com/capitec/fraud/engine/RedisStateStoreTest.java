package com.capitec.fraud.engine;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisStateStore} using a mocked
 * {@link StringRedisTemplate}. Fallback behaviour (circuit-breaker open /
 * Redis unreachable) is exercised by invoking the fallback methods
 * reflectively — they're package-private for this purpose.
 */
class RedisStateStoreTest {

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zops;
    private SetOperations<String, String> sops;
    private SimpleMeterRegistry meters;
    private RedisStateStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        zops = mock(ZSetOperations.class);
        sops = mock(SetOperations.class);
        meters = new SimpleMeterRegistry();
        when(redis.opsForZSet()).thenReturn(zops);
        when(redis.opsForSet()).thenReturn(sops);
        store = new RedisStateStore(redis, meters);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordAndCountWithin_executes_atomic_lua_script_and_returns_count() {
        // Post-overhaul: the eviction + add + count + expire sequence is a
        // single Lua EVAL so concurrent calls on the same shard can't
        // interleave. The mock returns the script's post-write ZCARD.
        when(redis.execute(any(RedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString())).thenReturn(3L);

        Instant at = Instant.parse("2026-05-19T12:00:00Z");
        int count = store.recordAndCountWithin("ACC-R", "tx-1", at, Duration.ofSeconds(60));

        assertThat(count).isEqualTo(3);
        verify(redis).execute(any(RedisScript.class),
                org.mockito.ArgumentMatchers.eq(List.of("velocity:ACC-R")),
                org.mockito.ArgumentMatchers.eq(Long.toString(at.toEpochMilli())),
                org.mockito.ArgumentMatchers.eq(Long.toString(at.toEpochMilli() - 60_000)),
                org.mockito.ArgumentMatchers.eq("tx-1"),
                org.mockito.ArgumentMatchers.eq(Long.toString(Duration.ofHours(1).toSeconds())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordAndCountWithin_zero_when_script_returns_null() {
        when(redis.execute(any(RedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        int count = store.recordAndCountWithin("ACC-N", "tx-1",
                Instant.parse("2026-05-19T12:00:00Z"), Duration.ofSeconds(60));
        assertThat(count).isZero();
    }

    @Test
    void isNewDevice_true_when_set_add_returns_one() {
        when(sops.add(anyString(), any(String[].class))).thenReturn(1L);
        assertThat(store.isNewDevice("ACC-D", "iphone-123")).isTrue();
    }

    @Test
    void isNewDevice_false_when_set_add_returns_zero() {
        when(sops.add(anyString(), any(String[].class))).thenReturn(0L);
        assertThat(store.isNewDevice("ACC-D", "iphone-123")).isFalse();
    }

    @Test
    void isNewDevice_false_on_blank_device_id_does_not_call_redis() {
        assertThat(store.isNewDevice("ACC-D", "")).isFalse();
        assertThat(store.isNewDevice("ACC-D", null)).isFalse();
        // Critically, no call to opsForSet().add()
        verify(sops, org.mockito.Mockito.never()).add(anyString(), any(String[].class));
    }

    @Test
    void fallbacks_return_safe_defaults_when_invoked() throws Exception {
        var velMethod = RedisStateStore.class.getDeclaredMethod(
                "velocityFallback", String.class, String.class, Instant.class, Duration.class, Throwable.class);
        velMethod.setAccessible(true);
        Object velResult = velMethod.invoke(store, "ACC", "tx", Instant.now(),
                Duration.ofSeconds(60), new RuntimeException("boom"));
        assertThat(velResult).isEqualTo(0);

        var devMethod = RedisStateStore.class.getDeclaredMethod(
                "deviceFallback", String.class, String.class, Throwable.class);
        devMethod.setAccessible(true);
        Object devResult = devMethod.invoke(store, "ACC", "iphone", new RuntimeException("boom"));
        assertThat(devResult).isEqualTo(false);

        // Day-7: predicate_state_unavailable_total bumps on fail-open
        // ADR-0006 §Negative + failure-modes.md #2/#18 reference this metric.
        assertThat(meters.counter("predicate_state_unavailable_total", "operation", "velocity").count())
                .as("velocity fail-open counter")
                .isEqualTo(1.0);
        assertThat(meters.counter("predicate_state_unavailable_total", "operation", "device").count())
                .as("device fail-open counter")
                .isEqualTo(1.0);
    }

    @SuppressWarnings("unchecked")
    private static <T> T eqAny(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
