package com.capitec.fraud.advisory;

import com.capitec.fraud.domain.Decision;
import com.capitec.fraud.domain.DecisionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day-5: the {@link NoopAdvisoryService} (default — Ollama profile inactive)
 * must always say UNAVAILABLE + human-review-required, never crash. This is
 * the safety contract for clients calling the advisory endpoint without
 * the advisory profile being on.
 */
class AdvisoryServiceTest {

    private final NoopAdvisoryService noop = new NoopAdvisoryService();

    @Test
    void noop_always_returns_unavailable_with_human_review_required() {
        Decision d = sampleDecision();
        AdvisoryResponse r = noop.adviseOn(d);
        assertThat(r.advisoryStatus()).isEqualTo(AdvisoryStatus.UNAVAILABLE);
        assertThat(r.humanReviewRequired()).isTrue();
        assertThat(r.confidence()).isZero();
    }

    @Test
    void noop_reports_disabled() {
        assertThat(noop.enabled()).isFalse();
    }

    @Test
    void factory_methods_satisfy_invariants() {
        for (AdvisoryResponse r : List.of(AdvisoryResponse.timedOut(),
                                          AdvisoryResponse.unavailable(),
                                          AdvisoryResponse.malformed())) {
            assertThat(r.humanReviewRequired()).as(r.advisoryStatus().name()).isTrue();
            assertThat(r.confidence()).as(r.advisoryStatus().name()).isZero();
        }
    }

    private static Decision sampleDecision() {
        return new Decision(UUID.randomUUID(), UUID.randomUUID(), "ACC-1",
                DecisionStatus.REVIEW, 0.85, 1, List.of(), Instant.now());
    }
}
