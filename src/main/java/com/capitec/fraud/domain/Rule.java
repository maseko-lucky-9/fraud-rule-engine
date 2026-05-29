package com.capitec.fraud.domain;

/**
 * One rule, after YAML→domain conversion. Pure POJO — no framework deps.
 * The {@code condition} tree is walked by {@code RuleEngine}; the {@code action}
 * is applied to the {@code Decision} when the condition is true.
 */
public record Rule(
        String id,
        int priority,
        Severity severity,
        boolean shortCircuit,
        Condition condition,
        Action action
) {
    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }
}
