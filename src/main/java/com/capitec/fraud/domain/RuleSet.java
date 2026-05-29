package com.capitec.fraud.domain;

import java.util.Comparator;
import java.util.List;

/**
 * A versioned, immutable set of rules. The {@code version} integer is
 * persisted with every Decision in {@code rule_set_version} — making audit
 * deterministic ("which rule-set version produced this decision?").
 *
 * <p>Rules are sorted by {@code priority} descending, with {@code id} ascending
 * as the tiebreaker. The tiebreaker is essential: without it, two rules with
 * the same priority appear in arbitrary order and a {@code shortCircuit:true}
 * rule could either suppress or yield to a sibling depending on JVM hashing —
 * non-deterministic decisions are unacceptable in banking.
 */
public record RuleSet(String id, int version, List<Rule> rules) {

    public RuleSet {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("RuleSet id required");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("RuleSet version must be positive");
        }
        rules = rules.stream()
                .sorted(Comparator.comparingInt(Rule::priority).reversed()
                        .thenComparing(Rule::id))
                .toList();
    }
}
