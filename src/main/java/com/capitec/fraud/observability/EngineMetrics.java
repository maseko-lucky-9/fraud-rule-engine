package com.capitec.fraud.observability;

import com.capitec.fraud.domain.Decision;
import com.capitec.fraud.domain.DecisionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Domain metrics surface for Prometheus. Counters are keyed by decision
 * status so the dashboard can break out APPROVE / REVIEW / BLOCK.
 * {@code rule_eval_duration_seconds} times the engine's evaluate() call only
 * — not the surrounding persistence — so latency regressions there are
 * isolated.
 */
@Component
public class EngineMetrics {

    private final Map<DecisionStatus, Counter> decisionsByStatus = new EnumMap<>(DecisionStatus.class);
    private final Timer ruleEvalTimer;

    public EngineMetrics(MeterRegistry registry) {
        for (DecisionStatus s : DecisionStatus.values()) {
            decisionsByStatus.put(s, Counter.builder("fraud_decisions_total")
                    .description("Number of decisions emitted, by status")
                    .tag("status", s.name())
                    .register(registry));
        }
        this.ruleEvalTimer = Timer.builder("rule_eval_duration_seconds")
                .description("Latency of RuleEngine.evaluate() — engine path only, no I/O")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    public void recordDecision(Decision d) {
        decisionsByStatus.get(d.status()).increment();
    }

    public Timer ruleEvalTimer() {
        return ruleEvalTimer;
    }
}
