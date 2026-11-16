package com.simulatedbank.banking.audit.application.port;

import com.simulatedbank.banking.audit.domain.AuditEvent;

import java.time.Instant;

/**
 * Outbound persistence port for appending hash-chained audit records.
 * Implemented by persistence adapters (e.g. AuditJdbcWriter in adapter/out).
 */
public interface AuditRecordWriterPort {
    AuditRecordRef append(AuditEvent event, Instant occurredAt);
}
