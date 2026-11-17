package com.simulatedbank.banking.audit.application.port;

/**
 * Immutable reference to an inserted, hash-chained audit record.
 */
public record AuditRecordRef(
        long auditId,
        String recordHash,
        short hashVersion
) { }
