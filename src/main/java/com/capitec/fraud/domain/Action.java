package com.capitec.fraud.domain;

/**
 * What a rule does when it matches. Score in [0,1]; the engine takes the
 * MAX of all matched-rule scores. The resulting status is the strictest among
 * matched rules: BLOCK > REVIEW > APPROVE.
 */
public record Action(DecisionStatus flag, double score, String reason) {
    public Action {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be in [0,1], got " + score);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must be non-blank");
        }
    }
}
