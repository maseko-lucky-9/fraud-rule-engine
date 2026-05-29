package com.capitec.fraud.engine;

import com.capitec.fraud.domain.Action;
import com.capitec.fraud.domain.Condition;
import com.capitec.fraud.domain.DecisionStatus;
import com.capitec.fraud.domain.Rule;
import com.capitec.fraud.domain.RuleSet;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads a {@link RuleSet} from a YAML resource. Validates the document against
 * a JSON Schema before constructing the domain tree; throws a typed exception
 * on any failure so {@code AdminController} can return 422 with diagnostics.
 *
 * <p>This class does NOT install the loaded {@link RuleSet} into the
 * {@link RuleEngine} — callers do that explicitly so the swap point is
 * visible in the code path (and testable).
 */
@Component
public class RuleLoader {

    private static final Logger log = LoggerFactory.getLogger(RuleLoader.class);

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper json = new ObjectMapper();
    private final JsonSchema schema;

    public RuleLoader() {
        SchemaValidatorsConfig cfg = new SchemaValidatorsConfig();
        cfg.setTypeLoose(false);
        try (InputStream s = getClass().getResourceAsStream("/rules/rule-set.schema.json")) {
            if (s == null) {
                throw new IllegalStateException("rule-set.schema.json missing from classpath");
            }
            this.schema = JsonSchemaFactory
                    .getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(s, cfg);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load rule schema", e);
        }
    }

    public RuleSet load(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = yaml.readTree(in);
            Set<ValidationMessage> errors = schema.validate(root);
            if (!errors.isEmpty()) {
                throw new RuleValidationException(errors.stream().map(ValidationMessage::getMessage).toList());
            }
            return toDomain(root);
        } catch (RuleValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new RuleValidationException(List.of("YAML parse error: " + e.getMessage()));
        }
    }

    private RuleSet toDomain(JsonNode root) {
        JsonNode rs = root.path("ruleSet");
        String id = rs.path("id").asText();
        int version = rs.path("version").asInt();
        List<Rule> rules = new java.util.ArrayList<>();
        for (JsonNode r : rs.path("rules")) {
            rules.add(toRule(r));
        }
        log.info("Loaded RuleSet id={} version={} rules={}", id, version, rules.size());
        return new RuleSet(id, version, rules);
    }

    private Rule toRule(JsonNode r) {
        String id = r.path("id").asText();
        int priority = r.path("priority").asInt();
        Rule.Severity severity = Rule.Severity.valueOf(r.path("severity").asText("MEDIUM"));
        boolean shortCircuit = r.path("shortCircuit").asBoolean(false);
        Condition condition = toCondition(r.path("condition"));
        Action action = toAction(r.path("action"));
        return new Rule(id, priority, severity, shortCircuit, condition, action);
    }

    @SuppressWarnings("unchecked")
    private Condition toCondition(JsonNode c) {
        if (c.has("all")) {
            return new Condition.All(toChildren(c.path("all")));
        }
        if (c.has("any")) {
            return new Condition.Any(toChildren(c.path("any")));
        }
        if (c.has("predicate")) {
            String predicateId = c.path("predicate").asText();
            JsonNode argsNode = c.path("args");
            Map<String, Object> args = argsNode.isObject()
                    ? json.convertValue(argsNode, Map.class)
                    : Map.of();
            return new Condition.Atomic(predicateId, args);
        }
        throw new RuleValidationException(List.of("Condition must be one of: predicate, all, any. Got: " + c));
    }

    private List<Condition> toChildren(JsonNode arr) {
        List<Condition> out = new java.util.ArrayList<>();
        for (JsonNode n : arr) {
            out.add(toCondition(n));
        }
        return out;
    }

    private Action toAction(JsonNode a) {
        DecisionStatus flag = DecisionStatus.valueOf(a.path("flag").asText());
        double score = a.path("score").asDouble();
        String reason = a.path("reason").asText();
        return new Action(flag, score, reason);
    }
}
