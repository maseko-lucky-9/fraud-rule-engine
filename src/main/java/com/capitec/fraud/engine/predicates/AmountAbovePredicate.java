package com.capitec.fraud.engine.predicates;

import com.capitec.fraud.engine.Predicate;
import com.capitec.fraud.engine.PredicateContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * True iff {@code transaction.amount > value} AND {@code transaction.currency == currency}.
 * Args: {@code value: number, currency: ISO-4217}.
 */
@Component
public class AmountAbovePredicate implements Predicate {

    @Override
    public String id() {
        return "amountAbove";
    }

    @Override
    public boolean test(PredicateContext ctx, Map<String, Object> args) {
        BigDecimal threshold = PredicateArgs.requireBigDecimal(args, "value");
        String currency = PredicateArgs.requireString(args, "currency");
        var tx = ctx.transaction();
        return currency.equals(tx.currency()) && tx.amount().compareTo(threshold) > 0;
    }
}
