package com.capitec.fraud.engine.predicates;

import com.capitec.fraud.engine.Predicate;
import com.capitec.fraud.engine.PredicateContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * True iff the count of recent transactions for this account (within
 * {@code windowSeconds}) is strictly greater than {@code count}, AFTER
 * recording the current transaction.
 * Args: {@code count: int, windowSeconds: int}.
 */
@Component
public class VelocityPredicate implements Predicate {

    @Override
    public String id() {
        return "velocity";
    }

    @Override
    public boolean test(PredicateContext ctx, Map<String, Object> args) {
        int count = PredicateArgs.requireInt(args, "count");
        int windowSeconds = PredicateArgs.requireInt(args, "windowSeconds");
        var tx = ctx.transaction();
        int observed = ctx.state().recordAndCountWithin(
                tx.accountId(),
                tx.txId().toString(),
                tx.timestamp(),
                Duration.ofSeconds(windowSeconds)
        );
        return observed > count;
    }
}
