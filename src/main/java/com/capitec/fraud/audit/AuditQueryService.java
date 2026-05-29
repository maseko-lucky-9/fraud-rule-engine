package com.capitec.fraud.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Audit-log read service. Kept in {@code audit} (not {@code api}) so the
 * {@code AuditController} can stay thin and the ArchUnit
 * {@code api ↛ persistence} rule holds. The {@code AuditEntity} never leaks
 * past this service.
 */
@Service
public class AuditQueryService {

    private final AuditRepository repo;

    public AuditQueryService(AuditRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public AuditPage list(int page, int size) {
        size = Math.min(Math.max(size, 1), 500);
        Page<AuditEntity> p = repo.findAllByOrderByOccurredAtDesc(PageRequest.of(page, size));
        return new AuditPage(
                p.stream().map(AuditQueryService::toDto).toList(),
                p.getNumber(), p.getSize(), p.getTotalElements());
    }

    private static AuditDto toDto(AuditEntity e) {
        return new AuditDto(e.getId(), e.getActor(), e.getAction(),
                e.getResourceId(), e.getPayloadHash(), e.getOccurredAt());
    }

    public record AuditDto(Long id, String actor, String action,
                           String resourceId, String payloadHash, Instant occurredAt) {}
    public record AuditPage(List<AuditDto> items, int page, int size, long total) {}
}
