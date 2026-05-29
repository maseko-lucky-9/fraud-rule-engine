package com.capitec.fraud.engine;

import java.util.Map;

/**
 * One named predicate. Spring beans implement this; the registry maps {@link #id()}
 * to the bean. Args are extracted from the rule YAML at each invocation, so a
 * single bean serves many rule instances (e.g. {@code amountAbove(10000)} and
 * {@code amountAbove(5000)} both invoke the same {@code AmountAbovePredicate}).
 */
public interface Predicate {

    String id();

    boolean test(PredicateContext ctx, Map<String, Object> args);
}
