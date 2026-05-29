package com.capitec.fraud.engine.predicates;

import com.capitec.fraud.engine.Predicate;
import com.capitec.fraud.engine.PredicateContext;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * True iff the transaction's local time (in the configured {@code zone}) falls
 * within {@code [start, end)}. Handles wrap-around (e.g. 22:00–05:00).
 * Args: {@code start: "HH:mm", end: "HH:mm", zone: IANA tz id}.
 */
@Component
public class TimeOfDayPredicate implements Predicate {

    @Override
    public String id() {
        return "timeOfDay";
    }

    @Override
    public boolean test(PredicateContext ctx, Map<String, Object> args) {
        LocalTime start = LocalTime.parse(PredicateArgs.requireString(args, "start"));
        LocalTime end = LocalTime.parse(PredicateArgs.requireString(args, "end"));
        ZoneId zone = ZoneId.of(PredicateArgs.requireString(args, "zone"));
        LocalTime t = ctx.transaction().timestamp().atZone(zone).toLocalTime();

        if (start.isBefore(end)) {
            return !t.isBefore(start) && t.isBefore(end);
        }
        // wrap-around: e.g. 22:00–05:00
        return !t.isBefore(start) || t.isBefore(end);
    }
}
