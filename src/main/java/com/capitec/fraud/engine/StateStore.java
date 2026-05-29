package com.capitec.fraud.engine;

import java.time.Duration;
import java.time.Instant;

/**
 * Stateful predicate backing. Day 2 ships an in-memory implementation;
 * Day 3 swaps in a Redis implementation behind the same interface.
 *
 * <p>The interface is intentionally narrow: just the operations the velocity
 * and device-fingerprint predicates need. If a future predicate needs richer
 * state semantics, add a method rather than leaking a generic key-value API.
 */
public interface StateStore {

    /**
     * Record a transaction event for the given account and return the count of
     * events recorded for that account in the trailing {@code window}.
     * Implementations must be safe for concurrent callers from different
     * threads.
     */
    int recordAndCountWithin(String accountId, String txId, Instant at, Duration window);

    /**
     * Returns true if {@code deviceId} has never been seen for {@code accountId},
     * AND records it as seen so subsequent calls return false.
     * If {@code deviceId} is null/blank, returns false (cannot fingerprint).
     */
    boolean isNewDevice(String accountId, String deviceId);
}
