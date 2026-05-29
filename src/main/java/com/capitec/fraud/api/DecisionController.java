package com.capitec.fraud.api;

import com.capitec.fraud.query.DecisionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read API for decisions. Cursor-style pagination via {@code (page, size)}.
 * Controller is intentionally thin — all read logic lives in
 * {@link DecisionQueryService} so the {@code api ↛ persistence} ArchUnit
 * rule is preserved.
 */
@RestController
@RequestMapping("/api/v1/decisions")
@Tag(name = "decisions", description = "Retrieve evaluated decisions.")
public class DecisionController {

    private final DecisionQueryService query;

    public DecisionController(DecisionQueryService query) {
        this.query = query;
    }

    @Operation(summary = "Page through recent decisions, newest first.")
    @GetMapping
    public DecisionQueryService.DecisionPage list(@RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        return query.list(page, size);
    }

    @Operation(summary = "Fetch a single decision by id, including the full matched-rule trace.")
    @GetMapping("/{id}")
    public ResponseEntity<DecisionQueryService.DecisionDetail> get(@PathVariable("id") UUID id) {
        return query.get(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
