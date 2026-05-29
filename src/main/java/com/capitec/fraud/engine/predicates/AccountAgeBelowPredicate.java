package com.capitec.fraud.engine.predicates;

import com.capitec.fraud.engine.Predicate;
import com.capitec.fraud.engine.PredicateContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * True iff {@code transaction.accountAgeDays < days}.
 * Args: {@code days: integer}.
 */
@Component
public class AccountAgeBelowPredicate implements Predicate {

    @Override
    public String id() {
        return "accountAgeBelow";
    }

    @Override
    public boolean test(PredicateContext ctx, Map<String, Object> args) {
        int days = PredicateArgs.requireInt(args, "days");
        return ctx.transaction().accountAgeDays() < days;
    }
}
