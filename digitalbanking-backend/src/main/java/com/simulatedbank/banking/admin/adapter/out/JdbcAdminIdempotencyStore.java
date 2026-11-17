package com.simulatedbank.banking.admin.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.admin.application.port.AdminIdempotencyStore;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyOperation;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyRecord;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyScope;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter for Admin idempotency. Native SQL is required for {@code INSERT ... ON CONFLICT DO NOTHING}
 * (insert-first without aborting the caller transaction) and {@code FOR UPDATE SKIP LOCKED} purge.
 */
@Component
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
class JdbcAdminIdempotencyStore implements AdminIdempotencyStore {

    private static final String COLUMNS = "record_id, idempotency_key, actor_id, operation, scope_key, "
            + "request_hash, status, response_status, response_body::text AS response_body_text, approval_id, "
            + "created_at, completed_at, expires_at";

    private static final String INSERT = """
            INSERT INTO banking.admin_idempotency_record
                (idempotency_key, actor_id, operation, scope_key, request_hash, status, approval_id,
                 created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, 'RECEIVED', ?, ?, ?)
            ON CONFLICT (actor_id, operation, scope_key, idempotency_key) DO NOTHING
            RETURNING %s
            """.formatted(COLUMNS);

    private static final String FIND = "SELECT " + COLUMNS
            + " FROM banking.admin_idempotency_record"
            + " WHERE actor_id = ? AND operation = ? AND scope_key = ? AND idempotency_key = ?";

    private static final String COMPLETE = """
            UPDATE banking.admin_idempotency_record
               SET status = 'COMPLETED',
                   response_status = ?,
                   response_body = ?::jsonb,
                   approval_id = ?,
                   completed_at = ?,
                   expires_at = GREATEST(expires_at, ?)
             WHERE record_id = ?
               AND status = 'RECEIVED'
            """;

    private static final String PURGE = """
            DELETE FROM banking.admin_idempotency_record
             WHERE record_id IN (
                 SELECT record_id
                   FROM banking.admin_idempotency_record
                  WHERE status IN ('COMPLETED', 'FAILED')
                    AND expires_at < ?
                  ORDER BY expires_at
                  FOR UPDATE SKIP LOCKED
                  LIMIT ?)
            """;

    private static final TypeReference<Map<String, Object>> RESPONSE_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcAdminIdempotencyStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdminIdempotencyRecord> find(AdminIdempotencyScope scope, String idempotencyKey) {
        List<AdminIdempotencyRecord> rows = jdbc.query(FIND, this::map,
                scope.actorId(), scope.operation().name(), scope.scopeKey(), idempotencyKey);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AdminIdempotencyRecord> claim(AdminIdempotencyScope scope, String idempotencyKey,
            String requestHash, Instant createdAt, Instant expiresAt) {
        List<AdminIdempotencyRecord> inserted = jdbc.query(INSERT, this::map,
                idempotencyKey, scope.actorId(), scope.operation().name(), scope.scopeKey(), requestHash,
                approvalReference(scope), timestamp(createdAt), timestamp(expiresAt));
        return inserted.stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(long recordId, int responseStatus, Map<String, Object> responseBody, long approvalId,
            Instant completedAt, Instant expiresAt) {
        String body = write(responseBody);
        int updated = jdbc.update(COMPLETE, responseStatus, body, approvalId,
                timestamp(completedAt), timestamp(expiresAt), recordId);
        if (updated != 1) {
            // Never continue after losing the claimed row: the business transaction must roll back.
            throw new IllegalStateException("Idempotency record is not in RECEIVED state");
        }
    }

    @Override
    @Transactional
    public int purgeExpired(Instant now, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        return jdbc.update(PURGE, timestamp(now), batchSize);
    }

    private AdminIdempotencyRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new AdminIdempotencyRecord(
                rs.getLong("record_id"),
                new AdminIdempotencyScope(rs.getLong("actor_id"),
                        AdminIdempotencyOperation.valueOf(rs.getString("operation")),
                        rs.getString("scope_key")),
                rs.getString("request_hash"),
                AdminIdempotencyStatus.valueOf(rs.getString("status")),
                responseStatus(rs),
                read(rs.getString("response_body_text")),
                rs.getObject("approval_id", Long.class),
                instant(rs, "created_at"),
                instant(rs, "completed_at"),
                instant(rs, "expires_at"));
    }

    private Map<String, Object> read(String body) {
        if (body == null) {
            return null;
        }
        try {
            return json.readValue(body, RESPONSE_TYPE);
        } catch (JsonProcessingException invalid) {
            // Never echo the stored body.
            throw new IllegalStateException("Stored idempotency response is not valid JSON");
        }
    }

    private String write(Map<String, Object> body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Idempotency response is not serializable");
        }
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Long approvalReference(AdminIdempotencyScope scope) {
        return scope.operation() == AdminIdempotencyOperation.DECIDE_APPROVAL
                ? Long.parseLong(scope.scopeKey()) : null;
    }

    private static Integer responseStatus(ResultSet rs) throws SQLException {
        Object value = rs.getObject("response_status");
        return value == null ? null : ((Number) value).intValue();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
