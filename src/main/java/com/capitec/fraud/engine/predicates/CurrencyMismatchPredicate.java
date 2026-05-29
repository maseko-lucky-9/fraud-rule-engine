package com.capitec.fraud.engine.predicates;

import com.capitec.fraud.engine.Predicate;
import com.capitec.fraud.engine.PredicateContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * True iff the transaction currency differs from a configured expected currency.
 * Args: {@code expected: ISO-4217 (e.g. ZAR)}.
 */
@Component
public class CurrencyMismatchPredicate implements Predicate {

    @Override
    public String id() {
        return "currencyMismatch";
    }

    @Override
    public boolean test(PredicateContext ctx, Map<String, Object> args) {
        String expected = PredicateArgs.requireString(args, "expected");
        return !expected.equals(ctx.transaction().currency());
    }
}
