package com.capitec.fraud.engine;

import com.capitec.fraud.domain.Action;
import com.capitec.fraud.domain.Condition;
import com.capitec.fraud.domain.Decision;
import com.capitec.fraud.domain.DecisionStatus;
import com.capitec.fraud.domain.Rule;
import com.capitec.fraud.domain.RuleSet;
import com.capitec.fraud.domain.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleEngineTest {

    private static final Instant FIXED = Instant.parse("2026-05-19T12:00:00Z");

    private RuleEngine engine;
    private StateStore state;

    @BeforeEach
    void setUp() {
        state = new InMemoryStateStore();
        var registry = new PredicateRegistry(List.of(
                new com.capitec.fraud.engine.predicates.AmountAbovePredicate(),
                new com.capitec.fraud.engine.predicates.AccountAgeBelowPredicate()
        ));
        engine = new RuleEngine(registry, state, Clock.fixed(FIXED, ZoneOffset.UTC), java.util.Optional.empty());
    }

    @Test
    @DisplayName("No rules installed → IllegalStateException")
    void no_ruleset_installed_throws() {
        assertThatThrownBy(() -> engine.evaluate(tx(BigDecimal.valueOf(100), 100)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("No rule matches → APPROVE with score 0 and empty trace")
    void no_match_yields_approve() {
        engine.install(ruleSet(highAmountReview()));
        Decision d = engine.evaluate(tx(BigDecimal.valueOf(100), 500));
        assertThat(d.status()).isEqualTo(DecisionStatus.APPROVE);
        assertThat(d.score()).isZero();
        assertThat(d.matchedRules()).isEmpty();
    }

    @Test
    @DisplayName("Match → REVIEW with rule's score; trace captures rule id + reason")
    void single_match_returns_action() {
        engine.install(ruleSet(highAmountReview()));
        Decision d = engine.evaluate(tx(BigDecimal.valueOf(20000), 500));
        assertThat(d.status()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(d.score()).isEqualTo(0.85);
        assertThat(d.matchedRules()).singleElement()
                .satisfies(r -> {
                    assertThat(r.ruleId()).isEqualTo("HIGH_AMOUNT");
                    assertThat(r.reason()).contains("High amount");
                });
    }

    @Test
    @DisplayName("BLOCK > REVIEW > APPROVE: status is strictest among matched rules (no short-circuit)")
    void status_escalates_to_strictest() {
        engine.install(ruleSet(highAmountReview(), youngAccountBlockNoShortCircuit()));
        Decision d = engine.evaluate(tx(BigDecimal.valueOf(20000), 10));
        assertThat(d.status()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(d.score()).isEqualTo(0.95); // max of 0.85, 0.95
        assertThat(d.matchedRules()).hasSize(2);
    }

    @Test
    @DisplayName("shortCircuit:true stops evaluation after first match")
    void short_circuit_skips_remaining_rules() {
        // youngAccountBlock has priority 1000 + shortCircuit; highAmount has priority 100.
        // Tx satisfies both — only youngAccountBlock should match.
        engine.install(ruleSet(highAmountReview(), youngAccountBlockWithShortCircuit()));
        Decision d = engine.evaluate(tx(BigDecimal.valueOf(20000), 10));
        assertThat(d.matchedRules()).hasSize(1);
        assertThat(d.matchedRules().get(0).ruleId()).isEqualTo("YOUNG_BLOCK");
    }

    @Test
    @DisplayName("Decision carries the active rule-set version (audit invariant)")
    void decision_carries_rule_set_version() {
        engine.install(new RuleSet("default", 42, List.of(highAmountReview())));
        Decision d = engine.evaluate(tx(BigDecimal.valueOf(20000), 500));
        assertThat(d.ruleSetVersion()).isEqualTo(42);
    }

    // --- helpers ---

    private static Transaction tx(BigDecimal amount, int ageDays) {
        return new Transaction(
                UUID.randomUUID(), "ACC-1", amount, "ZAR", "5411",
                Transaction.Channel.WEB, "ZA", "ZA", "dev", null, ageDays, FIXED
        );
    }

    private static RuleSet ruleSet(Rule... rules) {
        return new RuleSet("test", 1, List.of(rules));
    }

    private static Rule highAmountReview() {
        return new Rule(
                "HIGH_AMOUNT", 100, Rule.Severity.HIGH, false,
                new Condition.Atomic("amountAbove", Map.of("value", 10000, "currency", "ZAR")),
                new Action(DecisionStatus.REVIEW, 0.85, "High amount.")
        );
    }

    private static Rule youngAccountBlockWithShortCircuit() {
        return new Rule(
                "YOUNG_BLOCK", 1000, Rule.Severity.CRITICAL, true,
                new Condition.Atomic("accountAgeBelow", Map.of("days", 30)),
                new Action(DecisionStatus.BLOCK, 0.95, "Young account on hold.")
        );
    }

    private static Rule youngAccountBlockNoShortCircuit() {
        return new Rule(
                "YOUNG_BLOCK", 1000, Rule.Severity.CRITICAL, false,
                new Condition.Atomic("accountAgeBelow", Map.of("days", 30)),
                new Action(DecisionStatus.BLOCK, 0.95, "Young account on hold.")
        );
    }
}
