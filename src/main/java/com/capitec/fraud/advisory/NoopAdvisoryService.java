package com.capitec.fraud.advisory;

import com.capitec.fraud.domain.Decision;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default no-op advisor — bound when the {@code advisory} profile is
 * inactive ({@code app.advisory.enabled=false}). Returns
 * {@link AdvisoryStatus#UNAVAILABLE} so call sites get a consistent shape;
 * the {@link AdvisoryController} maps this to a 503 with {@code Retry-After}.
 *
 * <p>This keeps the core /api path running fine without Ollama in compose.
 */
@Component
@ConditionalOnProperty(prefix = "app.advisory", name = "enabled", havingValue = "false", matchIfMissing = true)
@ConditionalOnMissingBean(value = AdvisoryService.class, ignored = NoopAdvisoryService.class)
public class NoopAdvisoryService implements AdvisoryService {

    @Override
    public AdvisoryResponse adviseOn(Decision decision) {
        return AdvisoryResponse.unavailable();
    }
}
