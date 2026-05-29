package com.capitec.fraud.engine;

import com.capitec.fraud.domain.DecisionStatus;
import com.capitec.fraud.domain.RuleSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleLoaderTest {

    private final RuleLoader loader = new RuleLoader();

    @Test
    void loads_v1_rule_set_from_classpath() {
        RuleSet rs = loader.load(new ClassPathResource("rules/rule-set-v1.yml"));
        assertThat(rs.id()).isEqualTo("default");
        assertThat(rs.version()).isEqualTo(1);
        assertThat(rs.rules()).hasSize(6);
        // Sorted by priority desc → BLACKLISTED_MERCHANT (1000) is first
        assertThat(rs.rules().get(0).id()).isEqualTo("BLACKLISTED_MERCHANT");
        assertThat(rs.rules().get(0).action().flag()).isEqualTo(DecisionStatus.BLOCK);
        assertThat(rs.rules().get(0).shortCircuit()).isTrue();
    }

    @Test
    void rejects_yaml_with_unknown_top_level_key() {
        String yaml = """
                ruleSet:
                  id: x
                  version: 1
                  rules:
                    - id: BAD
                      priority: 1
                      bogusKey: 42
                      condition: { predicate: amountAbove, args: { value: 1, currency: ZAR } }
                      action: { flag: REVIEW, score: 0.5, reason: "test" }
                """;
        assertThatThrownBy(() -> loader.load(new ByteArrayResource(yaml.getBytes())))
                .isInstanceOf(RuleValidationException.class);
    }

    @Test
    void rejects_invalid_score_out_of_range() {
        String yaml = """
                ruleSet:
                  id: x
                  version: 1
                  rules:
                    - id: BAD
                      priority: 1
                      condition: { predicate: amountAbove, args: { value: 1, currency: ZAR } }
                      action: { flag: REVIEW, score: 5.0, reason: "test" }
                """;
        assertThatThrownBy(() -> loader.load(new ByteArrayResource(yaml.getBytes())))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("score");
    }

    @Test
    void rejects_rule_id_in_wrong_format() {
        String yaml = """
                ruleSet:
                  id: x
                  version: 1
                  rules:
                    - id: lowercase_id
                      priority: 1
                      condition: { predicate: amountAbove, args: { value: 1, currency: ZAR } }
                      action: { flag: REVIEW, score: 0.5, reason: "test" }
                """;
        assertThatThrownBy(() -> loader.load(new ByteArrayResource(yaml.getBytes())))
                .isInstanceOf(RuleValidationException.class);
    }
}
