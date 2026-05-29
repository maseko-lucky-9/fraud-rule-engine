package com.capitec.fraud.engine;

import java.util.List;

/**
 * Thrown by {@link RuleLoader} when a YAML document fails JSON-Schema validation
 * or domain conversion. The {@code errors} list is surfaced as 422 problem-details
 * to the operator hitting {@code POST /admin/rules/reload}.
 */
public class RuleValidationException extends RuntimeException {

    private final List<String> errors;

    public RuleValidationException(List<String> errors) {
        super("RuleSet validation failed: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
