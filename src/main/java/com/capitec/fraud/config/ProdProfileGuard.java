package com.capitec.fraud.config;

import com.capitec.fraud.engine.InMemoryStateStore;
import com.capitec.fraud.engine.StateStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Refuses to boot the application when the {@code prod} profile is active
 * and the only available {@link StateStore} bean is
 * {@link InMemoryStateStore}. Horizontal scale-out requires a shared
 * Redis-backed state because the velocity and device-fingerprint
 * predicates are stateful — each replica would otherwise see a
 * partial-account history, leading to under-blocking on bursty patterns.
 *
 * <p>The {@code redis-state} profile activates {@code RedisStateStore} as
 * {@code @Primary}, satisfying the guard. Operators who deliberately want
 * single-replica deployments can disable the guard by NOT activating the
 * {@code prod} profile, or by adding their own {@code StateStore} bean
 * that isn't {@code InMemoryStateStore}.
 */
@Configuration
public class ProdProfileGuard {

    private static final Logger log = LoggerFactory.getLogger(ProdProfileGuard.class);

    private final Environment env;
    private final StateStore stateStore;

    public ProdProfileGuard(Environment env, StateStore stateStore) {
        this.env = env;
        this.stateStore = stateStore;
    }

    @PostConstruct
    void check() {
        boolean prodActive = env.matchesProfiles("prod");
        boolean inMemoryActive = stateStore instanceof InMemoryStateStore;
        if (prodActive && inMemoryActive) {
            throw new IllegalStateException(
                    "Refusing to start: profile=prod requires a distributed StateStore, "
                            + "but only InMemoryStateStore is active. "
                            + "Add 'redis-state' to spring.profiles.active (e.g. "
                            + "SPRING_PROFILES_ACTIVE=prod,redis-state).");
        }
        if (prodActive) {
            log.info("ProdProfileGuard: prod profile + non-in-memory StateStore ({}). OK.",
                    stateStore.getClass().getSimpleName());
        }
    }
}
