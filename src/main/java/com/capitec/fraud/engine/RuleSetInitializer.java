package com.capitec.fraud.engine;

import com.capitec.fraud.domain.RuleSet;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Loads the configured rule YAML during context init and installs it into the
 * {@link RuleEngine}. Implemented as a {@link PostConstruct} (not an
 * ApplicationReadyEvent listener) so any load failure propagates as a
 * BeanInitializationException and aborts startup — the app must NEVER come up
 * "ready" without a rule set, because the first request would 500 with
 * {@code IllegalStateException("No RuleSet installed")}.
 */
@Component
public class RuleSetInitializer {

    private static final Logger log = LoggerFactory.getLogger(RuleSetInitializer.class);

    private final RuleEngine engine;
    private final RuleLoader loader;
    private final ResourceLoader resourceLoader;
    private final String rulesPath;

    public RuleSetInitializer(RuleEngine engine,
                              RuleLoader loader,
                              ResourceLoader resourceLoader,
                              @Value("${app.rules.path}") String rulesPath) {
        this.engine = engine;
        this.loader = loader;
        this.resourceLoader = resourceLoader;
        this.rulesPath = rulesPath;
    }

    @PostConstruct
    public void installInitialRuleSet() {
        RuleSet rs = loader.load(resourceLoader.getResource(rulesPath));
        engine.install(rs);
        log.info("Installed initial RuleSet version={} from {}", rs.version(), rulesPath);
    }
}
