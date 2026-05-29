package com.capitec.fraud.engine;

import com.capitec.fraud.domain.Transaction;

import java.time.Clock;

/**
 * Per-evaluation context handed to every {@link Predicate}. Encapsulates the
 * transaction under evaluation, a {@link StateStore} for stateful predicates
 * (velocity, device-fingerprint), and a {@link Clock} so time-based predicates
 * stay testable.
 */
public record PredicateContext(Transaction transaction, StateStore state, Clock clock) {
}
