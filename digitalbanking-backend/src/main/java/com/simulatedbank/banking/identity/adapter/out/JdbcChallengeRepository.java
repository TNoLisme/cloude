package com.simulatedbank.banking.identity.adapter.out;

import com.simulatedbank.banking.identity.application.ChallengeRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/**
 * JDBC implementation cho `banking.auth_challenge` (BE-ID-IMP-05; 04 §4.2.5). Mọi mutation là
 * conditional UPDATE/INSERT có RETURNING — atomic consume/verify/lock/expire chống race
 * (design/02 §5.1–§5.2, §9.1; T-STEP-01). Không chứa otp_hash trong snapshot; không log OTP/hash.
 *
 * <p>`@Transactional` (REQUIRED) trên mọi method: Hikari chạy `auto-commit: false`
 * (application.yml), statement ngoài transaction sẽ không bao giờ commit. Verify gọi bump/
 * expire ngoài TX caller → method mở TX ngắn riêng và COMMIT (rejection phải giữ bộ đếm);
 * consume được gọi trong T1 caller → JOIN đúng hợp đồng design/05 §5.2.</p>
 */
@Repository
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcChallengeRepository implements ChallengeRepository {

    private static final String SNAPSHOT_COLUMNS = """
            challenge_id, challenge_ref, user_id, purpose, method, channel, status, attempt_count,
            max_attempts, expires_at, created_at, verified_at, consumed_at, request_hash, idempotency_key,
            otp_hash, device_id
            """;

    private static final RowMapper<ChallengeSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) -> new ChallengeSnapshot(
            rs.getLong("challenge_id"),
            rs.getString("challenge_ref"),
            rs.getLong("user_id"),
            rs.getString("purpose"),
            rs.getString("method"),
            rs.getString("channel"),
            rs.getString("status"),
            rs.getInt("attempt_count"),
            rs.getInt("max_attempts"),
            toInstant(rs.getTimestamp("expires_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("verified_at")),
            toInstant(rs.getTimestamp("consumed_at")),
            rs.getString("request_hash"),
            rs.getString("idempotency_key"),
            rs.getString("otp_hash"),
            rs.getObject("device_id", Long.class));

    private static final String INSERT = """
            INSERT INTO banking.auth_challenge
                (challenge_ref, user_id, purpose, method, channel, otp_hash, request_hash,
                 idempotency_key, status, attempt_count, max_attempts, expires_at, correlation_id, device_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', 0, ?, ?, ?, ?)
            RETURNING %s
            """.formatted(SNAPSHOT_COLUMNS);

    private static final String FIND_BY_REF_AND_USER = """
            SELECT %s FROM banking.auth_challenge
             WHERE challenge_ref = ? AND user_id = ?
            """.formatted(SNAPSHOT_COLUMNS);

    /** Partial unique active step-up (uq_auth_challenge_active_stepup): reissue idempotent. */
    private static final String FIND_ACTIVE_BY_KEY = """
            SELECT %s FROM banking.auth_challenge
             WHERE purpose = 'TRANSFER_STEP_UP'
               AND idempotency_key = ?
               AND request_hash = ?
               AND status IN ('CREATED', 'SENT', 'VERIFIED')
               AND consumed_at IS NULL
            """.formatted(SNAPSHOT_COLUMNS);

    private static final String EXPIRE_IF_DUE = """
            UPDATE banking.auth_challenge
               SET status = 'EXPIRED'
             WHERE challenge_id = ?
               AND status IN ('CREATED', 'SENT', 'VERIFIED')
               AND consumed_at IS NULL
               AND expires_at <= clock_timestamp()
            RETURNING challenge_id
            """;

    /** One-statement bump: attempt+1, đạt ngưỡng → LOCKED (CHECK attempt_count <= max_attempts giữ nguyên). */
    private static final String BUMP_FAILED_ATTEMPT = """
            UPDATE banking.auth_challenge
               SET attempt_count = attempt_count + 1,
                   status = CASE WHEN attempt_count + 1 >= ? THEN 'LOCKED' ELSE status END
             WHERE challenge_id = ?
               AND status = 'SENT'
               AND consumed_at IS NULL AND expires_at > clock_timestamp()
            RETURNING attempt_count, status
            """;

    private static final String MARK_VERIFIED = """
            UPDATE banking.auth_challenge
               SET attempt_count = attempt_count + 1,
                   status = 'VERIFIED',
                   verified_at = clock_timestamp()
             WHERE challenge_id = ?
               AND status = 'SENT'
               AND attempt_count = ?
               AND attempt_count + 1 <= max_attempts
               AND consumed_at IS NULL AND expires_at > clock_timestamp()
            RETURNING challenge_id
            """;

    /**
     * Lock before sampling wall time: UPDATE predicates (even clock_timestamp()) can run
     * before a row-lock wait whose holder later rolls back. Both statements JOIN caller T1.
     * Compare unbounded text, never cast caller proof to truncating CHAR/VARCHAR widths;
     * text equality also preserves a caller's trailing spaces instead of CHAR padding rules.
     */
    private static final String LOCK_FOR_CONSUME = """
            SELECT challenge_id FROM banking.auth_challenge
             WHERE challenge_ref = ? AND user_id = ?
             FOR UPDATE
            """;

    private static final String CONSUME = """
            WITH checked AS MATERIALIZED (SELECT clock_timestamp() AS consumed_at)
            UPDATE banking.auth_challenge AS challenge
               SET consumed_at = checked.consumed_at
              FROM checked
             WHERE challenge.challenge_id = ?
               AND challenge.purpose = ?
               AND challenge.status = 'VERIFIED'
               AND challenge.consumed_at IS NULL
               AND challenge.expires_at > checked.consumed_at
               AND challenge.request_hash::text IS NOT DISTINCT FROM ?::text
               AND challenge.idempotency_key::text IS NOT DISTINCT FROM ?::text
            RETURNING challenge.challenge_id, challenge.consumed_at
            """;

    private final JdbcTemplate jdbc;

    public JdbcChallengeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ChallengeSnapshot insert(NewChallenge command) {
        List<ChallengeSnapshot> rows = jdbc.query(INSERT, SNAPSHOT_MAPPER,
                UUID.randomUUID(),
                command.userId(),
                command.purpose(),
                command.method(),
                command.channel(),
                command.otpHash(),
                command.requestHash(),
                command.idempotencyKey(),
                command.maxAttempts(),
                Timestamp.from(command.expiresAt()),
                command.correlationId(), command.deviceId());
        return requireOne(rows);
    }

    @Override
    @Transactional
    public Optional<ChallengeSnapshot> findByRefAndUser(String challengeRef, long userId) {
        UUID ref = parseRef(challengeRef);
        return ref == null || userId <= 0 ? Optional.empty() : queryOne(FIND_BY_REF_AND_USER, ref, userId);
    }

    @Override
    @Transactional
    public Optional<ChallengeSnapshot> findByRef(String challengeRef) {
        UUID ref = parseRef(challengeRef);
        return ref == null ? Optional.empty() : queryOne(
                "SELECT " + SNAPSHOT_COLUMNS + " FROM banking.auth_challenge WHERE challenge_ref = ?", ref);
    }

    @Override
    @Transactional
    public Optional<ChallengeSnapshot> findActiveByIdempotencyKey(String idempotencyKey, String requestHash) {
        return queryOne(FIND_ACTIVE_BY_KEY, idempotencyKey, requestHash);
    }

    @Override
    @Transactional
    public boolean expireIfDue(long challengeId) {
        return !jdbc.query(EXPIRE_IF_DUE,
                (rs, rowNum) -> rs.getLong("challenge_id"), challengeId).isEmpty();
    }

    @Override
    @Transactional
    public Optional<FailedAttemptOutcome> recordFailedVerifyAttempt(long challengeId, int maxAttempts) {
        List<FailedAttemptOutcome> rows = jdbc.query(BUMP_FAILED_ATTEMPT,
                (rs, rowNum) -> new FailedAttemptOutcome(rs.getInt("attempt_count"), rs.getString("status")),
                maxAttempts, challengeId);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public boolean markVerified(long challengeId, int expectedAttemptCount) {
        return !jdbc.query(MARK_VERIFIED,
                (rs, rowNum) -> rs.getLong("challenge_id"), challengeId, expectedAttemptCount).isEmpty();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ConsumeResult> consume(ConsumePredicate predicate) {
        UUID ref = parseRef(predicate.challengeRef());
        if (ref == null || predicate.userId() <= 0) {
            return Optional.empty();
        }
        List<Long> locked = jdbc.query(LOCK_FOR_CONSUME,
                (rs, rowNum) -> rs.getLong("challenge_id"), ref, predicate.userId());
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        List<ConsumeResult> rows = jdbc.query(CONSUME,
                (rs, rowNum) -> new ConsumeResult(rs.getLong("challenge_id"),
                        toInstant(rs.getTimestamp("consumed_at"))),
                locked.get(0),
                predicate.purpose(),
                predicate.requestHash(),
                predicate.idempotencyKey());
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public boolean markSent(long challengeId, String expectedOtpHash) {
        return !jdbc.query("""
                UPDATE banking.auth_challenge SET status = 'SENT', sent_at = clock_timestamp()
                 WHERE challenge_id = ? AND otp_hash = ? AND status = 'CREATED'
                   AND consumed_at IS NULL AND expires_at > clock_timestamp()
                RETURNING challenge_id
                """, (rs, rowNum) -> rs.getLong("challenge_id"), challengeId, expectedOtpHash).isEmpty();
    }

    @Override
    @Transactional
    public int expireDue(int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Invalid expiry batch size");
        }
        return jdbc.update("""
                WITH due AS (
                    SELECT challenge_id FROM banking.auth_challenge
                     WHERE status IN ('CREATED', 'SENT', 'VERIFIED')
                       AND consumed_at IS NULL AND expires_at <= clock_timestamp()
                     ORDER BY expires_at, challenge_id
                     LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE banking.auth_challenge c SET status = 'EXPIRED'
                  FROM due WHERE c.challenge_id = due.challenge_id
                """, batchSize);
    }

    private Optional<ChallengeSnapshot> queryOne(String sql, Object... args) {
        List<ChallengeSnapshot> rows = jdbc.query(sql, SNAPSHOT_MAPPER, args);
        return rows.stream().findFirst();
    }

    private static ChallengeSnapshot requireOne(List<ChallengeSnapshot> rows) {
        if (rows.size() != 1) {
            throw new IllegalStateException("Unexpected auth_challenge insert row count: " + rows.size());
        }
        return rows.get(0);
    }

    /** Reject malformed/noncanonical UUID text before PostgreSQL can abort the caller TX. */
    private static UUID parseRef(String value) {
        if (value == null || value.length() != 36) {
            return null;
        }
        try {
            UUID ref = UUID.fromString(value);
            return ref.toString().equalsIgnoreCase(value) ? ref : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
