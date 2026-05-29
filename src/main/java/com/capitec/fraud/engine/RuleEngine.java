package com.capitec.fraud.engine;

import com.capitec.fraud.domain.Action;
import com.capitec.fraud.domain.Condition;
import com.capitec.fraud.domain.Decision;
import com.capitec.fraud.domain.DecisionStatus;
import com.capitec.fraud.domain.Rule;
import com.capitec.fraud.domain.RuleResult;
import com.capitec.fraud.domain.RuleSet;
import com.capitec.fraud.domain.Transaction;
import com.capitec.fraud.observability.EngineMetrics;
import org.springframework.stereotype.Component;

import java.util.Optional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic rule evaluator. Holds the active {@link RuleSet} in an
 * {@link AtomicReference} so reloads swap atomically without ever leaving a
 * caller with a null or half-built set.
 *
 * <p>Evaluation walks rules by priority desc; a matched rule with
 * {@code shortCircuit=true} stops further evaluation. The final
 * {@link DecisionStatus} is the strictest action among matched rules
 * (BLOCK > REVIEW > APPROVE); the final score is the max.
 */
@Component
public class RuleEngine {

    private final PredicateRegistry registry;
    private final StateStore stateStore;
    private final Clock clock;
    private final Optional<EngineMetrics> metrics;
    private final AtomicReference<RuleSet> active = new AtomicReference<>();

    /** Metrics is Optional so Spring injects when present, tests pass Optional.empty(). */
    public RuleEngine(PredicateRegistry registry,
                      StateStore stateStore,
                      Clock clock,
                      Optional<EngineMetrics> metrics) {
        this.registry = registry;
        this.stateStore = stateStore;
        this.clock = clock;
        this.metrics = metrics;
    }

    public void install(RuleSet ruleSet) {
        active.set(ruleSet);
    }

    public RuleSet active() {
        return active.get();
    }

    public Decision evaluate(Transaction tx) {
        RuleSet snapshot = active.get();
        if (snapshot == null) {
            throw new IllegalStateException("No RuleSet installed");
        }
        long start = System.nanoTime();
        try {
            PredicateContext ctx = new PredicateContext(tx, stateStore, clock);
            List<RuleResult> matched = new ArrayList<>();
            double maxScore = 0.0;
            DecisionStatus status = DecisionStatus.APPROVE;

            for (Rule rule : snapshot.rules()) {
                if (evaluateCondition(rule.condition(), ctx)) {
                    Action action = rule.action();
                    matched.add(new RuleResult(rule.id(), rule.priority(), action.reason()));
                    maxScore = Math.max(maxScore, action.score());
                    status = stricter(status, action.flag());
                    if (rule.shortCircuit()) break;
                }
            }

            Decision decision = new Decision(
                    UUID.randomUUID(),
                    tx.txId(),
                    tx.accountId(),
                    status,
                    maxScore,
                    snapshot.version(),
                    matched,
                    clock.instant()
            );
            metrics.ifPresent(m -> m.recordDecision(decision));
            return decision;
        } finally {
            metrics.ifPresent(m -> m.ruleEvalTimer().record(
                    System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS));
        }
    }

    private boolean evaluateCondition(Condition cond, PredicateContext ctx) {
        return switch (cond) {
            case Condition.Atomic atomic -> registry.require(atomic.predicateId()).test(ctx, atomic.args());
            case Condition.All all -> {
                for (Condition c : all.children()) {
                    if (!evaluateCondition(c, ctx)) yield false;
                }
                yield true;
            }
            case Condition.Any any -> {
                for (Condition c : any.children()) {
                    if (evaluateCondition(c, ctx)) yield true;
                }
                yield false;
            }
        };
    }

    private static DecisionStatus stricter(DecisionStatus a, DecisionStatus b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(DecisionStatus s) {
        return switch (s) {
            case APPROVE -> 0;
            case REVIEW -> 1;
            case BLOCK -> 2;
        };
    }
}
