package com.simulatedbank.banking.audit.api;

/** Durable reference of a written audit record; carries no payload and no PII. */
public record AuditRecordRef(long auditId, String recordHash) {

    public AuditRecordRef {
        if (auditId <= 0) {
            throw new IllegalArgumentException("auditId must be positive");
        }
        if (recordHash == null || !recordHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("recordHash must be lowercase SHA-256 hex");
        }
    }
}
