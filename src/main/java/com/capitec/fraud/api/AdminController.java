package com.capitec.fraud.api;

import com.capitec.fraud.domain.RuleSet;
import com.capitec.fraud.engine.RuleEngine;
import com.capitec.fraud.engine.RuleLoader;
import com.capitec.fraud.engine.RuleValidationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/admin/rules")
@Tag(name = "admin", description = "Operator endpoints. Day-4 work will require service API key.")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final RuleEngine engine;
    private final RuleLoader loader;
    private final ResourceLoader resourceLoader;
    private final String rulesPath;
    private final Counter reloadFailedCounter;

    public AdminController(RuleEngine engine,
                           RuleLoader loader,
                           ResourceLoader resourceLoader,
                           MeterRegistry meters,
                           @Value("${app.rules.path}") String rulesPath) {
        this.engine = engine;
        this.loader = loader;
        this.resourceLoader = resourceLoader;
        this.rulesPath = rulesPath;
        this.reloadFailedCounter = Counter.builder("rules_reload_failed_total")
                .description("Count of rule-set reload attempts rejected by schema/predicate validation. Old rule-set stays active on every failure.")
                .register(meters);
    }

    @Operation(summary = "Hot-reload the rule set from the configured YAML resource. "
            + "Atomic swap via AtomicReference; old set serves in-flight evaluations until completion. "
            + "On validation failure the old set stays active and a 422 is returned.")
    @PostMapping(value = "/reload", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> reload() {
        try {
            RuleSet rs = loader.load(resourceLoader.getResource(rulesPath));
            int oldVersion = engine.active() == null ? -1 : engine.active().version();
            engine.install(rs);
            log.info("Reload OK: {} → {} (rules: {})", oldVersion, rs.version(), rs.rules().size());
            return ResponseEntity.ok(Map.of(
                    "previousVersion", oldVersion,
                    "currentVersion", rs.version(),
                    "ruleCount", rs.rules().size()
            ));
        } catch (RuleValidationException e) {
            reloadFailedCounter.increment();
            log.error("Reload REJECTED: {}", e.getMessage());
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
            pd.setProperty("errors", e.errors());
            pd.setProperty("activeVersion", engine.active() == null ? null : engine.active().version());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(pd);
        }
    }
}
