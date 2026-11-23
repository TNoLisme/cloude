package com.simulatedbank.banking.identity.adapter.out;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.simulatedbank.banking.identity.application.UserAccountRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC implementation cho `banking.app_user` + `banking.auth_attempt` (04 §4.2.1, §4.2.6).
 * Failed-attempt increment là MỘT statement có điều kiện (row lock của UPDATE +
 * RETURNING) — chống lost update khi N request đồng thời (design/02 §9.1; T11).
 */
@Repository
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcUserAccountRepository implements UserAccountRepository {

    private static final String SELECT_BY_USERNAME =
            "SELECT user_id, username, user_type, password_hash, status, failed_attempt_count, locked_until "
                    + "FROM banking.app_user WHERE username = ?";

    private static final String BUMP_FAILED_ATTEMPT = """
            UPDATE banking.app_user
               SET failed_attempt_count = failed_attempt_count + 1,
                   last_failed_at = now(),
                   updated_at = now(),
                   status = CASE WHEN failed_attempt_count + 1 >= ? THEN 'LOCKED' ELSE status END,
                   locked_until = CASE WHEN failed_attempt_count + 1 >= ?
                       THEN now() + make_interval(mins => ?) ELSE locked_until END
             WHERE user_id = ?
               AND status = 'ACTIVE'
               AND (locked_until IS NULL OR locked_until <= now())
            RETURNING failed_attempt_count, locked_until
            """;

    private static final String UNLOCK_IF_EXPIRED = """
            UPDATE banking.app_user
               SET status = 'ACTIVE', failed_attempt_count = 0, locked_until = NULL, updated_at = now()
             WHERE user_id = ? AND status = 'LOCKED'
               AND locked_until IS NOT NULL AND locked_until <= now()
            RETURNING user_id
            """;

    private static final String CONFIRM_PRIMARY = """
            UPDATE banking.app_user
               SET failed_attempt_count = 0, locked_until = NULL, updated_at = now()
             WHERE user_id = ? AND status = 'ACTIVE'
               AND (locked_until IS NULL OR locked_until <= now())
            RETURNING user_id
            """;

    private static final String RESET_ON_SUCCESS = """
            UPDATE banking.app_user
               SET failed_attempt_count = 0, locked_until = NULL, last_login_at = now(), updated_at = now()
             WHERE user_id = ? AND status = 'ACTIVE'
               AND (locked_until IS NULL OR locked_until <= now())
            RETURNING user_id
            """;

    private static final String INSERT_AUTH_ATTEMPT = """
            INSERT INTO banking.auth_attempt
                (user_id, attempt_type, outcome, reason_code, correlation_id, client_info_masked)
            VALUES (?, ?, ?, ?, ?, ?::jsonb)
            """;

    private final JdbcTemplate jdbc;

    public JdbcUserAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Optional<AppUserSnapshot> findByUsername(String normalizedUsername) {
        List<AppUserSnapshot> rows = jdbc.query(SELECT_BY_USERNAME, (rs, rowNum) -> new AppUserSnapshot(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("user_type"),
                rs.getString("password_hash"),
                rs.getString("status"),
                rs.getInt("failed_attempt_count"),
                toInstant(rs.getTimestamp("locked_until"))), normalizedUsername);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<AppUserSnapshot> findById(long userId) {
        List<AppUserSnapshot> rows = jdbc.query(
                "SELECT user_id, username, user_type, password_hash, status, failed_attempt_count, locked_until "
                        + "FROM banking.app_user WHERE user_id = ?",
                (rs, rowNum) -> new AppUserSnapshot(
                        rs.getLong("user_id"), rs.getString("username"), rs.getString("user_type"),
                        rs.getString("password_hash"), rs.getString("status"),
                        rs.getInt("failed_attempt_count"), toInstant(rs.getTimestamp("locked_until"))),
                userId);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<FailedAttemptOutcome> recordFailedAttempt(long userId, int maxFailedAttempts,
            int lockDurationMinutes) {
        // SET expressions đọc giá trị row TRƯỚC update nên `failed_attempt_count + 1` là count mới.
        return jdbc.query(BUMP_FAILED_ATTEMPT, (rs, rowNum) -> new FailedAttemptOutcome(
                rs.getInt("failed_attempt_count"),
                rs.getInt("failed_attempt_count") >= maxFailedAttempts,
                toInstant(rs.getTimestamp("locked_until"))),
                maxFailedAttempts, maxFailedAttempts, lockDurationMinutes, userId).stream().findFirst();
    }

    @Override
    @Transactional
    public boolean unlockIfLockExpired(long userId) {
        List<Long> unlocked = jdbc.queryForList(UNLOCK_IF_EXPIRED, Long.class, userId);
        return !unlocked.isEmpty();
    }

    @Override
    @Transactional
    public boolean confirmPrimaryAuthentication(long userId) {
        return !jdbc.queryForList(CONFIRM_PRIMARY, Long.class, userId).isEmpty();
    }

    @Override
    @Transactional
    public boolean resetCountersAndMarkLoginSuccess(long userId) {
        List<Long> updated = jdbc.queryForList(RESET_ON_SUCCESS, Long.class, userId);
        return !updated.isEmpty();
    }

    @Override
    @Transactional
    public void recordAuthAttempt(AuthAttemptRecord record) {
        jdbc.update(INSERT_AUTH_ATTEMPT, record.userId(), record.attemptType(), record.outcome(),
                record.reasonCode(), record.correlationId(), record.clientInfoMaskedJson());
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
