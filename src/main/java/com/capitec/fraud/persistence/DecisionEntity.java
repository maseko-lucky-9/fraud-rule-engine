package com.capitec.fraud.persistence;

import com.capitec.fraud.domain.DecisionStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "decisions")
public class DecisionEntity {

    @Id
    @Column(name = "decision_id")
    private UUID decisionId;

    @Column(name = "tx_id", nullable = false)
    private UUID txId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DecisionStatus status;

    @Column(nullable = false, precision = 4, scale = 3)
    private java.math.BigDecimal score;

    @Column(name = "rule_set_version", nullable = false)
    private int ruleSetVersion;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @OneToMany(mappedBy = "decision", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<DecisionRuleEntity> matchedRules = new ArrayList<>();

    protected DecisionEntity() { /* JPA */ }

    public DecisionEntity(UUID decisionId, UUID txId, String accountId,
                          DecisionStatus status, java.math.BigDecimal score,
                          int ruleSetVersion, Instant evaluatedAt) {
        this.decisionId = decisionId;
        this.txId = txId;
        this.accountId = accountId;
        this.status = status;
        this.score = score;
        this.ruleSetVersion = ruleSetVersion;
        this.evaluatedAt = evaluatedAt;
    }

    public void addMatchedRule(DecisionRuleEntity rule) {
        rule.setDecision(this);
        matchedRules.add(rule);
    }

    public UUID getDecisionId() { return decisionId; }
    public UUID getTxId() { return txId; }
    public String getAccountId() { return accountId; }
    public DecisionStatus getStatus() { return status; }
    public java.math.BigDecimal getScore() { return score; }
    public int getRuleSetVersion() { return ruleSetVersion; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public List<DecisionRuleEntity> getMatchedRules() { return matchedRules; }
}
