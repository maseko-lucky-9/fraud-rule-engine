package com.capitec.fraud.engine;

import com.capitec.fraud.domain.Decision;
import com.capitec.fraud.domain.RuleSet;
import com.capitec.fraud.domain.Transaction;
import com.capitec.fraud.engine.predicates.AccountAgeBelowPredicate;
import com.capitec.fraud.engine.predicates.AmountAbovePredicate;
import com.capitec.fraud.engine.predicates.CurrencyMismatchPredicate;
import com.capitec.fraud.engine.predicates.DeviceFingerprintNewPredicate;
import com.capitec.fraud.engine.predicates.GeoMismatchPredicate;
import com.capitec.fraud.engine.predicates.MerchantBlacklistPredicate;
import com.capitec.fraud.engine.predicates.TimeOfDayPredicate;
import com.capitec.fraud.engine.predicates.VelocityPredicate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan §7 Day-2 DoD: "each composed rule has ≥ 2 fixtures (match + no-match)".
 * The 6 shipped rules yield 12 golden cases, stored in
 * {@code test/resources/fixtures/golden-cases.json}.
 *
 * <p>Each case is independent — a fresh {@link InMemoryStateStore} per case so
 * stateful-predicate runs don't bleed into each other. Cases with a
 * {@code preLoad} array prime the state by evaluating those transactions
 * before the assertion target.
 */
class GoldenCasesTest {

    private static final ObjectMapper M = new ObjectMapper();

    static Stream<GoldenCase> cases() throws IOException {
        try (var in = new ClassPathResource("fixtures/golden-cases.json").getInputStream()) {
            JsonNode root = M.readTree(in);
            return StreamSupport.stream(root.path("cases").spliterator(), false)
                    .map(GoldenCasesTest::toCase);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void evaluates_to_expected_outcome(GoldenCase c) {
        var registry = new PredicateRegistry(List.of(
                new AmountAbovePredicate(),
                new AccountAgeBelowPredicate(),
                new CurrencyMismatchPredicate(),
                new GeoMismatchPredicate(),
                new MerchantBlacklistPredicate(),
                new TimeOfDayPredicate(),
                new VelocityPredicate(),
                new DeviceFingerprintNewPredicate()
        ));
        var state = new InMemoryStateStore();
        var engine = new RuleEngine(registry, state, Clock.fixed(Instant.parse("2026-05-19T12:00:00Z"), ZoneOffset.UTC), java.util.Optional.empty());
        RuleSet rs = new RuleLoader().load(new ClassPathResource("rules/rule-set-v1.yml"));
        engine.install(rs);

        for (Transaction primer : c.preLoad()) {
            engine.evaluate(primer);
        }
        Decision d = engine.evaluate(c.input());

        assertThat(d.status().name()).as("status").isEqualTo(c.expectedStatus());
        assertThat(d.score()).as("score").isEqualTo(c.expectedScore());
        assertThat(d.matchedRules().stream().map(r -> r.ruleId()).sorted().toList())
                .as("matched rule ids")
                .isEqualTo(c.expectedMatchedRuleIds().stream().sorted().toList());
    }

    private static GoldenCase toCase(JsonNode n) {
        String name = n.path("name").asText();
        Transaction input = parseTx(n.path("input"));
        List<Transaction> pre = new ArrayList<>();
        if (n.has("preLoad")) {
            n.path("preLoad").forEach(p -> pre.add(parseTx(p)));
        }
        JsonNode e = n.path("expected");
        List<String> matched = new ArrayList<>();
        e.path("matchedRuleIds").forEach(rid -> matched.add(rid.asText()));
        return new GoldenCase(name, pre, input, e.path("status").asText(), e.path("score").asDouble(), matched);
    }

    private static Transaction parseTx(JsonNode t) {
        return new Transaction(
                UUID.fromString(t.path("txId").asText()),
                t.path("accountId").asText(),
                new BigDecimal(t.path("amount").asText()),
                t.path("currency").asText(),
                t.path("mcc").asText(),
                Transaction.Channel.valueOf(t.path("channel").asText()),
                t.path("country").asText(),
                t.path("ipCountry").asText(),
                t.path("deviceId").isNull() ? null : t.path("deviceId").asText(null),
                t.path("merchantId").isNull() ? null : t.path("merchantId").asText(null),
                t.path("accountAgeDays").asInt(),
                Instant.parse(t.path("timestamp").asText())
        );
    }

    record GoldenCase(
            String name,
            List<Transaction> preLoad,
            Transaction input,
            String expectedStatus,
            double expectedScore,
            List<String> expectedMatchedRuleIds
    ) {
        @Override public String toString() { return name; }
    }
}
