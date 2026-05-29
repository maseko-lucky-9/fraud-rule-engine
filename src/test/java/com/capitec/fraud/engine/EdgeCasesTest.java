package com.capitec.fraud.engine;

import com.capitec.fraud.domain.Action;
import com.capitec.fraud.domain.Condition;
import com.capitec.fraud.domain.Decision;
import com.capitec.fraud.domain.DecisionStatus;
import com.capitec.fraud.domain.Rule;
import com.capitec.fraud.domain.RuleSet;
import com.capitec.fraud.domain.Transaction;
import com.capitec.fraud.engine.predicates.AccountAgeBelowPredicate;
import com.capitec.fraud.engine.predicates.AmountAbovePredicate;
import com.capitec.fraud.engine.predicates.PredicateArgs;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers defensive error paths + the {@code Any} branch of the engine that
 * the smoke + golden suites don't naturally exercise. Targets the
 * ≥95% line coverage gate on {@code engine/} (Day 2 DoD).
 */
class EdgeCasesTest {

    private static final Instant FIXED = Instant.parse("2026-05-19T12:00:00Z");

    @Test
    void duplicate_predicate_ids_fail_fast_at_registry_build() {
        var dup1 = new AmountAbovePredicate();
        var dup2 = new AmountAbovePredicate();
        assertThatThrownBy(() -> new PredicateRegistry(List.of(dup1, dup2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate predicate id");
    }

    @Test
    void registry_knownIds_returns_all_registered() {
        var registry = new PredicateRegistry(List.of(new AmountAbovePredicate(), new AccountAgeBelowPredicate()));
        assertThat(registry.knownIds()).containsExactlyInAnyOrder("amountAbove", "accountAgeBelow");
    }

    @Test
    void registry_unknown_id_throws_with_listing() {
        var registry = new PredicateRegistry(List.of(new AmountAbovePredicate()));
        assertThatThrownBy(() -> registry.require("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("amountAbove");
    }

    @Test
    void engine_evaluates_any_branch() {
        var registry = new PredicateRegistry(List.of(new AmountAbovePredicate(), new AccountAgeBelowPredicate()));
        var engine = new RuleEngine(registry, new InMemoryStateStore(), Clock.fixed(FIXED, ZoneOffset.UTC), java.util.Optional.empty());
        // Rule fires if EITHER condition holds — second one fires here.
        engine.install(new RuleSet("test", 1, List.of(new Rule(
                "EITHER", 100, Rule.Severity.LOW, false,
                new Condition.Any(List.of(
                        new Condition.Atomic("amountAbove", Map.of("value", 999999, "currency", "ZAR")),
                        new Condition.Atomic("accountAgeBelow", Map.of("days", 30))
                )),
                new Action(DecisionStatus.REVIEW, 0.3, "either branch matched")
        ))));
        Decision d = engine.evaluate(tx(BigDecimal.TEN, 5));
        assertThat(d.status()).isEqualTo(DecisionStatus.REVIEW);
        assertThat(d.matchedRules()).hasSize(1);
    }

    @Test
    void any_with_no_branch_holding_does_not_match() {
        var registry = new PredicateRegistry(List.of(new AmountAbovePredicate(), new AccountAgeBelowPredicate()));
        var engine = new RuleEngine(registry, new InMemoryStateStore(), Clock.fixed(FIXED, ZoneOffset.UTC), java.util.Optional.empty());
        engine.install(new RuleSet("test", 1, List.of(new Rule(
                "EITHER", 100, Rule.Severity.LOW, false,
                new Condition.Any(List.of(
                        new Condition.Atomic("amountAbove", Map.of("value", 999999, "currency", "ZAR")),
                        new Condition.Atomic("accountAgeBelow", Map.of("days", 1))
                )),
                new Action(DecisionStatus.REVIEW, 0.3, "n/a")
        ))));
        assertThat(engine.evaluate(tx(BigDecimal.TEN, 500)).status()).isEqualTo(DecisionStatus.APPROVE);
    }

    @Test
    void engine_active_returns_installed_ruleset() {
        var registry = new PredicateRegistry(List.of(new AmountAbovePredicate()));
        var engine = new RuleEngine(registry, new InMemoryStateStore(), Clock.fixed(FIXED, ZoneOffset.UTC), java.util.Optional.empty());
        var rs = new RuleSet("test", 7, List.of(new Rule(
                "X", 100, Rule.Severity.LOW, false,
                new Condition.Atomic("amountAbove", Map.of("value", 1, "currency", "ZAR")),
                new Action(DecisionStatus.REVIEW, 0.1, "x")
        )));
        engine.install(rs);
        assertThat(engine.active().version()).isEqualTo(7);
    }

    @Test
    void rule_validation_exception_exposes_error_list() {
        var ex = new RuleValidationException(List.of("err-1", "err-2"));
        assertThat(ex.errors()).containsExactly("err-1", "err-2");
        assertThat(ex.getMessage()).contains("err-1").contains("err-2");
    }

    @Test
    void predicate_args_rejects_wrong_types() {
        assertThatThrownBy(() -> PredicateArgs.requireString(Map.of("k", 42), "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("string");
        assertThatThrownBy(() -> PredicateArgs.requireInt(Map.of("k", "abc"), "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("integer");
        assertThatThrownBy(() -> PredicateArgs.requireBigDecimal(Map.of("k", List.of()), "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("number");
        assertThatThrownBy(() -> PredicateArgs.requireList(Map.of("k", "scalar"), "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("list");
    }

    @Test
    void predicate_args_accepts_strings_for_bigdecimal() {
        assertThat(PredicateArgs.requireBigDecimal(Map.of("k", "12.50"), "k"))
                .isEqualByComparingTo("12.50");
    }

    @Test
    void predicate_args_missing_key_throws() {
        assertThatThrownBy(() -> PredicateArgs.requireString(Map.of(), "missing"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing");
        assertThatThrownBy(() -> PredicateArgs.requireInt(Map.of(), "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("k");
        assertThatThrownBy(() -> PredicateArgs.requireBigDecimal(Map.of(), "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("k");
        assertThatThrownBy(() -> PredicateArgs.requireList(Map.of(), "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("k");
    }

    @Test
    void rule_loader_rejects_yaml_with_unknown_condition_shape() {
        String yaml = """
                ruleSet:
                  id: x
                  version: 1
                  rules:
                    - id: BAD
                      priority: 1
                      condition: { not: { predicate: amountAbove, args: { value: 1, currency: ZAR } } }
                      action: { flag: REVIEW, score: 0.5, reason: "test" }
                """;
        assertThatThrownBy(() -> new RuleLoader().load(new ByteArrayResource(yaml.getBytes())))
                .isInstanceOf(RuleValidationException.class);
    }

    @Test
    void rule_loader_rejects_malformed_yaml() {
        String yaml = "this is not: { valid: yaml: nesting\n  - bad";
        assertThatThrownBy(() -> new RuleLoader().load(new ByteArrayResource(yaml.getBytes())))
                .isInstanceOf(RuleValidationException.class);
    }

    @Test
    void rule_loader_loads_any_condition_from_yaml() {
        String yaml = """
                ruleSet:
                  id: x
                  version: 1
                  rules:
                    - id: ANY_TEST
                      priority: 1
                      condition:
                        any:
                          - { predicate: amountAbove, args: { value: 1, currency: ZAR } }
                          - { predicate: accountAgeBelow, args: { days: 1 } }
                      action: { flag: REVIEW, score: 0.5, reason: "test" }
                """;
        var rs = new RuleLoader().load(new ByteArrayResource(yaml.getBytes()));
        assertThat(rs.rules().get(0).condition()).isInstanceOf(Condition.Any.class);
    }

    @Test
    void domain_invariants_reject_invalid_score() {
        assertThatThrownBy(() -> new Action(DecisionStatus.REVIEW, 1.5, "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("score");
        assertThatThrownBy(() -> new Action(DecisionStatus.REVIEW, 0.5, "  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
    }

    @Test
    void domain_invariants_reject_empty_all_or_any() {
        assertThatThrownBy(() -> new Condition.All(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Condition.Any(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Condition.Atomic("", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void domain_ruleset_rejects_invalid_id_or_version() {
        assertThatThrownBy(() -> new RuleSet("", 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuleSet("x", 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decision_rejects_out_of_range_score() {
        assertThatThrownBy(() -> new Decision(
                UUID.randomUUID(), UUID.randomUUID(), "A", DecisionStatus.APPROVE,
                1.1, 1, List.of(), FIXED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Transaction tx(BigDecimal amount, int age) {
        return new Transaction(UUID.randomUUID(), "ACC", amount, "ZAR", "5411",
                Transaction.Channel.WEB, "ZA", "ZA", "d", null, age, FIXED);
    }
}
