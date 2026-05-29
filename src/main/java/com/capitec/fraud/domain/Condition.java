package com.capitec.fraud.domain;

import java.util.List;
import java.util.Map;

/**
 * Sealed AST for rule conditions. Three shapes:
 * <ul>
 *   <li>{@link Atomic} — invoke a single named predicate with args.</li>
 *   <li>{@link All}    — every child condition must hold (AND).</li>
 *   <li>{@link Any}    — at least one child must hold (OR).</li>
 * </ul>
 * Loaded from YAML via {@code engine.RuleLoader}; never constructed directly
 * from user input.
 */
public sealed interface Condition {

    record Atomic(String predicateId, Map<String, Object> args) implements Condition {
        public Atomic {
            if (predicateId == null || predicateId.isBlank()) {
                throw new IllegalArgumentException("predicateId required");
            }
            args = Map.copyOf(args == null ? Map.of() : args);
        }
    }

    record All(List<Condition> children) implements Condition {
        public All {
            if (children == null || children.isEmpty()) {
                throw new IllegalArgumentException("All requires at least one child");
            }
            children = List.copyOf(children);
        }
    }

    record Any(List<Condition> children) implements Condition {
        public Any {
            if (children == null || children.isEmpty()) {
                throw new IllegalArgumentException("Any requires at least one child");
            }
            children = List.copyOf(children);
        }
    }
}
