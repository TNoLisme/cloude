package com.simulatedbank.banking.notification.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.notification.domain.NotificationChannel;
import com.simulatedbank.banking.notification.domain.NotificationItem;
import com.simulatedbank.banking.notification.domain.NotificationStatus;
import com.simulatedbank.banking.notification.domain.NotificationType;
import org.springframework.beans.factory.annotation.Autowired;
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
 * JDBC repository for managing PostgreSQL notification outbox table.
 */
@Repository
public class NotificationJdbcRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Autowired
    public NotificationJdbcRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public NotificationJdbcRepository(JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper());
    }

    public long insert(NotificationItem item) {
        String payloadJson = serializePayload(item.payloadMasked());
        Timestamp nextRetry = item.nextRetryAt() != null ? Timestamp.from(item.nextRetryAt()) : Timestamp.from(Instant.now());

        String sql = """
                INSERT INTO banking.notification
                (customer_id, transfer_id, auth_challenge_id, notification_type, channel, template_code,
                 payload_masked, status, retry_count, next_retry_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                RETURNING notification_id
                """;

        Long id = jdbc.queryForObject(
                sql,
                Long.class,
                item.customerId(),
                item.transferId(),
                item.authChallengeId(),
                item.notificationType().name(),
                item.channel().name(),
                item.templateCode(),
                payloadJson,
                item.status().name(),
                item.retryCount(),
                nextRetry,
                item.correlationId()
        );

        return id != null ? id : 0L;
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

    public Optional<NotificationItem> findById(long notificationId) {
        String sql = """
                SELECT notification_id, customer_id, transfer_id, auth_challenge_id, notification_type, channel,
                       template_code, payload_masked::text, status, retry_count, next_retry_at, sent_at,
                       failure_code, correlation_id, claim_token, locked_until, claim_version, created_at, updated_at
                FROM banking.notification
                WHERE notification_id = ?
                """;

        List<NotificationItem> results = jdbc.query(sql, new NotificationRowMapper(), notificationId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Collections.emptyMap() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize masked payload to JSON", e);
        }
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
