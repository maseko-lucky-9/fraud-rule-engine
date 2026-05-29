package com.capitec.fraud.engine.predicates;

import com.capitec.fraud.engine.Predicate;
import com.capitec.fraud.engine.PredicateContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * True iff {@code transaction.merchantId} is in the configured blacklist.
 * Args: {@code in: [string,...]}.
 */
@Component
public class MerchantBlacklistPredicate implements Predicate {

    @Override
    public String id() {
        return "merchantBlacklist";
    }

    @Override
    public boolean test(PredicateContext ctx, Map<String, Object> args) {
        List<?> list = PredicateArgs.requireList(args, "in");
        Set<String> blacklist = list.stream().map(String::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
        String merchantId = ctx.transaction().merchantId();
        return merchantId != null && blacklist.contains(merchantId);
    }
}
