package com.simulatedbank.banking.identity.adapter.out;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.application.SessionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC implementation cho `banking.session` (04 §4.2.3). DB chỉ lưu hash token/CSRF. */
@Repository
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcSessionRepository implements SessionRepository {

    private static final String INSERT_SESSION = """
            INSERT INTO banking.session
                (session_token_hash, csrf_token_hash, user_id, status, idle_expires_at, absolute_expires_at, device_id)
            VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)
            RETURNING session_id
            """;

    private static final String REVOKE = """
            UPDATE banking.session
               SET status = 'LOGGED_OUT', logged_out_at = now()
             WHERE session_token_hash = ? AND status = 'ACTIVE'
            RETURNING session_id
            """;

    private static final String ROTATE_CSRF = """
            UPDATE banking.session
               SET csrf_token_hash = ?
             WHERE session_token_hash = ? AND status = 'ACTIVE'
               AND idle_expires_at > now() AND absolute_expires_at > now()
               AND (device_id IS NULL OR EXISTS (
                   SELECT 1 FROM banking.device AS device
                    WHERE device.device_id = banking.session.device_id
                      AND device.user_id = banking.session.user_id AND device.status = 'ACTIVE'))
            RETURNING session_id, user_id, csrf_token_hash, idle_expires_at, absolute_expires_at
            """;

    private static final String VALIDATE_AND_REFRESH = """
            UPDATE banking.session
               SET last_activity_at = now(),
                   idle_expires_at = LEAST(now() + make_interval(secs => ?), absolute_expires_at)
             WHERE session_token_hash = ? AND status = 'ACTIVE'
               AND idle_expires_at > now() AND absolute_expires_at > now()
               AND (device_id IS NULL OR EXISTS (
                   SELECT 1 FROM banking.device AS device
                    WHERE device.device_id = banking.session.device_id
                      AND device.user_id = banking.session.user_id AND device.status = 'ACTIVE'))
            RETURNING session_id, user_id, csrf_token_hash, idle_expires_at, absolute_expires_at
            """;

    private final JdbcTemplate jdbc;

    public JdbcSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public long createSession(CreateSessionCommand command) {
        if (command.deviceId() != null && jdbc.queryForList("""
                SELECT device_id FROM banking.device
                 WHERE device_id = ? AND user_id = ? AND status = 'ACTIVE'
                 FOR UPDATE
                """, Long.class, command.deviceId(), command.userId()).isEmpty()) {
            throw new BankingBusinessException(ErrorCode.AUTH_DEVICE_NOT_TRUSTED);
        }
        return jdbc.queryForObject(INSERT_SESSION, Long.class, command.sessionTokenHash(),
                command.csrfTokenHash(), command.userId(),
                Timestamp.from(command.idleExpiresAt()), Timestamp.from(command.absoluteExpiresAt()),
                command.deviceId());
    }

    @Override
    @Transactional
    public boolean revokeByTokenHash(String sessionTokenHash) {
        List<Long> revoked = jdbc.queryForList(REVOKE, Long.class, sessionTokenHash);
        return !revoked.isEmpty();
    }

    @Override
    @Transactional
    public Optional<ActiveSession> rotateCsrfToken(String sessionTokenHash, String newCsrfTokenHash) {
        List<ActiveSession> rows = jdbc.query(ROTATE_CSRF, (rs, rowNum) -> new ActiveSession(
                rs.getLong("session_id"), rs.getLong("user_id"), rs.getString("csrf_token_hash"),
                toInstant(rs.getTimestamp("idle_expires_at")),
                toInstant(rs.getTimestamp("absolute_expires_at"))),
                newCsrfTokenHash, sessionTokenHash);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<ActiveSession> validateAndRefresh(String sessionTokenHash, long idleSeconds) {
        List<ActiveSession> rows = jdbc.query(VALIDATE_AND_REFRESH, (rs, rowNum) -> new ActiveSession(
                rs.getLong("session_id"), rs.getLong("user_id"), rs.getString("csrf_token_hash"),
                toInstant(rs.getTimestamp("idle_expires_at")),
                toInstant(rs.getTimestamp("absolute_expires_at"))),
                idleSeconds, sessionTokenHash);
        return rows.stream().findFirst();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
