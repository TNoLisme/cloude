package com.simulatedbank.banking.notification.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.notification.adapter.out.persistence.NotificationEntity;
import com.simulatedbank.banking.notification.adapter.out.persistence.NotificationJpaRepository;
import com.simulatedbank.banking.notification.adapter.out.persistence.NotificationMapper;
import com.simulatedbank.banking.notification.application.port.NotificationPersistencePort;
import com.simulatedbank.banking.notification.domain.NotificationChannel;
import com.simulatedbank.banking.notification.domain.NotificationItem;
import com.simulatedbank.banking.notification.domain.NotificationStatus;
import com.simulatedbank.banking.notification.domain.NotificationType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence adapter for PostgreSQL notification outbox table.
 * Standard CRUD operations are supported via Spring Data JPA repository and MapStruct,
 * while PostgreSQL-specific concurrency operations (atomic batch claim with FOR UPDATE SKIP LOCKED
 * and fencing token status updates) are retained via JdbcTemplate with explicit technical justification.
 */
@Repository
@ConditionalOnProperty(name = "app.notification.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationJdbcRepository implements NotificationPersistencePort {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final NotificationJpaRepository jpaRepository;
    private final NotificationMapper mapper;

    @Autowired
    public NotificationJdbcRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            NotificationJpaRepository jpaRepository,
            NotificationMapper mapper
    ) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc is required");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.jpaRepository = java.util.Objects.requireNonNull(jpaRepository, "jpaRepository is required");
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper is required");
    }

    /**
     * Test-only fallback constructor for isolated DB unit tests executing without Spring application context.
     */
    public NotificationJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc is required");
        this.objectMapper = new ObjectMapper();
        this.jpaRepository = null;
        this.mapper = null;
    }

    /**
     * Inserts an outbox notification item using Spring Data JPA Entity persistence.
     * Ordinary CRUD persistence delegates strictly to NotificationJpaRepository and MapStruct.
     */
    public long insert(NotificationItem item) {
        if (jpaRepository == null || mapper == null) {
            throw new IllegalStateException("NotificationJpaRepository and NotificationMapper are required for ordinary entity persistence");
        }
        NotificationEntity entity = mapper.toEntity(item);
        if (entity.getNextRetryAt() == null) {
            entity.setNextRetryAt(item.nextRetryAt() != null ? item.nextRetryAt() : Instant.now());
        }
        NotificationEntity saved = jpaRepository.save(entity);
        return saved.getNotificationId() != null ? saved.getNotificationId() : 0L;
    }

    public List<NotificationItem> claimBatch(int batchSize, Duration leaseDuration, String claimToken) {
        long leaseSeconds = leaseDuration.toSeconds();

        String sql = """
                UPDATE banking.notification
                SET status = 'RETRYING',
                    locked_until = clock_timestamp() + (? * interval '1 second'),
                    next_retry_at = clock_timestamp() + (? * interval '1 second'),
                    claim_token = ?,
                    claim_version = claim_version + 1,
                    retry_count = retry_count + 1,
                    updated_at = clock_timestamp()
                WHERE notification_id IN (
                    SELECT notification_id
                    FROM banking.notification
                    WHERE (status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= clock_timestamp()))
                       OR (status = 'RETRYING'
                           AND (locked_until IS NULL OR locked_until <= clock_timestamp())
                           AND (next_retry_at IS NULL OR next_retry_at <= clock_timestamp()))
                    ORDER BY created_at ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING notification_id, customer_id, transfer_id, auth_challenge_id, notification_type, channel,
                          template_code, payload_masked::text, status, retry_count, next_retry_at, sent_at,
                          failure_code, correlation_id, claim_token, locked_until, claim_version, created_at, updated_at
                """;

        return jdbc.query(sql, new NotificationRowMapper(), leaseSeconds, leaseSeconds, claimToken, batchSize);
    }

    public boolean markSent(long notificationId, String claimToken, Instant sentAt) {
        String sql = """
                UPDATE banking.notification
                SET status = 'SENT',
                    sent_at = ?,
                    claim_token = NULL,
                    locked_until = NULL,
                    updated_at = clock_timestamp()
                WHERE notification_id = ? AND claim_token = ?
                """;

        int affected = jdbc.update(sql, Timestamp.from(sentAt), notificationId, claimToken);
        return affected > 0;
    }

    public boolean markRetryOrFailed(
            long notificationId,
            String claimToken,
            NotificationStatus newStatus,
            Instant nextRetryAt,
            String failureCode
    ) {
        String sql = """
                UPDATE banking.notification
                SET status = ?,
                    next_retry_at = ?,
                    failure_code = ?,
                    claim_token = NULL,
                    locked_until = NULL,
                    updated_at = clock_timestamp()
                WHERE notification_id = ? AND claim_token = ?
                """;

        Timestamp nextRetry = nextRetryAt != null ? Timestamp.from(nextRetryAt) : null;
        int affected = jdbc.update(sql, newStatus.name(), nextRetry, failureCode, notificationId, claimToken);
        return affected > 0;
    }

    /**
     * Looks up an outbox notification item by ID using Spring Data JPA.
     * Ordinary CRUD lookup delegates strictly to NotificationJpaRepository and MapStruct.
     */
    public Optional<NotificationItem> findById(long notificationId) {
        if (jpaRepository == null || mapper == null) {
            throw new IllegalStateException("NotificationJpaRepository and NotificationMapper are required for entity lookup");
        }
        return jpaRepository.findById(notificationId).map(mapper::toDomain);
    }

    private class NotificationRowMapper implements RowMapper<NotificationItem> {
        @Override
        public NotificationItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            String payloadJson = rs.getString("payload_masked");
            Map<String, Object> payload;
            try {
                payload = objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {});
            } catch (JsonProcessingException e) {
                payload = Collections.emptyMap();
            }

            Timestamp nextRetry = rs.getTimestamp("next_retry_at");
            Timestamp sentAt = rs.getTimestamp("sent_at");
            Timestamp lockedUntil = rs.getTimestamp("locked_until");
            Timestamp createdAt = rs.getTimestamp("created_at");
            Timestamp updatedAt = rs.getTimestamp("updated_at");

            return new NotificationItem(
                    rs.getLong("notification_id"),
                    rs.getLong("customer_id"),
                    rs.getObject("transfer_id", Long.class),
                    rs.getObject("auth_challenge_id", Long.class),
                    NotificationType.valueOf(rs.getString("notification_type")),
                    NotificationChannel.valueOf(rs.getString("channel")),
                    rs.getString("template_code"),
                    payload,
                    NotificationStatus.valueOf(rs.getString("status")),
                    rs.getInt("retry_count"),
                    nextRetry != null ? nextRetry.toInstant() : null,
                    sentAt != null ? sentAt.toInstant() : null,
                    rs.getString("failure_code"),
                    rs.getString("correlation_id"),
                    rs.getString("claim_token"),
                    lockedUntil != null ? lockedUntil.toInstant() : null,
                    rs.getLong("claim_version"),
                    createdAt != null ? createdAt.toInstant() : null,
                    updatedAt != null ? updatedAt.toInstant() : null
            );
        }
    }
}
