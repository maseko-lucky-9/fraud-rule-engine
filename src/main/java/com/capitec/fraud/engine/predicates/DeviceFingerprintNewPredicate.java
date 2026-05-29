package com.capitec.fraud.engine.predicates;

import com.capitec.fraud.engine.Predicate;
import com.capitec.fraud.engine.PredicateContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * True iff {@code transaction.deviceId} has never been seen for this account.
 * Side-effect: records the device as seen, so subsequent evaluations for the
 * same (accountId, deviceId) return false. No args.
 */
@Component
public class DeviceFingerprintNewPredicate implements Predicate {

    @Override
    public String id() {
        return "deviceFingerprintNew";
    }

    @Override
    public boolean test(PredicateContext ctx, Map<String, Object> args) {
        var tx = ctx.transaction();
        return ctx.state().isNewDevice(tx.accountId(), tx.deviceId());
    }
}
