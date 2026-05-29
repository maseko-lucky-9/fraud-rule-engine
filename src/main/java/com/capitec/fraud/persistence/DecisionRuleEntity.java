package com.capitec.fraud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "decision_rules")
public class DecisionRuleEntity {

    @EmbeddedId
    private DecisionRuleId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("decisionId")
    @JoinColumn(name = "decision_id")
    private DecisionEntity decision;

    @Column(name = "matched_priority", nullable = false)
    private int matchedPriority;

    @Column(nullable = false)
    private String reason;

    protected DecisionRuleEntity() { /* JPA */ }

    public DecisionRuleEntity(UUID decisionId, String ruleId, int matchedPriority, String reason) {
        this.id = new DecisionRuleId(decisionId, ruleId);
        this.matchedPriority = matchedPriority;
        this.reason = reason;
    }

    void setDecision(DecisionEntity decision) {
        this.decision = decision;
        if (this.id != null) {
            this.id = new DecisionRuleId(decision.getDecisionId(), this.id.ruleId());
        }
    }

    public String getRuleId() { return id == null ? null : id.ruleId(); }
    public int getMatchedPriority() { return matchedPriority; }
    public String getReason() { return reason; }

    @Embeddable
    public record DecisionRuleId(UUID decisionId, String ruleId) implements Serializable {
        public DecisionRuleId {
            Objects.requireNonNull(decisionId);
            Objects.requireNonNull(ruleId);
        }
    }
}
