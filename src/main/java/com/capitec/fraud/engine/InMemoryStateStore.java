package com.capitec.fraud.engine;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Day-2 in-memory {@link StateStore}. Thread-safe via explicit synchronization
 * on per-account deques. Day 3 ships a Redis implementation that takes
 * {@code @Primary} when its profile is active.
 *
 * <p>Velocity state is a per-account {@link Deque} of event-timestamps (millis)
 * in arrival order. Crucially we use a deque (not a {@code Set<Long>}) so two
 * concurrent transactions arriving in the same millisecond both count — a
 * {@code Set<Long>} would silently deduplicate and let bursty card-testing
 * patterns slip past the {@code VELOCITY_BURST} rule.
 *
 * <p>Old entries are evicted lazily at the front of the deque on every access.
 */
@Component
@Primary
@Profile("!redis-state")
public class InMemoryStateStore implements StateStore {

    private final Map<String, Deque<Long>> velocityByAccount = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> devicesByAccount = new ConcurrentHashMap<>();

    @Override
    public int recordAndCountWithin(String accountId, String txId, Instant at, Duration window) {
        long now = at.toEpochMilli();
        long floor = now - window.toMillis();
        Deque<Long> deque = velocityByAccount.computeIfAbsent(accountId, k -> new ArrayDeque<>());
        synchronized (deque) {
            // Evict entries older than or equal to floor.
            Iterator<Long> it = deque.iterator();
            while (it.hasNext()) {
                if (it.next() <= floor) {
                    it.remove();
                } else {
                    break; // deque is ordered by arrival ≈ timestamp asc
                }
            }
            deque.addLast(now);
            return deque.size();
        }
    }

    @Override
    public boolean isNewDevice(String accountId, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        Set<String> devices = devicesByAccount.computeIfAbsent(
                accountId, k -> ConcurrentHashMap.newKeySet());
        return devices.add(deviceId);
    }

    // -----------------------------------------------------------------
    // Operational surface used by InMemoryStateStoreCleaner and metrics.
    // Visibility is package-private so it isn't part of the StateStore
    // interface contract — Redis-backed implementations don't need it.

    int velocitySize() { return velocityByAccount.size(); }

    int deviceSize() { return devicesByAccount.size(); }

    /**
     * Drops accounts whose velocity deque AND device set are both empty
     * after the live read path's lazy eviction. Conservative: never
     * touches accounts with active state. Returns the number of evicted
     * entries (the sum across both maps).
     */
    int evictEmptyAccounts() {
        int evicted = 0;
        Iterator<Map.Entry<String, Deque<Long>>> vIt = velocityByAccount.entrySet().iterator();
        while (vIt.hasNext()) {
            var e = vIt.next();
            Deque<Long> deque = e.getValue();
            synchronized (deque) {
                if (deque.isEmpty()) {
                    vIt.remove();
                    evicted++;
                }
            }
        }
        Iterator<Map.Entry<String, Set<String>>> dIt = devicesByAccount.entrySet().iterator();
        while (dIt.hasNext()) {
            var e = dIt.next();
            if (e.getValue().isEmpty()) {
                dIt.remove();
                evicted++;
            }
        }
        return evicted;
    }
}
