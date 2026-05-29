package com.capitec.fraud.engine.predicates;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Typed extraction helpers for {@code Predicate} args. YAML deserialises
 * numbers as Integer, Long, Double, or BigDecimal depending on form; these
 * helpers normalise. Throws {@link IllegalArgumentException} with a clear
 * message if a required arg is missing or the wrong type — caught by the
 * RuleLoader and surfaced as a 422 to the operator.
 */
public final class PredicateArgs {

    private PredicateArgs() {}

    public static String requireString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing required arg: " + key);
        }
        if (!(v instanceof CharSequence)) {
            throw new IllegalArgumentException("arg " + key + " must be string, got " + v.getClass().getSimpleName());
        }
        return v.toString();
    }

    public static int requireInt(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing required arg: " + key);
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        throw new IllegalArgumentException("arg " + key + " must be integer, got " + v.getClass().getSimpleName());
    }

    public static BigDecimal requireBigDecimal(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing required arg: " + key);
        }
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof CharSequence cs) return new BigDecimal(cs.toString());
        throw new IllegalArgumentException("arg " + key + " must be number, got " + v.getClass().getSimpleName());
    }

    public static List<?> requireList(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing required arg: " + key);
        }
        if (!(v instanceof List<?> list)) {
            throw new IllegalArgumentException("arg " + key + " must be list, got " + v.getClass().getSimpleName());
        }
        return list;
    }
}
