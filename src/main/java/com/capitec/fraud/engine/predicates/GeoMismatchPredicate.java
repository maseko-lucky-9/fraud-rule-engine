package com.capitec.fraud.engine.predicates;

import com.capitec.fraud.engine.Predicate;
import com.capitec.fraud.engine.PredicateContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * True iff {@code transaction.country != transaction.ipCountry}. No args.
 * The "cross-border" detection signal.
 */
@Component
public class GeoMismatchPredicate implements Predicate {

    @Override
    public String id() {
        return "geoMismatch";
    }

    @Override
    public boolean test(PredicateContext ctx, Map<String, Object> args) {
        var tx = ctx.transaction();
        return !tx.country().equals(tx.ipCountry());
    }
}
