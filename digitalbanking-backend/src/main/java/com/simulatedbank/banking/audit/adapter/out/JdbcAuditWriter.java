package com.simulatedbank.banking.audit.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.audit.api.AuditRecordRef;
import com.simulatedbank.banking.audit.application.AuditChainWriter;
import com.simulatedbank.banking.audit.domain.AuditChain;
import com.simulatedbank.banking.audit.domain.ValidatedAuditEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC implementation of the append-only hash-chain protocol (design/01 §6.1/§7.5).
 *
 * <p>Chain integrity: under {@code pg_advisory_xact_lock(CHAIN_LOCK_KEY)} the writer reads the last
 * {@code record_hash} by {@code audit_id DESC} (genesis = 64 zeros on an empty chain), computes
 * {@code record_hash = SHA-256(JCS(payload) || "|" || prev_hash)} and inserts one row. The lock is transaction
 * scoped, so a concurrent appender waits and then sees the previous committed row; a rolled-back append leaves
 * only a sequence gap, which is not tampering because verification follows {@code prev_hash}.</p>
 *
 * <p>Lock scope: {@link #append} is {@code MANDATORY} and is always the last database phase of the caller
 * transaction (audit phase last, C2 ascending id). No business SQL, ORM flush or network call may run after it;
 * the writer itself only performs the lock, the previous-hash SELECT and the INSERT. There is deliberately no
 * UPDATE/DELETE path.</p>
 */
@Component
@ConditionalOnProperty(name = "app.audit.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcAuditWriter implements AuditChainWriter {

    private static final String LOCK_CHAIN = "SELECT pg_advisory_xact_lock(?)";
    private static final String SELECT_LAST_HASH =
            "SELECT record_hash FROM banking.audit_log ORDER BY audit_id DESC LIMIT 1";
    private static final String INSERT = """
            INSERT INTO banking.audit_log
                (occurred_at, actor_type, actor_user_id, actor_role, action, entity_type, entity_id,
                 before_state, after_state, amount, currency, correlation_id, request_id, outcome,
                 client_info_masked, prev_hash, record_hash, hash_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            RETURNING audit_id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcAuditWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditRecordRef append(ValidatedAuditEvent event) {
        jdbc.query(LOCK_CHAIN, resultSet -> null, AuditChain.CHAIN_LOCK_KEY);

        List<String> hashes = jdbc.queryForList(SELECT_LAST_HASH, String.class);
        String prevHash = hashes.isEmpty() ? AuditChain.GENESIS_PREV_HASH : hashes.get(0).trim();
        String recordHash = AuditChain.recordHash(event.canonicalJson(), prevHash);

        Long auditId = jdbc.queryForObject(INSERT, Long.class,
                OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC),
                event.actorType().name(),
                event.actorUserId(),
                event.actorRole(),
                event.action().name(),
                event.entityType(),
                event.entityId(),
                jsonb(event.beforeState()),
                jsonb(event.afterState()),
                event.amount(),
                event.currency(),
                event.correlationId(),
                event.requestId(),
                event.outcome().name(),
                jsonb(event.clientInfoMasked()),
                prevHash,
                recordHash,
                event.hashVersion());
        if (auditId == null) {
            throw new IllegalStateException("Audit insert returned no identifier");
        }
        return new AuditRecordRef(auditId, recordHash);
    }

    private String jsonb(java.util.Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            // Never echo the state map in the failure message.
            throw new IllegalStateException("Audit state is not serializable");
        }
    }
}
