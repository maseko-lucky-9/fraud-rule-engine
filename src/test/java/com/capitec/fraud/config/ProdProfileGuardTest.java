package com.capitec.fraud.config;

import com.capitec.fraud.engine.InMemoryStateStore;
import com.capitec.fraud.engine.RedisStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-level proof of the prod-profile guard. The full Spring boot smoke
 * (env SPRING_PROFILES_ACTIVE=prod, no redis-state -> startup failure)
 * sits in the CI integration run; this test pins the rule itself in
 * isolation so a regression in the env-matching logic surfaces in
 * milliseconds.
 */
class ProdProfileGuardTest {

    @Test
    void refusesProdWithInMemoryStore() {
        Environment env = mock(Environment.class);
        when(env.matchesProfiles("prod")).thenReturn(true);
        InMemoryStateStore inMemory = new InMemoryStateStore();
        ProdProfileGuard guard = new ProdProfileGuard(env, inMemory);

        assertThatThrownBy(guard::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("InMemoryStateStore")
                .hasMessageContaining("redis-state");
    }

    @Test
    void allowsProdWithRedisStore() {
        Environment env = mock(Environment.class);
        when(env.matchesProfiles("prod")).thenReturn(true);
        RedisStateStore redis = mock(RedisStateStore.class);
        ProdProfileGuard guard = new ProdProfileGuard(env, redis);

        // No throw expected.
        guard.check();
        assertThat(guard).isNotNull();
    }

    @Test
    void allowsNonProdWithInMemoryStore() {
        Environment env = mock(Environment.class);
        when(env.matchesProfiles("prod")).thenReturn(false);
        InMemoryStateStore inMemory = new InMemoryStateStore();
        ProdProfileGuard guard = new ProdProfileGuard(env, inMemory);

        guard.check();
        assertThat(guard).isNotNull();
    }
}
