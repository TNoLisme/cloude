package com.simulatedbank.banking.audit.application;

import com.simulatedbank.banking.audit.domain.AuditActionCatalog;
import com.simulatedbank.banking.audit.domain.AuditEvent;
import com.simulatedbank.banking.audit.domain.AuditCanonicalJson;
import com.simulatedbank.banking.audit.domain.AuditEventValidator;
import com.simulatedbank.banking.audit.domain.AuditHashing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;

@Service
public class AuditRecorder {
    private final JdbcTemplate jdbc;

    public AuditRecorder(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditRecordRef recordInTransaction(AuditEvent event, Instant occurredAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("Audit C1/C2 requires caller transaction");
        }
        try {
            AuditEventValidator.validate(event);
            var criticality = AuditActionCatalog.require(event.action()).criticality();
            if (criticality != AuditActionCatalog.Criticality.C1 && criticality != AuditActionCatalog.Criticality.C2) {
                throw new IllegalArgumentException("Atomic audit writer accepts only C1/C2 actions");
            }
            if (occurredAt == null) throw new IllegalArgumentException("Event time is required");
            occurredAt = occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var canonical = AuditHashing.canonicalPayload(event, occurredAt);
            // A transaction-wide snapshot can miss a predecessor committed while waiting for the lock.
            if (!"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation", String.class))) {
                throw new IllegalArgumentException("Audit chain writer requires READ COMMITTED isolation");
            }
            jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('audit:chain', 0))");
            var previousRows = jdbc.query("SELECT record_hash FROM banking.audit_log ORDER BY audit_id DESC LIMIT 1",
                    (rs, row) -> rs.getString(1));
            var previous = previousRows.isEmpty() ? AuditHashing.GENESIS_HASH : previousRows.get(0);
            var hash = AuditHashing.recordHash(canonical, previous);
            var id = jdbc.queryForObject("""
                    INSERT INTO banking.audit_log
                    (occurred_at, actor_type, actor_user_id, actor_role, action, entity_type, entity_id,
                     before_state, after_state, amount, currency, correlation_id, request_id, outcome,
                     client_info_masked, prev_hash, record_hash, hash_version, canonical_payload)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, 1, ?::jsonb)
                    RETURNING audit_id
                    """, Long.class, Timestamp.from(occurredAt), event.actorType(), event.actorUserId(), event.actorRole(),
                    event.action(), event.entityType(), event.entityId(), event.beforeState() == null ? null : AuditCanonicalJson.serialize(event.beforeState()),
                    event.afterState() == null ? null : AuditCanonicalJson.serialize(event.afterState()), event.amount(), event.currency(),
                    event.correlationId(), event.requestId(), event.outcome(),
                    event.clientInfoMasked() == null ? null : AuditCanonicalJson.serialize(event.clientInfoMasked()), previous, hash, canonical);
            return new AuditRecordRef(id, hash, (short) 1);
        } catch (RuntimeException exception) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw exception;
        }
    }

    public record AuditRecordRef(long auditId, String recordHash, short hashVersion) { }
}
