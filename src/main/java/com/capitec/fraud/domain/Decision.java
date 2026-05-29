package com.capitec.fraud.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Output of {@code RuleEngine.evaluate}. Persisted to {@code decisions} +
 * {@code decision_rules} tables. The {@code matchedRules} list is the full
 * explainability trace — every rule that fired, in priority order.
 */
public record Decision(
        UUID decisionId,
        UUID txId,
        String accountId,
        DecisionStatus status,
        double score,
        int ruleSetVersion,
        List<RuleResult> matchedRules,
        Instant evaluatedAt
) {
    public Decision {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be in [0,1], got " + score);
        }
        matchedRules = List.copyOf(matchedRules);
    }
}
