package com.simulatedbank.banking.audit.application.port;

import com.simulatedbank.banking.audit.domain.AuditSearchQuery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound query port for retrieving audit records from persistence.
 * Implemented by persistence adapters (e.g. AuditQueryJdbcStore in adapter/out).
 */
public interface AuditQueryPort {

    record AuditRecordSummary(
            String auditId,
            Instant occurredAt,
            Instant recordedAt,
            String actorType,
            String actorUserId,
            String actorRole,
            String action,
            String entityType,
            String entityId,
            String amount,
            String currency,
            String correlationId,
            String outcome,
            String prevHash,
            String recordHash,
            int hashVersion
    ) { }

    record AuditRecordPage(
            List<AuditRecordSummary> items,
            int number,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last,
            Instant asOf
    ) { }

    record AuditRecordDetail(
            AuditRecordSummary summary,
            String requestId,
            String beforeStateJson,
            String afterStateJson,
            String clientInfoMaskedJson
    ) { }

    AuditRecordPage search(AuditSearchQuery query);

    AuditRecordPage search(AuditSearchQuery query, Instant asOf);

    AuditRecordPage search(AuditSearchQuery query, String reference, String correlationId);

    AuditRecordPage search(AuditSearchQuery query, String reference, String correlationId, Instant asOf);

    Optional<AuditRecordDetail> findDetail(long auditId);
}
