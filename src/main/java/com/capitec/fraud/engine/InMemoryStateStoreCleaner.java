package com.capitec.fraud.engine;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;

/**
 * Scheduled eviction for {@link InMemoryStateStore}'s velocity and device
 * maps. Without cleanup, long-lived processes accrue per-account entries
 * indefinitely — heap bloat is gradual but unbounded, especially the
 * device set which has no per-event TTL (a deviceId once seen stays
 * forever).
 *
 * <p>This cleaner only activates when {@code redis-state} profile is
 * NOT in play (the in-memory store is the active {@link StateStore}).
 * Redis already has explicit TTLs and doesn't need this.
 *
 * <p>Eviction policy: walk both maps every
 * {@code app.engine.state-cleanup-interval-ms} (default 5 min); drop any
 * account whose velocity deque is empty (already drained by lazy
 * eviction inside the read path) AND whose device set is empty. This is
 * deliberately conservative — we don't try to estimate a TTL for
 * still-populated maps, just collect the accounts the live path has
 * already cleared.
 */
@Component
@Profile("!redis-state")
public class InMemoryStateStoreCleaner {

    private static final Logger log = LoggerFactory.getLogger(InMemoryStateStoreCleaner.class);

    private final InMemoryStateStore store;
    private final long intervalMs;

    public InMemoryStateStoreCleaner(InMemoryStateStore store,
                                     MeterRegistry meters,
                                     @Value("${app.engine.state-cleanup-interval-ms:300000}") long intervalMs) {
        this.store = store;
        this.intervalMs = intervalMs;
        // Gauges so the dashboard can chart heap pressure on the in-memory store.
        meters.gauge("instate_velocity_accounts", store, InMemoryStateStore::velocitySize);
        meters.gauge("instate_device_accounts", store, InMemoryStateStore::deviceSize);
    }

    @Scheduled(fixedDelayString = "${app.engine.state-cleanup-interval-ms:300000}")
    public void cleanup() {
        int evicted = store.evictEmptyAccounts();
        if (evicted > 0) {
            log.debug("InMemoryStateStoreCleaner: evicted {} empty account entries (interval {} ms)",
                    evicted, intervalMs);
        }
    }
}
