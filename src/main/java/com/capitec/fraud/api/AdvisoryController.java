package com.capitec.fraud.api;

import com.capitec.fraud.advisory.AdvisoryResponse;
import com.capitec.fraud.advisory.AdvisoryService;
import com.capitec.fraud.advisory.AdvisoryStatus;
import com.capitec.fraud.query.DecisionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Advisory commentary for an existing decision. Strictly non-blocking for
 * core decisioning — clients call this AFTER they have the deterministic
 * decision in hand. If the advisory is unavailable, this endpoint returns
 * 503 with {@code Retry-After} and the deterministic decision remains
 * authoritative.
 */
@RestController
@RequestMapping("/api/v1/decisions/{id}/advisory")
@Tag(name = "advisory", description = "Optional LLM-backed commentary on a decision. Never authoritative.")
public class AdvisoryController {

    private final DecisionQueryService decisions;
    private final AdvisoryService advisory;

    public AdvisoryController(DecisionQueryService decisions, AdvisoryService advisory) {
        this.decisions = decisions;
        this.advisory = advisory;
    }

    @Operation(summary = "Generate or return advisory commentary for the given decision id.")
    @GetMapping
    public ResponseEntity<AdvisoryResponse> get(@PathVariable("id") UUID id) {
        var maybe = decisions.get(id);
        if (maybe.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Build a minimal Decision-shaped object from the DTO (advisor only
        // needs status / score / matched rule ids, no PII).
        var d = maybe.get();
        var stubDecision = new com.capitec.fraud.domain.Decision(
                d.decisionId(), d.txId(), d.accountId(),
                com.capitec.fraud.domain.DecisionStatus.valueOf(d.status()),
                d.score(), d.ruleSetVersion(),
                d.matchedRules().stream()
                        .map(r -> new com.capitec.fraud.domain.RuleResult(r.ruleId(), r.priority(), r.reason()))
                        .toList(),
                d.evaluatedAt());

        AdvisoryResponse advice = advisory.adviseOn(stubDecision);
        if (advice.advisoryStatus() == AdvisoryStatus.UNAVAILABLE || advice.advisoryStatus() == AdvisoryStatus.TIMED_OUT) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "10")
                    .body(advice);
        }
        return ResponseEntity.ok(advice);
    }
}
