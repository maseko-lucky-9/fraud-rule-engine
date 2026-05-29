package com.capitec.fraud.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DecisionResponse(
        UUID decisionId,
        UUID txId,
        String status,
        double score,
        int ruleSetVersion,
        List<MatchedRule> matchedRules,
        Instant evaluatedAt
) {
    public record MatchedRule(String ruleId, int priority, String reason) {}
}
