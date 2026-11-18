package com.simulatedbank.banking.audit.adapter.out;

import com.simulatedbank.banking.audit.application.port.AuditRecordRef;
import com.simulatedbank.banking.audit.application.port.AuditRecordWriterPort;
import com.simulatedbank.banking.audit.domain.AuditCanonicalJson;
import com.simulatedbank.banking.audit.domain.AuditChain;
import com.simulatedbank.banking.audit.domain.AuditEvent;
import com.simulatedbank.banking.audit.domain.AuditHashing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * JDBC persistence adapter for atomic hash-chained audit writing.
 * Implements AuditRecordWriterPort.
 * <p>
 * Stack Convention Rationale (BE-CON-39, BE-CON-40, 05 §4.3):
 * Retains JdbcTemplate and native PostgreSQL SQL because:
 * 1. Concurrency & Integrity: Requires pg_advisory_xact_lock to serialize hash chaining.
 * 2. Database-level Isolation Guard: Enforces SHOW transaction_isolation == 'read committed'.
 * 3. Immutable Append-Only Evidence: Direct INSERT RETURNING into banking.audit_log without
 *    ORM entity lifecycle or dirty checking.
 */
@Component
@ConditionalOnProperty(name = "app.audit.enabled", havingValue = "true", matchIfMissing = true)
public class AuditJdbcWriter implements AuditRecordWriterPort {

    private final JdbcTemplate jdbc;

    public AuditJdbcWriter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    @Override
    public AuditRecordRef append(AuditEvent event, Instant occurredAt) {
        // A transaction-wide snapshot can miss a predecessor committed while waiting for the lock.
        if (!"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation", String.class))) {
            throw new IllegalArgumentException("Audit chain writer requires READ COMMITTED isolation");
        }
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, AuditChain.CHAIN_LOCK_KEY);
        var canonical = AuditHashing.canonicalPayload(event, occurredAt);
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
    }
}
