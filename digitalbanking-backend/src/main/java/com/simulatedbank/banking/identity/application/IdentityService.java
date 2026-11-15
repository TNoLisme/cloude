package com.simulatedbank.banking.identity.application;

import com.simulatedbank.banking.customer.api.CustomerQueryPort;
import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.config.RequestRateLimiter;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.IdentityRoleCommandPort;
import com.simulatedbank.banking.identity.api.OtpDeliveryPort;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "banking.modules.enabled", havingValue = "true", matchIfMissing = true)
public class IdentityService implements SessionAuthenticator, StepUpChallengePort, IdentityRoleCommandPort {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final HmacService hmac;
    private final Clock clock;
    private final AppProperties properties;
    private final RequestRateLimiter rateLimiter;
    private final CustomerQueryPort customers;
    private final ObjectProvider<OtpDeliveryPort> otpDelivery;
    private final ObjectProvider<IdentitySecurityAuditSink> securityAudit;
    private final String dummyPasswordHash;

    public IdentityService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, HmacService hmac, Clock clock,
            AppProperties properties, RequestRateLimiter rateLimiter, CustomerQueryPort customers,
            ObjectProvider<OtpDeliveryPort> otpDelivery, ObjectProvider<IdentitySecurityAuditSink> securityAudit) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.hmac = hmac;
        this.clock = clock;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.customers = customers;
        this.otpDelivery = otpDelivery;
        this.securityAudit = securityAudit;
        this.dummyPasswordHash = passwordEncoder.encode(randomToken());
    }

    @Override
    @Transactional
    public Optional<SessionAuthenticator.Session> authenticate(String opaqueToken) {
        String tokenHash = HmacService.sha256Hex(opaqueToken);
        SessionRow row;
        try {
            row = jdbc.queryForObject("""
                    SELECT s.session_id, s.user_id, s.device_id, s.csrf_token_hash, s.idle_expires_at,
                           s.absolute_expires_at, u.status AS user_status, c.customer_id,
                           d.status AS device_status
                    FROM banking.session s
                    JOIN banking.app_user u ON u.user_id = s.user_id
                    LEFT JOIN banking.customer c ON c.user_id = u.user_id
                    LEFT JOIN banking.device d ON d.device_id = s.device_id
                    WHERE s.session_token_hash = ? AND s.status = 'ACTIVE'
                    FOR UPDATE OF s
                    """, this::sessionRow, tokenHash);
        } catch (EmptyResultDataAccessException absent) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (row.deviceId() != null && !"ACTIVE".equals(row.deviceStatus())) {
            jdbc.update("""
                    UPDATE banking.session SET status = 'REVOKED', revoked_at = ?, revoked_reason = 'DEVICE_REVOKED'
                    WHERE session_id = ? AND status = 'ACTIVE'
                    """, dbTime(now), row.sessionId());
            return Optional.empty();
        }
        if (!"ACTIVE".equals(row.userStatus()) || !now.isBefore(row.idleExpiresAt())
                || !now.isBefore(row.absoluteExpiresAt())) {
            jdbc.update("UPDATE banking.session SET status = 'EXPIRED' WHERE session_id = ? AND status = 'ACTIVE'",
                    row.sessionId());
            return Optional.empty();
        }
        Instant idleExpiry = min(now.plus(Duration.ofMinutes(properties.security().session().idleTimeoutMinutes())),
                row.absoluteExpiresAt());
        jdbc.update("UPDATE banking.session SET last_activity_at = ?, idle_expires_at = ? WHERE session_id = ?",
                dbTime(now), dbTime(idleExpiry), row.sessionId());
        return Optional.of(new SessionAuthenticator.Session(Long.toString(row.userId()),
                row.customerId() == null ? null : Long.toString(row.customerId()), authorities(row.userId()),
                row.csrfHash()));
    }

    @Transactional(noRollbackFor = BankingBusinessException.class)
    public LoginResult login(LoginCommand command) {
        String username = normalizeUsername(command.username());
        long retry = rateLimiter.acquire("login-username", username,
                properties.security().rateLimit().loginPerUsernamePerMinute(), Duration.ofMinutes(1));
        if (retry > 0) {
            throw new BankingBusinessException(ErrorCode.RATE_LIMITED);
        }

        UserRow user = findUserForUpdate(username).orElse(null);
        if (user == null) {
            passwordEncoder.matches(command.password(), dummyPasswordHash);
            throw new BankingBusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        Instant now = clock.instant();
        if ("DISABLED".equals(user.status())) {
            passwordEncoder.matches(command.password(), dummyPasswordHash);
            throw new BankingBusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        user = normalizeExpiredLock(user, now);
        if (!passwordEncoder.matches(command.password(), user.passwordHash())) {
            int failures = user.failedAttempts() + 1;
            boolean locked = failures >= properties.security().lockout().maxFailedAttempts();
            Instant lockedUntil = locked
                    ? now.plus(Duration.ofMinutes(properties.security().lockout().lockDurationMinutes())) : null;
            jdbc.update("""
                    UPDATE banking.app_user
                    SET failed_attempt_count = ?, last_failed_at = ?, status = ?, locked_until = ?, updated_at = ?
                    WHERE user_id = ?
                    """, failures, dbTime(now), locked ? "LOCKED" : "ACTIVE", dbTime(lockedUntil), dbTime(now),
                    user.userId());
            recordAttempt(user.userId(), "LOGIN", locked ? "LOCKED" : "FAILURE", "INVALID_CREDENTIALS");
            auditC3(user.userId(), locked ? "AUTH_ACCOUNT_LOCKED" : "AUTH_LOGIN_FAILED", "APP_USER",
                    Long.toString(user.userId()), "FAILURE");
            if (locked) {
                throw new BankingBusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED,
                        ErrorCode.AUTH_ACCOUNT_LOCKED.detail(), Map.of("lockedUntil", lockedUntil));
            }
            throw new BankingBusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        jdbc.update("""
                UPDATE banking.app_user SET failed_attempt_count = 0, locked_until = NULL, status = 'ACTIVE',
                    last_login_at = ?, updated_at = ? WHERE user_id = ?
                """, dbTime(now), dbTime(now), user.userId());
        recordAttempt(user.userId(), "LOGIN", "SUCCESS", null);

        DeviceRow device = resolveDevice(user.userId(), command, now);
        if (device != null && device.trusted()) {
            jdbc.update("UPDATE banking.device SET last_seen_at = ?, updated_at = ? WHERE device_id = ?",
                    dbTime(now), dbTime(now), device.deviceId());
            return LoginResult.authenticated(issueSession(user, device, now));
        }

        ChallengeMaterial challenge = createOtpChallenge(user.userId(), device == null ? null : device.deviceId(),
                Purpose.LOGIN_NEW_DEVICE, null, null);
        deliverOrCancel(challenge);
        return LoginResult.challenge(challenge.publicChallenge());
    }

    @Transactional(noRollbackFor = BankingBusinessException.class)
    public SessionGrant loginPasscode(PasscodeCommand command) {
        String username = normalizeUsername(command.username());
        long retry = rateLimiter.acquire("login-username", username,
                properties.security().rateLimit().loginPerUsernamePerMinute(), Duration.ofMinutes(1));
        if (retry > 0) {
            throw new BankingBusinessException(ErrorCode.RATE_LIMITED);
        }
        PasscodeRow row;
        try {
            row = jdbc.queryForObject("""
                    SELECT u.user_id, u.username, u.password_hash, u.user_type, u.status,
                           u.failed_attempt_count, u.locked_until, d.device_id, d.device_ref,
                           d.device_fingerprint_hash, d.passcode_hash, d.trusted, d.status AS device_status
                    FROM banking.app_user u
                    JOIN banking.device d ON d.user_id = u.user_id
                    WHERE u.username = ? AND d.device_ref = ?
                    FOR UPDATE OF u, d
                    """, (rs, rowNum) -> new PasscodeRow(userRow(rs, rowNum), rs.getLong("device_id"),
                            rs.getObject("device_ref", UUID.class), rs.getString("device_fingerprint_hash"),
                            rs.getString("passcode_hash"), rs.getBoolean("trusted"),
                            rs.getString("device_status")), username, command.deviceRef());
        } catch (EmptyResultDataAccessException absent) {
            passwordEncoder.matches(command.passcode(), dummyPasswordHash);
            throw new BankingBusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        Instant now = clock.instant();
        String fingerprint = hmac.hmacSha256Hex(command.deviceRef() + "|" + command.deviceFingerprint());
        if (!row.trusted() || !"ACTIVE".equals(row.deviceStatus())
                || !HmacService.constantTimeEquals(row.fingerprintHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        fingerprint.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            passwordEncoder.matches(command.passcode(), dummyPasswordHash);
            throw new BankingBusinessException(ErrorCode.AUTH_DEVICE_NOT_TRUSTED);
        }
        UserRow user = normalizeExpiredLock(row.user(), now);
        boolean matches = row.passcodeHash() != null && passwordEncoder.matches(command.passcode(), row.passcodeHash());
        if (!matches) {
            int failures = user.failedAttempts() + 1;
            boolean locked = failures >= properties.security().lockout().maxFailedAttempts();
            Instant lockedUntil = locked
                    ? now.plus(Duration.ofMinutes(properties.security().lockout().lockDurationMinutes())) : null;
            jdbc.update("""
                    UPDATE banking.app_user SET failed_attempt_count = ?, last_failed_at = ?, status = ?,
                        locked_until = ?, updated_at = ? WHERE user_id = ?
                    """, failures, dbTime(now), locked ? "LOCKED" : "ACTIVE", dbTime(lockedUntil), dbTime(now),
                    user.userId());
            recordAttempt(user.userId(), "PASSCODE", locked ? "LOCKED" : "FAILURE", "INVALID_PASSCODE");
            auditC3(user.userId(), locked ? "AUTH_ACCOUNT_LOCKED" : "AUTH_LOGIN_FAILED", "APP_USER",
                    Long.toString(user.userId()), "FAILURE");
            if (locked) {
                throw new BankingBusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED,
                        ErrorCode.AUTH_ACCOUNT_LOCKED.detail(), Map.of("lockedUntil", lockedUntil));
            }
            throw new BankingBusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        jdbc.update("""
                UPDATE banking.app_user SET failed_attempt_count = 0, locked_until = NULL, status = 'ACTIVE',
                    last_login_at = ?, updated_at = ? WHERE user_id = ?
                """, dbTime(now), dbTime(now), user.userId());
        jdbc.update("UPDATE banking.device SET last_seen_at = ?, updated_at = ? WHERE device_id = ?",
                dbTime(now), dbTime(now), row.deviceId());
        recordAttempt(user.userId(), "PASSCODE", "SUCCESS", null);
        return issueSession(user, new DeviceRow(row.deviceId(), user.userId(), true, "ACTIVE", row.deviceRef()), now);
    }

    @Transactional(noRollbackFor = BankingBusinessException.class)
    public SessionGrant verifyLoginOtp(UUID challengeRef, String otp) {
        ChallengeRow challenge = lockChallenge(challengeRef);
        if (challenge.purpose() != Purpose.LOGIN_NEW_DEVICE) {
            throw new BankingBusinessException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        }
        Instant now = clock.instant();
        UserRow user = normalizeExpiredLock(findEnabledUserForUpdateById(challenge.userId()), now);
        DeviceRow device = null;
        if (challenge.deviceId() != null) {
            device = findDeviceForUpdate(challenge.deviceId());
            if (device.userId() != user.userId() || !"ACTIVE".equals(device.status())) {
                throw new BankingBusinessException(ErrorCode.AUTH_DEVICE_NOT_TRUSTED);
            }
        }
        verifyOtp(challenge, otp, "OTP");
        if (device != null) {
            int trusted = jdbc.update("""
                    UPDATE banking.device SET trusted = TRUE, trusted_at = COALESCE(trusted_at, ?),
                        last_seen_at = ?, updated_at = ? WHERE device_id = ? AND user_id = ? AND status = 'ACTIVE'
                    """, dbTime(now), dbTime(now), dbTime(now), device.deviceId(), user.userId());
            if (trusted != 1) {
                throw new BankingBusinessException(ErrorCode.AUTH_DEVICE_NOT_TRUSTED);
            }
            device = new DeviceRow(device.deviceId(), device.userId(), true, device.status(), device.deviceRef());
        }
        recordAttempt(user.userId(), "OTP", "SUCCESS", null);
        return issueSession(user, device, now);
    }

    @Transactional(noRollbackFor = BankingBusinessException.class)
    public StepUpVerification verifyStepUp(long authenticatedUserId, UUID challengeRef, String method, String secret) {
        ChallengeRow challenge = lockChallenge(challengeRef);
        if (challenge.userId() != authenticatedUserId || challenge.purpose() == Purpose.LOGIN_NEW_DEVICE) {
            throw new BankingBusinessException(ErrorCode.AUTH_CHALLENGE_NOT_FOUND);
        }
        if (!"OTP".equals(method)) {
            throw new BankingBusinessException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        }
        verifyOtp(challenge, secret, method);
        recordAttempt(authenticatedUserId, "STEP_UP", "SUCCESS", null);
        return new StepUpVerification(challenge.challengeRef(), challenge.purpose(), clock.instant(), challenge.expiresAt());
    }

    @Transactional(noRollbackFor = BankingBusinessException.class)
    public Challenge resend(UUID challengeRef, Long authenticatedUserId) {
        ChallengeRow challenge = lockChallenge(challengeRef);
        if (challenge.purpose() != Purpose.LOGIN_NEW_DEVICE
                && (authenticatedUserId == null || challenge.userId() != authenticatedUserId)) {
            throw new BankingBusinessException(ErrorCode.AUTH_CHALLENGE_NOT_FOUND);
        }
        Instant now = clock.instant();
        ensureChallengeUsable(challenge, now);
        if (challenge.resendCount() >= properties.security().otp().maxResendCount()
                || challenge.lastSentAt() != null && now.isBefore(challenge.lastSentAt()
                        .plusSeconds(properties.security().otp().resendCooldownSeconds()))) {
            throw new BankingBusinessException(ErrorCode.RATE_LIMITED);
        }
        String otp = oneTimeCode();
        String otpHash = hmac.hmacSha256Hex(challenge.challengeRef() + ":" + otp);
        Instant expiry = now.plusSeconds(properties.security().otp().ttlSeconds());
        int updated = jdbc.update("""
                UPDATE banking.auth_challenge SET otp_hash = ?, resend_count = resend_count + 1,
                    sent_at = ?, last_sent_at = ?, expires_at = ?, status = 'SENT'
                WHERE challenge_id = ? AND status IN ('CREATED', 'SENT')
                """, otpHash, dbTime(now), dbTime(now), dbTime(expiry), challenge.challengeId());
        if (updated != 1) {
            throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        Challenge refreshed = publicChallenge(challenge.challengeRef(), challenge.purpose(), expiry,
                challenge.maxAttempts(), challenge.attemptCount());
        deliverOrCancel(new ChallengeMaterial(challenge.challengeId(), challenge.userId(), otp, refreshed));
        return refreshed;
    }

    @Override
    @Transactional(noRollbackFor = BankingBusinessException.class)
    public Challenge create(long userId, Purpose purpose, String requestHash, String idempotencyKey) {
        if (purpose == Purpose.LOGIN_NEW_DEVICE) {
            throw new IllegalArgumentException("Login challenges require device context");
        }
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid challenge request hash");
        }
        UserRow user = findUser(userId);
        if (!"ACTIVE".equals(user.status())) {
            throw new BankingBusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED);
        }
        ChallengeMaterial material = createOtpChallenge(userId, null, purpose, requestHash, idempotencyKey);
        deliverOrCancel(material);
        return material.publicChallenge();
    }

    @Override
    @Transactional
    public long consumeVerifiedChallenge(UUID challengeId, long userId, Purpose expectedPurpose,
            String expectedRequestHash) {
        Instant now = clock.instant();
        List<Long> ids = jdbc.query("""
                UPDATE banking.auth_challenge
                SET status = 'CONSUMED', consumed_at = ?
                WHERE challenge_ref = ? AND user_id = ? AND purpose = ? AND request_hash = ?
                  AND status = 'VERIFIED' AND consumed_at IS NULL AND expires_at > ?
                RETURNING challenge_id
                """, (rs, rowNum) -> rs.getLong(1), dbTime(now), challengeId, userId, expectedPurpose.name(),
                expectedRequestHash, dbTime(now));
        if (ids.size() != 1) {
            throw new BankingBusinessException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        }
        return ids.getFirst();
    }

    @Override
    @Transactional
    public void apply(RoleChange command) {
        UserRow target = findUserForUpdateById(command.targetUserId());
        if (command.operation() == Operation.ASSIGN_ROLE || command.operation() == Operation.REVOKE_ROLE) {
            Long roleId;
            try {
                roleId = jdbc.queryForObject("SELECT role_id FROM banking.role WHERE role_code = ? AND status = 'ACTIVE'",
                        Long.class, command.code());
            } catch (EmptyResultDataAccessException absent) {
                throw new BankingBusinessException(ErrorCode.ROLE_NOT_FOUND);
            }
            if (("CUSTOMER".equals(command.code())) != "CUSTOMER".equals(target.userType())) {
                throw new BankingBusinessException(ErrorCode.ROLE_CONFLICT);
            }
            if (command.operation() == Operation.ASSIGN_ROLE) {
                jdbc.update("""
                        INSERT INTO banking.user_role(user_id, role_id, assigned_by)
                        VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                        """, target.userId(), roleId, command.changedByUserId());
            } else {
                jdbc.update("DELETE FROM banking.user_role WHERE user_id = ? AND role_id = ?",
                        target.userId(), roleId);
            }
        } else {
            if (!"CHECKER".equals(command.code()) || !"STAFF".equals(target.userType())) {
                throw new BankingBusinessException(ErrorCode.ROLE_CONFLICT);
            }
            if (command.operation() == Operation.GRANT_PRIVILEGE) {
                jdbc.update("""
                        INSERT INTO banking.user_privilege(user_id, privilege_code, status, granted_by)
                        VALUES (?, 'CHECKER', 'ACTIVE', ?)
                        ON CONFLICT (user_id, privilege_code) DO UPDATE SET status = 'ACTIVE', granted_by = EXCLUDED.granted_by
                        """, target.userId(), command.changedByUserId());
            } else {
                jdbc.update("""
                        UPDATE banking.user_privilege SET status = 'REVOKED'
                        WHERE user_id = ? AND privilege_code = 'CHECKER'
                        """, target.userId());
            }
        }
        Instant now = clock.instant();
        jdbc.update("""
                UPDATE banking.session SET status = 'REVOKED', revoked_at = ?, revoked_reason = 'ROLE_CHANGED'
                WHERE user_id = ? AND status = 'ACTIVE'
                """, dbTime(now), target.userId());
    }

    @Transactional
    public void logout(String opaqueToken) {
        Instant now = clock.instant();
        List<Long> users = jdbc.query("""
                UPDATE banking.session SET status = 'LOGGED_OUT', logged_out_at = ?
                WHERE session_token_hash = ? AND status = 'ACTIVE'
                RETURNING user_id
                """, (rs, rowNum) -> rs.getLong(1), dbTime(now), HmacService.sha256Hex(opaqueToken));
        if (users.size() != 1) {
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        auditC3(users.getFirst(), "AUTH_SESSION_LOGOUT", "SESSION", "CURRENT", "SUCCESS");
    }

    @Transactional
    public SessionGrant rotateCsrfAndReadSession(String opaqueToken) {
        String hash = HmacService.sha256Hex(opaqueToken);
        SessionDetails details;
        try {
            details = jdbc.queryForObject("""
                    SELECT s.session_id, s.user_id, s.device_id, s.idle_expires_at, s.absolute_expires_at,
                           u.username, u.user_type, u.status AS user_status, c.customer_id,
                           d.status AS device_status
                    FROM banking.session s JOIN banking.app_user u ON u.user_id = s.user_id
                    LEFT JOIN banking.customer c ON c.user_id = u.user_id
                    LEFT JOIN banking.device d ON d.device_id = s.device_id
                    WHERE s.session_token_hash = ? AND s.status = 'ACTIVE'
                    FOR UPDATE OF s
                    """, this::sessionDetails, hash);
        } catch (EmptyResultDataAccessException absent) {
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        Instant now = clock.instant();
        if (!"ACTIVE".equals(details.userStatus())
                || details.deviceId() != null && !"ACTIVE".equals(details.deviceStatus())
                || !now.isBefore(details.idleExpiresAt()) || !now.isBefore(details.absoluteExpiresAt())) {
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        String csrf = randomToken();
        jdbc.update("UPDATE banking.session SET csrf_token_hash = ? WHERE session_id = ?",
                HmacService.sha256Hex(csrf), details.sessionId());
        return grant(details, opaqueToken, csrf);
    }

    @Transactional
    public long createUser(String username, String rawPassword, String userType, String roleCode) {
        String normalized = normalizeUsername(username);
        if (rawPassword == null || rawPassword.isEmpty() || rawPassword.length() > 128
                || !Set.of("CUSTOMER", "STAFF").contains(userType)) {
            throw new IllegalArgumentException("Invalid user provisioning input");
        }
        if (("CUSTOMER".equals(roleCode)) != "CUSTOMER".equals(userType)) {
            throw new IllegalArgumentException("Role is incompatible with user type");
        }
        Long userId = jdbc.queryForObject("""
                INSERT INTO banking.app_user(username, password_hash, user_type)
                VALUES (?, ?, ?) RETURNING user_id
                """, Long.class, normalized, passwordEncoder.encode(rawPassword), userType);
        int assigned = jdbc.update("""
                INSERT INTO banking.user_role(user_id, role_id)
                SELECT ?, role_id FROM banking.role WHERE role_code = ? AND status = 'ACTIVE'
                """, userId, roleCode);
        if (assigned != 1) {
            throw new BankingBusinessException(ErrorCode.ROLE_NOT_FOUND);
        }
        return userId;
    }

    @Transactional
    public void setPasscode(long userId, UUID deviceRef, String passcode) {
        if (passcode == null || !passcode.matches("[0-9]{6,8}")) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        int updated = jdbc.update("""
                UPDATE banking.device SET passcode_hash = ?, updated_at = ?
                WHERE user_id = ? AND device_ref = ? AND trusted = TRUE AND status = 'ACTIVE'
                """, passwordEncoder.encode(passcode), dbTime(clock.instant()), userId, deviceRef);
        if (updated != 1) {
            throw new BankingBusinessException(ErrorCode.AUTH_DEVICE_NOT_TRUSTED);
        }
    }

    @Transactional
    public void revokePasscode(long userId, UUID deviceRef) {
        Instant now = clock.instant();
        int updated = jdbc.update("""
                UPDATE banking.device SET passcode_hash = NULL, updated_at = ?
                WHERE user_id = ? AND device_ref = ? AND status = 'ACTIVE'
                """, dbTime(now), userId, deviceRef);
        if (updated != 1) {
            throw new BankingBusinessException(ErrorCode.AUTH_DEVICE_NOT_TRUSTED);
        }
        jdbc.update("""
                UPDATE banking.session SET status = 'REVOKED', revoked_at = ?, revoked_reason = 'PASSCODE_REVOKED'
                WHERE user_id = ? AND device_id = (SELECT device_id FROM banking.device WHERE device_ref = ?)
                  AND status = 'ACTIVE'
                """, dbTime(now), userId, deviceRef);
    }

    @Transactional
    public void revokeDevice(long userId, UUID deviceRef) {
        Instant now = clock.instant();
        List<Long> deviceIds = jdbc.query("""
                UPDATE banking.device SET status = 'REVOKED', trusted = FALSE, passcode_hash = NULL, updated_at = ?
                WHERE user_id = ? AND device_ref = ? AND status = 'ACTIVE'
                RETURNING device_id
                """, (rs, rowNum) -> rs.getLong(1), dbTime(now), userId, deviceRef);
        if (deviceIds.size() != 1) {
            throw new BankingBusinessException(ErrorCode.AUTH_DEVICE_NOT_TRUSTED);
        }
        jdbc.update("""
                UPDATE banking.session SET status = 'REVOKED', revoked_at = ?, revoked_reason = 'DEVICE_REVOKED'
                WHERE user_id = ? AND device_id = ? AND status = 'ACTIVE'
                """, dbTime(now), userId, deviceIds.getFirst());
        auditC3(userId, "AUTH_DEVICE_REVOKED", "DEVICE", deviceRef.toString(), "SUCCESS");
    }

    private DeviceRow resolveDevice(long userId, LoginCommand command, Instant now) {
        boolean hasRef = command.deviceRef() != null;
        boolean hasFingerprint = command.deviceFingerprint() != null && !command.deviceFingerprint().isBlank();
        if (hasRef != hasFingerprint) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (!hasRef) {
            return null;
        }
        String fingerprintHash = hmac.hmacSha256Hex(command.deviceRef() + "|" + command.deviceFingerprint());
        try {
            DeviceRow existing = jdbc.queryForObject("""
                    SELECT device_id, user_id, trusted, status, device_ref
                    FROM banking.device WHERE device_ref = ?
                    """, this::deviceRow, command.deviceRef());
            if (existing.userId() != userId || !"ACTIVE".equals(existing.status())
                    || !Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT device_fingerprint_hash = ? FROM banking.device WHERE device_id = ?
                            """, Boolean.class, fingerprintHash, existing.deviceId()))) {
                throw new BankingBusinessException(ErrorCode.AUTH_DEVICE_NOT_TRUSTED);
            }
            return existing;
        } catch (EmptyResultDataAccessException absent) {
            String name = command.deviceName() == null || command.deviceName().isBlank() ? "Unknown device"
                    : command.deviceName();
            Long id = jdbc.queryForObject("""
                    INSERT INTO banking.device(device_ref, user_id, device_name, device_fingerprint_hash, last_seen_at)
                    VALUES (?, ?, ?, ?, ?) RETURNING device_id
                    """, Long.class, command.deviceRef(), userId, name, fingerprintHash, dbTime(now));
            return new DeviceRow(id, userId, false, "ACTIVE", command.deviceRef());
        }
    }

    private ChallengeMaterial createOtpChallenge(long userId, Long deviceId, Purpose purpose,
            String requestHash, String idempotencyKey) {
        UUID ref = UUID.randomUUID();
        String otp = oneTimeCode();
        Instant now = clock.instant();
        Instant expiry = now.plusSeconds(properties.security().otp().ttlSeconds());
        int maxAttempts = properties.security().otp().maxVerifyAttempts();
        Long id = jdbc.queryForObject("""
                INSERT INTO banking.auth_challenge(challenge_ref, user_id, device_id, purpose, method, channel,
                    otp_hash, request_hash, idempotency_key, status, max_attempts, expires_at,
                    sent_at, last_sent_at, correlation_id)
                VALUES (?, ?, ?, ?, 'OTP', 'IN_APP', ?, ?, ?, 'SENT', ?, ?, ?, ?, ?)
                RETURNING challenge_id
                """, Long.class, ref, userId, deviceId, purpose.name(), hmac.hmacSha256Hex(ref + ":" + otp),
                requestHash, idempotencyKey, maxAttempts, dbTime(expiry), dbTime(now), dbTime(now), correlationId());
        return new ChallengeMaterial(id, userId, otp,
                publicChallenge(ref, purpose, expiry, maxAttempts, 0));
    }

    private void deliverOrCancel(ChallengeMaterial material) {
        OtpDeliveryPort gateway = otpDelivery.getIfAvailable();
        if (gateway == null) {
            jdbc.update("UPDATE banking.auth_challenge SET status = 'CANCELLED' WHERE challenge_id = ?",
                    material.challengeId());
            throw new BankingBusinessException(ErrorCode.OTP_DISPATCH_FAILED);
        }
        try {
            gateway.deliver(new OtpDeliveryPort.OtpDelivery(material.publicChallenge().challengeId(),
                    material.userId(), material.publicChallenge().purpose().name(), "IN_APP", material.otp(),
                    material.publicChallenge().expiresAt(), correlationId()));
        } catch (RuntimeException failed) {
            jdbc.update("UPDATE banking.auth_challenge SET status = 'CANCELLED' WHERE challenge_id = ?",
                    material.challengeId());
            throw new BankingBusinessException(ErrorCode.OTP_DISPATCH_FAILED);
        }
    }

    private void verifyOtp(ChallengeRow challenge, String otp, String method) {
        Instant now = clock.instant();
        ensureChallengeUsable(challenge, now);
        if (!challenge.method().equals(method)) {
            throw new BankingBusinessException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        }
        String candidate = hmac.hmacSha256Hex(challenge.challengeRef() + ":" + otp);
        boolean matches = HmacService.constantTimeEquals(HexFormat.of().parseHex(challenge.otpHash()),
                HexFormat.of().parseHex(candidate));
        if (!matches) {
            int attempts = challenge.attemptCount() + 1;
            boolean locked = attempts >= challenge.maxAttempts();
            jdbc.update("""
                    UPDATE banking.auth_challenge SET attempt_count = ?, status = ? WHERE challenge_id = ?
                    """, attempts, locked ? "LOCKED" : "SENT", challenge.challengeId());
            recordAttempt(challenge.userId(), challenge.purpose() == Purpose.LOGIN_NEW_DEVICE ? "OTP" : "STEP_UP",
                    locked ? "LOCKED" : "FAILURE", "INVALID_OTP");
            auditC3(challenge.userId(), locked ? "AUTH_CHALLENGE_LOCKED" : "AUTH_STEP_UP_FAILED",
                    "AUTH_CHALLENGE", challenge.challengeRef().toString(), "FAILURE");
            if (locked) {
                throw new BankingBusinessException(ErrorCode.AUTH_CHALLENGE_LOCKED);
            }
            throw new BankingBusinessException(ErrorCode.AUTH_OTP_INVALID);
        }
        int updated = jdbc.update("""
                UPDATE banking.auth_challenge SET status = 'VERIFIED', verified_at = ?
                WHERE challenge_id = ? AND status = 'SENT' AND expires_at > ?
                """, dbTime(now), challenge.challengeId(), dbTime(now));
        if (updated != 1) {
            throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private void ensureChallengeUsable(ChallengeRow challenge, Instant now) {
        if (!now.isBefore(challenge.expiresAt())) {
            jdbc.update("""
                    UPDATE banking.auth_challenge SET status = 'EXPIRED'
                    WHERE challenge_id = ? AND status IN ('CREATED', 'SENT')
                    """, challenge.challengeId());
            throw new BankingBusinessException(ErrorCode.AUTH_OTP_EXPIRED);
        }
        if ("LOCKED".equals(challenge.status())) {
            throw new BankingBusinessException(ErrorCode.AUTH_CHALLENGE_LOCKED);
        }
        if (!"SENT".equals(challenge.status())) {
            throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private SessionGrant issueSession(UserRow user, DeviceRow device, Instant now) {
        if (!"ACTIVE".equals(user.status()) || device != null && !"ACTIVE".equals(device.status())) {
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        String token = randomToken();
        String csrf = randomToken();
        Instant absolute = now.plus(Duration.ofHours(properties.security().session().absoluteTimeoutHours()));
        Instant idle = min(now.plus(Duration.ofMinutes(properties.security().session().idleTimeoutMinutes())), absolute);
        Long sessionId = jdbc.queryForObject("""
                INSERT INTO banking.session(session_token_hash, csrf_token_hash, user_id, device_id,
                    idle_expires_at, absolute_expires_at)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING session_id
                """, Long.class, HmacService.sha256Hex(token), HmacService.sha256Hex(csrf), user.userId(),
                device == null ? null : device.deviceId(), dbTime(idle), dbTime(absolute));
        Long customerId = customerId(user.userId()).orElse(null);
        return new SessionGrant(sessionId, token, csrf, user.userId(), customerId,
                displayName(user.userId(), customerId, user.username()), user.userType(),
                roles(user.userId()), privileges(user.userId()), idle, absolute, device != null && device.trusted());
    }

    private SessionGrant grant(SessionDetails row, String token, String csrf) {
        boolean trusted = row.deviceId() != null && "ACTIVE".equals(row.deviceStatus())
                && findDevice(row.deviceId()).trusted();
        return new SessionGrant(row.sessionId(), token, csrf, row.userId(), row.customerId(),
                displayName(row.userId(), row.customerId(), row.username()), row.userType(), roles(row.userId()),
                privileges(row.userId()), row.idleExpiresAt(), row.absoluteExpiresAt(), trusted);
    }

    private Set<String> authorities(long userId) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        roles(userId).forEach(role -> result.add("ROLE_" + role));
        result.addAll(jdbc.queryForList("""
                SELECT DISTINCT p.permission_code FROM banking.user_role ur
                JOIN banking.role r ON r.role_id = ur.role_id AND r.status = 'ACTIVE'
                JOIN banking.role_permission rp ON rp.role_id = r.role_id
                JOIN banking.permission p ON p.permission_id = rp.permission_id AND p.status = 'ACTIVE'
                WHERE ur.user_id = ?
                """, String.class, userId));
        result.addAll(privileges(userId));
        return Set.copyOf(result);
    }

    private List<String> roles(long userId) {
        return jdbc.queryForList("""
                SELECT r.role_code FROM banking.user_role ur JOIN banking.role r ON r.role_id = ur.role_id
                WHERE ur.user_id = ? AND r.status = 'ACTIVE' ORDER BY r.role_code
                """, String.class, userId);
    }

    private List<String> privileges(long userId) {
        return jdbc.queryForList("""
                SELECT privilege_code FROM banking.user_privilege
                WHERE user_id = ? AND status = 'ACTIVE' ORDER BY privilege_code
                """, String.class, userId);
    }

    private Optional<Long> customerId(long userId) {
        List<Long> values = jdbc.queryForList("SELECT customer_id FROM banking.customer WHERE user_id = ?",
                Long.class, userId);
        return values.stream().findFirst();
    }

    private String displayName(long userId, Long customerId, String username) {
        return customerId == null ? username : customers.getCustomerByUserId(userId).fullName();
    }

    private UserRow normalizeExpiredLock(UserRow user, Instant now) {
        if ("DISABLED".equals(user.status())) {
            throw new BankingBusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (!"LOCKED".equals(user.status())) {
            return user;
        }
        if (user.lockedUntil() == null || now.isBefore(user.lockedUntil())) {
            throw new BankingBusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED,
                    ErrorCode.AUTH_ACCOUNT_LOCKED.detail(),
                    user.lockedUntil() == null ? Map.of() : Map.of("lockedUntil", user.lockedUntil()));
        }
        jdbc.update("""
                UPDATE banking.app_user
                SET status = 'ACTIVE', failed_attempt_count = 0, locked_until = NULL, updated_at = ?
                WHERE user_id = ? AND status = 'LOCKED'
                """, dbTime(now), user.userId());
        return new UserRow(user.userId(), user.username(), user.passwordHash(), user.userType(), "ACTIVE", 0, null);
    }

    private Optional<UserRow> findUserForUpdate(String username) {
        List<UserRow> rows = jdbc.query("""
                SELECT user_id, username, password_hash, user_type, status, failed_attempt_count, locked_until
                FROM banking.app_user WHERE username = ? FOR UPDATE
                """, this::userRow, username);
        return rows.stream().findFirst();
    }

    private UserRow findUserForUpdateById(long userId) {
        try {
            return jdbc.queryForObject("""
                    SELECT user_id, username, password_hash, user_type, status, failed_attempt_count, locked_until
                    FROM banking.app_user WHERE user_id = ? FOR UPDATE
                    """, this::userRow, userId);
        } catch (EmptyResultDataAccessException absent) {
            throw new BankingBusinessException(ErrorCode.USER_NOT_FOUND);
        }
    }

    private UserRow findEnabledUserForUpdateById(long userId) {
        try {
            return jdbc.queryForObject("""
                    SELECT user_id, username, password_hash, user_type, status, failed_attempt_count, locked_until
                    FROM banking.app_user WHERE user_id = ? AND status <> 'DISABLED' FOR UPDATE
                    """, this::userRow, userId);
        } catch (EmptyResultDataAccessException absentOrDisabled) {
            throw new BankingBusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
    }

    private UserRow findUser(long userId) {
        try {
            return jdbc.queryForObject("""
                    SELECT user_id, username, password_hash, user_type, status, failed_attempt_count, locked_until
                    FROM banking.app_user WHERE user_id = ?
                    """, this::userRow, userId);
        } catch (EmptyResultDataAccessException absent) {
            throw new BankingBusinessException(ErrorCode.USER_NOT_FOUND);
        }
    }

    private ChallengeRow lockChallenge(UUID ref) {
        try {
            return jdbc.queryForObject("""
                    SELECT challenge_id, challenge_ref, user_id, device_id, purpose, method, otp_hash, status,
                           attempt_count, max_attempts, resend_count, expires_at, last_sent_at
                    FROM banking.auth_challenge WHERE challenge_ref = ? FOR UPDATE
                    """, this::challengeRow, ref);
        } catch (EmptyResultDataAccessException absent) {
            throw new BankingBusinessException(ErrorCode.AUTH_CHALLENGE_NOT_FOUND);
        }
    }

    private DeviceRow findDevice(long id) {
        return jdbc.queryForObject("""
                SELECT device_id, user_id, trusted, status, device_ref FROM banking.device WHERE device_id = ?
                """, this::deviceRow, id);
    }

    private DeviceRow findDeviceForUpdate(long id) {
        try {
            return jdbc.queryForObject("""
                    SELECT device_id, user_id, trusted, status, device_ref
                    FROM banking.device WHERE device_id = ? FOR UPDATE
                    """, this::deviceRow, id);
        } catch (EmptyResultDataAccessException absent) {
            throw new BankingBusinessException(ErrorCode.AUTH_DEVICE_NOT_TRUSTED);
        }
    }

    private void recordAttempt(long userId, String type, String outcome, String reason) {
        jdbc.update("""
                INSERT INTO banking.auth_attempt(user_id, attempt_type, outcome, reason_code, correlation_id)
                VALUES (?, ?, ?, ?, ?)
                """, userId, type, outcome, reason, correlationId());
    }

    private void auditC3(long userId, String action, String entityType, String entityId, String outcome) {
        IdentitySecurityAuditSink sink = securityAudit.getIfAvailable();
        if (sink == null) {
            return;
        }
        IdentitySecurityAuditSink.SecurityEvent event = new IdentitySecurityAuditSink.SecurityEvent(userId,
                action, entityType, entityId, outcome, correlationId(), Map.of());
        Runnable publish = () -> {
            try {
                sink.recordStandalone(event);
            } catch (RuntimeException ignored) {
                // C3 audit is best-effort and must never roll back identity security state.
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    private Challenge publicChallenge(UUID ref, Purpose purpose, Instant expiry, int max, int attempts) {
        return new Challenge(ref, purpose, List.of("OTP"), "IN_APP", "in-app", expiry, max,
                Math.max(0, max - attempts));
    }

    private String correlationId() {
        return RequestContextHolder.current().map(context -> context.correlationId()).orElse("internal-" + UUID.randomUUID());
    }

    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank() || username.length() > 64) {
            throw new BankingBusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        return username.toLowerCase(Locale.ROOT);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String oneTimeCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime dbTime(Instant value) {
        return value == null ? null : value.atOffset(java.time.ZoneOffset.UTC);
    }

    private UserRow userRow(ResultSet rs, int rowNum) throws SQLException {
        return new UserRow(rs.getLong("user_id"), rs.getString("username"), rs.getString("password_hash"),
                rs.getString("user_type"), rs.getString("status"), rs.getInt("failed_attempt_count"),
                instant(rs, "locked_until"));
    }

    private SessionRow sessionRow(ResultSet rs, int rowNum) throws SQLException {
        Long customerId = rs.getObject("customer_id", Long.class);
        return new SessionRow(rs.getLong("session_id"), rs.getLong("user_id"),
                rs.getObject("device_id", Long.class), customerId, rs.getString("csrf_token_hash"),
                instant(rs, "idle_expires_at"), instant(rs, "absolute_expires_at"),
                rs.getString("user_status"), rs.getString("device_status"));
    }

    private SessionDetails sessionDetails(ResultSet rs, int rowNum) throws SQLException {
        return new SessionDetails(rs.getLong("session_id"), rs.getLong("user_id"),
                rs.getObject("customer_id", Long.class), rs.getObject("device_id", Long.class),
                rs.getString("username"), rs.getString("user_type"), rs.getString("user_status"),
                rs.getString("device_status"), instant(rs, "idle_expires_at"), instant(rs, "absolute_expires_at"));
    }

    private DeviceRow deviceRow(ResultSet rs, int rowNum) throws SQLException {
        return new DeviceRow(rs.getLong("device_id"), rs.getLong("user_id"), rs.getBoolean("trusted"),
                rs.getString("status"), rs.getObject("device_ref", UUID.class));
    }

    private ChallengeRow challengeRow(ResultSet rs, int rowNum) throws SQLException {
        return new ChallengeRow(rs.getLong("challenge_id"), rs.getObject("challenge_ref", UUID.class),
                rs.getLong("user_id"), rs.getObject("device_id", Long.class),
                Purpose.valueOf(rs.getString("purpose")), rs.getString("method"), rs.getString("otp_hash"),
                rs.getString("status"), rs.getInt("attempt_count"), rs.getInt("max_attempts"),
                rs.getInt("resend_count"), instant(rs, "expires_at"),
                instant(rs, "last_sent_at"));
    }

    public record LoginCommand(String username, String password, UUID deviceRef, String deviceName,
            String deviceFingerprint) {
    }

    public record PasscodeCommand(String username, String passcode, UUID deviceRef, String deviceFingerprint) {
    }

    public record LoginResult(SessionGrant session, Challenge challenge) {
        static LoginResult authenticated(SessionGrant session) { return new LoginResult(session, null); }
        static LoginResult challenge(Challenge challenge) { return new LoginResult(null, challenge); }
        public boolean authenticated() { return session != null; }
    }

    public record SessionGrant(long sessionId, String rawToken, String csrfToken, long userId, Long customerId,
            String displayName, String userType, List<String> roles, List<String> privileges, Instant idleExpiresAt,
            Instant absoluteExpiresAt, boolean deviceTrusted) {
        public SessionGrant {
            roles = List.copyOf(roles);
            privileges = List.copyOf(privileges);
        }
        @Override public String toString() { return "SessionGrant[redacted]"; }
    }

    public record StepUpVerification(UUID challengeId, Purpose purpose, Instant verifiedAt, Instant expiresAt) {
    }

    private record UserRow(long userId, String username, String passwordHash, String userType, String status,
            int failedAttempts, Instant lockedUntil) {
    }
    private record SessionRow(long sessionId, long userId, Long deviceId, Long customerId, String csrfHash,
            Instant idleExpiresAt, Instant absoluteExpiresAt, String userStatus, String deviceStatus) {
    }
    private record SessionDetails(long sessionId, long userId, Long customerId, Long deviceId, String username,
            String userType, String userStatus, String deviceStatus, Instant idleExpiresAt,
            Instant absoluteExpiresAt) {
    }
    private record DeviceRow(long deviceId, long userId, boolean trusted, String status, UUID deviceRef) {
    }
    private record PasscodeRow(UserRow user, long deviceId, UUID deviceRef, String fingerprintHash,
            String passcodeHash, boolean trusted, String deviceStatus) {
    }
    private record ChallengeRow(long challengeId, UUID challengeRef, long userId, Long deviceId, Purpose purpose,
            String method, String otpHash, String status, int attemptCount, int maxAttempts, int resendCount,
            Instant expiresAt, Instant lastSentAt) {
    }
    private record ChallengeMaterial(long challengeId, long userId, String otp, Challenge publicChallenge) {
    }
}
