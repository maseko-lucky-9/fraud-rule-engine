package com.capitec.fraud.api;

import com.capitec.fraud.domain.Decision;
import com.capitec.fraud.domain.Transaction;
import com.capitec.fraud.idempotency.IdempotencyService;
import com.capitec.fraud.ingest.IngestService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "transactions", description = "Submit transaction events for fraud evaluation.")
public class TransactionController {

    private final IngestService ingestService;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public TransactionController(IngestService ingestService,
                                 IdempotencyService idempotency,
                                 ObjectMapper json) {
        this.ingestService = ingestService;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Operation(summary = "Submit a transaction event. Persists the raw event, the verdict, "
            + "the matched-rule trace, and an outbox row in a single transaction. "
            + "Supports the standard Idempotency-Key header (24h cache).")
    @PostMapping
    public ResponseEntity<?> submit(
            @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key",
                    description = "Optional. Cached for 24h scoped to the JWT subject. "
                            + "Replay with same key + same body returns the cached response; "
                            + "same key + different body returns 409 Conflict.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransactionRequest request) {

        String subject = currentSubject();
        String bodyHash = canonicalBodyHash(request);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var cached = idempotency.lookup(subject, idempotencyKey);
            if (cached.isPresent()) {
                if (!cached.get().bodyHash().equals(bodyHash)) {
                    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
                    pd.setType(URI.create("https://fraud-engine.example/problems/idempotency-conflict"));
                    pd.setTitle("Idempotency-Key reused with different body");
                    pd.setDetail("The same Idempotency-Key was previously used with a different payload.");
                    pd.setProperty("idempotencyKey", idempotencyKey);
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
                }
                // Same key + same body → return cached response verbatim.
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(cached.get().response());
            }
        }

        Decision decision = ingestService.ingest(toDomain(request), IngestService.CONSUMER_REST);
        DecisionResponse response = toResponse(decision);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotency.store(subject, idempotencyKey, bodyHash, response);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    private String canonicalBodyHash(TransactionRequest req) {
        try {
            // Re-serialise to canonical JSON (record field order) so semantically-equal
            // payloads hash identically regardless of client formatting.
            return IdempotencyService.hashBody(json.writeValueAsBytes(req));
        } catch (JsonProcessingException e) {
            // Fall back to toString-based hash so the request still flows.
            return IdempotencyService.hashBody(IdempotencyService.toBytes(req.toString()));
        }
    }

    private static String currentSubject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "anonymous" : auth.getName();
    }

    private static Transaction toDomain(TransactionRequest r) {
        return new Transaction(
                r.txId(), r.accountId(), r.amount(), r.currency(), r.mcc(),
                Transaction.Channel.valueOf(r.channel()),
                r.country(), r.ipCountry(), r.deviceId(), r.merchantId(),
                r.accountAgeDays(), r.timestamp()
        );
    }

    private static DecisionResponse toResponse(Decision d) {
        return new DecisionResponse(
                d.decisionId(), d.txId(),
                d.status().name(), d.score(), d.ruleSetVersion(),
                d.matchedRules().stream()
                        .map(r -> new DecisionResponse.MatchedRule(r.ruleId(), r.priority(), r.reason()))
                        .toList(),
                d.evaluatedAt()
        );
    }
}
