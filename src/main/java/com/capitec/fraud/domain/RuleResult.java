package com.capitec.fraud.domain;

/**
 * A single rule that matched the transaction. Captures the rule's id, the
 * priority it fired at (useful for ordering / audit), and the human-readable
 * reason from the rule's {@code action.reason} field. Persisted to
 * {@code decision_rules}.
 */
public record RuleResult(String ruleId, int priority, String reason) {
}
