package com.capitec.fraud.audit;

/**
 * Published by the application code; consumed by {@link AuditEventListener}
 * which writes the row. Decoupling via Spring's event bus keeps audit
 * concerns out of business code.
 */
public record AuditEvent(String actor, String action, String resourceId, String payloadHash) {

    public static AuditEvent of(String actor, String action, String resourceId, String payloadHash) {
        return new AuditEvent(actor, action, resourceId, payloadHash);
    }
}
