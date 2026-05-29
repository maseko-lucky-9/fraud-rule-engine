package com.capitec.fraud.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read API for the audit log. Service API key required (the filter sets
 * {@code ROLE_SERVICE} on the security context).
 *
 * <p>Lives in the {@code audit} package — not {@code api} — because admin
 * read endpoints are owned by the audit module. ArchUnit's {@code api ↛
 * persistence} rule is preserved because {@code api} never sees this class.
 */
@RestController
@RequestMapping("/admin/audit")
@Tag(name = "audit", description = "Read the audit log. Service API key required.")
@PreAuthorize("hasRole('SERVICE')")
public class AuditController {

    private final AuditQueryService query;

    public AuditController(AuditQueryService query) {
        this.query = query;
    }

    @Operation(summary = "Page through audit log entries, newest first.")
    @GetMapping
    public AuditQueryService.AuditPage list(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        return query.list(page, size);
    }
}
