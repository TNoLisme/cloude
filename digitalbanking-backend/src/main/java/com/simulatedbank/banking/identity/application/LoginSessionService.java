package com.simulatedbank.banking.identity.application;

import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Completes login only in the transaction that verified and locked its authentication challenge. */
@Service
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class LoginSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final UserAccountRepository users;
    private final DeviceRepository devices;
    private final SessionRepository sessions;
    private final AuthorityReader authorities;
    private final SessionCustomerBinding customerBinding;
    private final AuthAuditPort audit;
    private final ChallengeRepository challenges;
    private final Clock clock;
    private final AuthMetrics metrics;
    private final Duration idleTimeout;
    private final Duration absoluteTimeout;

    public LoginSessionService(UserAccountRepository users, DeviceRepository devices, SessionRepository sessions,
            AuthorityReader authorities, AuthAuditPort audit, ChallengeRepository challenges, Clock clock,
            AppProperties properties, AuthMetrics metrics, SessionCustomerBinding customerBinding) {
        this.users = users;
        this.devices = devices;
        this.sessions = sessions;
        this.authorities = authorities;
        this.customerBinding = customerBinding;
        this.audit = audit;
        this.challenges = challenges;
        this.clock = clock;
        this.metrics = metrics;
        this.idleTimeout = Duration.ofMinutes(properties.security().session().idleTimeoutMinutes());
        this.absoluteTimeout = Duration.ofHours(properties.security().session().absoluteTimeoutHours());
    }

    /**
     * JOIN OTP verification's OWN transaction. Lock order: already-locked challenge, user,
     * device, session; mandatory audits are last. Any failure rolls verification back too.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LoginResult complete(long userId, Long deviceId, String challengeRef, ClientContext client) {
        return completeVerified(userId, deviceId, challengeRef, client, "OTP");
    }

    /** FIDO caller holds challenge/user/device locks and has verified the signature and counter. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LoginResult completeFido(long userId, Long deviceId, String challengeRef, ClientContext client) {
        return completeVerified(userId, deviceId, challengeRef, client, "FIDO");
    }

    private LoginResult completeVerified(long userId, Long deviceId, String challengeRef, ClientContext client,
            String method) {
        var challenge = challenges.findByRefAndUser(challengeRef, userId)
                .orElseThrow(() -> rejection(ErrorCode.AUTH_CHALLENGE_NOT_FOUND));
        if (!"LOGIN_NEW_DEVICE".equals(challenge.purpose()) || !method.equals(challenge.method())
                || !Objects.equals(deviceId, challenge.deviceId())
                || !"VERIFIED".equals(challenge.status()) || challenge.consumedAt() != null) {
            throw rejection(ErrorCode.INVALID_STATE_TRANSITION);
        }

        // Conditional UPDATE locks the user and rechecks eligibility after any concurrent change.
        if (!users.resetCountersAndMarkLoginSuccess(userId)) {
            throw diagnoseUserRejection(userId);
        }
        var user = users.findById(userId)
                .orElseThrow(() -> rejection(ErrorCode.AUTH_INVALID_CREDENTIALS));
        String customerId = customerBinding.resolve(userId, user.userType());
        if (deviceId != null) {
            var device = devices.lockOwned(deviceId, userId)
                    .orElseThrow(() -> rejection(ErrorCode.AUTH_DEVICE_NOT_TRUSTED));
            if (!"ACTIVE".equals(device.status())) {
                throw rejection(ErrorCode.AUTH_DEVICE_NOT_TRUSTED);
            }
        }

        if (challenges.consume(new ChallengeRepository.ConsumePredicate(
                challengeRef, userId, "LOGIN_NEW_DEVICE", null, null)).isEmpty()) {
            throw diagnoseConsumeRejection(challengeRef, userId, method);
        }

        String sessionToken = freshToken();
        String csrfToken = freshToken();
        Instant now = clock.instant();
        Instant absolute = now.plus(absoluteTimeout);
        Instant idle = now.plus(idleTimeout.compareTo(absoluteTimeout) <= 0 ? idleTimeout : absoluteTimeout);
        sessions.createSession(new SessionRepository.CreateSessionCommand(HmacService.sha256Hex(sessionToken),
                HmacService.sha256Hex(csrfToken), userId, idle, absolute, deviceId));
        if (deviceId != null && !devices.markSeen(deviceId, userId)) {
            throw rejection(ErrorCode.AUTH_DEVICE_NOT_TRUSTED);
        }
        users.recordAuthAttempt(new UserAccountRepository.AuthAttemptRecord(
                userId, "SUCCESS", null, client.correlationId(), client.clientInfoJson(), method));
        Set<String> auths = authorities.loadAuthorities(userId);
        List<String> roles = auths.stream().filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length())).sorted().toList();
        Set<String> privileges = auths.stream().filter(a -> !a.startsWith("ROLE_"))
                .collect(Collectors.toUnmodifiableSet());
        LoginResult result = new LoginResult(userId, user.username(), user.userType(), roles, privileges, customerId,
                sessionToken, csrfToken, idle, absolute);

        audit.recordInTransaction(AuthAuditPort.AuthAuditEvent.c2Challenge(
                AuthAuditPort.AuthAction.AUTH_STEP_UP_VERIFIED, userId, challengeRef, null, null),
                client.correlationId());
        audit.recordInTransaction(AuthAuditPort.AuthAuditEvent.c2(
                AuthAuditPort.AuthAction.AUTH_LOGIN_SUCCESS, userId, null, null), client.correlationId());
        // BE-ID-IMP-13: mọi write phiên đã thành công trong TX; metric sau audit C2 (fail-closed không đếm sai).
        metrics.sessionCreated(method);
        return result;
    }

    /** Record only after the rejected completion/verification transaction has ended. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(long userId, String reasonCode, ClientContext client) {
        String outcome = ErrorCode.AUTH_CHALLENGE_LOCKED.name().equals(reasonCode)
                || ErrorCode.AUTH_ACCOUNT_LOCKED.name().equals(reasonCode) ? "LOCKED" : "FAILURE";
        users.recordAuthAttempt(new UserAccountRepository.AuthAttemptRecord(
                userId, outcome, reasonCode, client.correlationId(), client.clientInfoJson(), "OTP"));
        audit.recordStandalone(AuthAuditPort.AuthAuditEvent.c3User(
                AuthAuditPort.AuthAction.AUTH_LOGIN_FAILED, userId, reasonCode, null), client.correlationId());
    }

    private BankingBusinessException diagnoseUserRejection(long userId) {
        var user = users.findById(userId).orElse(null);
        if (user == null) {
            return rejection(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if ("LOCKED".equals(user.status())
                || user.lockedUntil() != null && user.lockedUntil().isAfter(clock.instant())) {
            return rejection(ErrorCode.AUTH_ACCOUNT_LOCKED);
        }
        return rejection(ErrorCode.AUTH_FORBIDDEN);
    }

    private BankingBusinessException diagnoseConsumeRejection(String challengeRef, long userId, String method) {
        var current = challenges.findByRefAndUser(challengeRef, userId)
                .orElseThrow(() -> rejection(ErrorCode.AUTH_CHALLENGE_NOT_FOUND));
        if (current.consumedAt() != null) {
            return rejection(ErrorCode.INVALID_STATE_TRANSITION);
        }
        // expireIfDue samples DB wall time, including time spent waiting for user/device locks.
        if (challenges.expireIfDue(current.challengeId()) || "EXPIRED".equals(current.status())
                || !current.expiresAt().isAfter(clock.instant())) {
            return rejection("OTP".equals(method) ? ErrorCode.AUTH_OTP_EXPIRED : ErrorCode.AUTH_CHALLENGE_EXPIRED);
        }
        if ("LOCKED".equals(current.status())) {
            return rejection(ErrorCode.AUTH_CHALLENGE_LOCKED);
        }
        return rejection(ErrorCode.INVALID_STATE_TRANSITION);
    }

    private static String freshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    private static BankingBusinessException rejection(ErrorCode code) {
        return new BankingBusinessException(code);
    }
}
