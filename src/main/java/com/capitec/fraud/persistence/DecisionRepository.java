package com.capitec.fraud.persistence;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DecisionRepository extends JpaRepository<DecisionEntity, UUID> {

    @EntityGraph(attributePaths = "matchedRules")
    Optional<DecisionEntity> findFirstByTxIdOrderByEvaluatedAtDesc(UUID txId);

    @EntityGraph(attributePaths = "matchedRules")
    Optional<DecisionEntity> findWithRulesByDecisionId(UUID decisionId);
}
