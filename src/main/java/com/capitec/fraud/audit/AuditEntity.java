package com.capitec.fraud.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String action;

    @Column(name = "resource_id", nullable = false)
    private String resourceId;

    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEntity() { /* JPA */ }

    public AuditEntity(String actor, String action, String resourceId, String payloadHash) {
        this.actor = actor;
        this.action = action;
        this.resourceId = resourceId;
        this.payloadHash = payloadHash;
        this.occurredAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getResourceId() { return resourceId; }
    public String getPayloadHash() { return payloadHash; }
    public Instant getOccurredAt() { return occurredAt; }
}
