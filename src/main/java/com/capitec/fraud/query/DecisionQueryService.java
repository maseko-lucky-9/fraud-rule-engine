package com.capitec.fraud.query;

import com.capitec.fraud.persistence.DecisionEntity;
import com.capitec.fraud.persistence.DecisionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin read-side service for decisions. Exists so {@code api} stays decoupled
 * from {@code persistence} (ArchUnit-enforced) — controllers depend on this
 * service's POJO DTOs, never on JPA entities. Returns immutable records.
 */
@Service
public class DecisionQueryService {

    private final DecisionRepository repo;

    public DecisionQueryService(DecisionRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public DecisionPage list(int page, int size) {
        size = Math.min(Math.max(size, 1), 500);
        Page<DecisionEntity> p = repo.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "evaluatedAt")));
        return new DecisionPage(
                p.stream().map(DecisionQueryService::toSummary).toList(),
                p.getNumber(), p.getSize(), p.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Optional<DecisionDetail> get(UUID id) {
        return repo.findWithRulesByDecisionId(id).map(DecisionQueryService::toDetail);
    }

    private static DecisionSummary toSummary(DecisionEntity e) {
        return new DecisionSummary(
                e.getDecisionId(), e.getTxId(), e.getAccountId(),
                e.getStatus().name(), e.getScore().doubleValue(),
                e.getRuleSetVersion(), e.getEvaluatedAt());
    }

    private static DecisionDetail toDetail(DecisionEntity e) {
        return new DecisionDetail(
                e.getDecisionId(), e.getTxId(), e.getAccountId(),
                e.getStatus().name(), e.getScore().doubleValue(),
                e.getRuleSetVersion(), e.getEvaluatedAt(),
                e.getMatchedRules().stream()
                        .map(r -> new MatchedRule(r.getRuleId(), r.getMatchedPriority(), r.getReason()))
                        .toList());
    }

    public record DecisionSummary(UUID decisionId, UUID txId, String accountId, String status,
                                  double score, int ruleSetVersion, Instant evaluatedAt) {}
    public record MatchedRule(String ruleId, int priority, String reason) {}
    public record DecisionDetail(UUID decisionId, UUID txId, String accountId, String status,
                                 double score, int ruleSetVersion, Instant evaluatedAt,
                                 List<MatchedRule> matchedRules) {}
    public record DecisionPage(List<DecisionSummary> items, int page, int size, long total) {}
}
