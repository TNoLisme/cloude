package com.simulatedbank.banking.identity.application;

import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.application.port.FidoAssertionVerifier;
import com.simulatedbank.banking.identity.application.port.FidoCredentialStore;
import com.simulatedbank.banking.identity.application.port.FidoVerificationException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** API-AUTH-03. Challenge, user and credential locks serialize the entire assertion commit. */
@Service
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class FidoLoginService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final FidoCredentialStore store;
    private final UserAccountRepository users;
    private final LoginSessionService sessions;
    private final AuthAuditPort audit;
    private final ObjectProvider<FidoAssertionVerifier> verifiers;
    private final FidoProperties policy;
    private final AppProperties.Security.Lockout lockout;
    private final Clock clock;
    private final AuthMetrics metrics;
    private final TransactionTemplate tx;

    public FidoLoginService(FidoCredentialStore store, UserAccountRepository users, LoginSessionService sessions,
            AuthAuditPort audit, ObjectProvider<FidoAssertionVerifier> verifiers, FidoProperties policy,
            AppProperties properties, Clock clock, PlatformTransactionManager manager, AuthMetrics metrics) {
        this.store = store;
        this.users = users;
        this.sessions = sessions;
        this.audit = audit;
        this.verifiers = verifiers;
        this.policy = policy;
        this.lockout = properties.security().lockout();
        this.clock = clock;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Begun(String challengeRef, String nonce, String credentialId, Instant expiresAt,
            int maxAttempts, String rpId, long timeoutMillis) {
        @Override public String toString() { return "FidoBegin[redacted]"; }
    }

    public Begun begin(String username, String deviceRef, ClientContext client) {
        requireVerifier();
        return tx.execute(status -> {
            var user = users.findByUsername(username.toLowerCase(Locale.ROOT))
                    .orElseThrow(() -> rejection(ErrorCode.AUTH_INVALID_CREDENTIALS));
            store.lockUser(user.userId());
            user = users.findById(user.userId()).orElseThrow(() -> rejection(ErrorCode.AUTH_INVALID_CREDENTIALS));
            checkUser(user);
            var credential = store.findCredential(deviceRef, user.userId())
                    .filter(FidoLoginService::eligible)
                    .orElseThrow(() -> rejection(ErrorCode.AUTH_INVALID_CREDENTIALS));
            byte[] nonce = new byte[32];
            RANDOM.nextBytes(nonce);
            var ceremony = store.createCeremony(user.userId(), credential.deviceId(), credential.credentialId(),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
                    clock.instant().plus(policy.challengeTtl()), lockout.maxFailedAttempts(), client.correlationId());
            audit.recordInTransaction(AuthAuditPort.AuthAuditEvent.c2Challenge(
                    AuthAuditPort.AuthAction.AUTH_CHALLENGE_CREATED, user.userId(), ceremony.challengeRef(), null, null),
                    client.correlationId());
            return new Begun(ceremony.challengeRef(), ceremony.nonce(), credential.credentialId(), ceremony.expiresAt(),
                    ceremony.maxAttempts(), policy.rpId(), policy.challengeTtl().toMillis());
        });
    }

    public LoginResult finish(String username, String deviceRef, String challengeRef,
            FidoAssertionVerifier.Assertion assertion, ClientContext client) {
        var verifier = requireVerifier();
        long started = System.nanoTime();
        try {
            Outcome outcome = tx.execute(status ->
                    verify(username, deviceRef, challengeRef, assertion, client, verifier));
            if (outcome.error() != null) {
                if (outcome.userId() != null) {
                    audit.recordStandalone(AuthAuditPort.AuthAuditEvent.c3User(
                            AuthAuditPort.AuthAction.AUTH_LOGIN_FAILED, outcome.userId(), outcome.reason(), null),
                            client.correlationId());
                    if (outcome.error() == ErrorCode.AUTH_ACCOUNT_LOCKED) {
                        audit.recordStandalone(AuthAuditPort.AuthAuditEvent.c3User(
                                AuthAuditPort.AuthAction.AUTH_ACCOUNT_LOCKED, outcome.userId(), outcome.reason(), null),
                                client.correlationId());
                    }
                }
                metrics.loginAttempt("FIDO", false, outcome.error().name());
                throw rejection(outcome.error());
            }
            metrics.loginAttempt("FIDO", true, "NONE");
            return outcome.result();
        } finally {
            // BE-ID-IMP-13: duration của assertion verify + commit phiên.
            metrics.loginDuration("FIDO", System.nanoTime() - started);
        }
    }

    private Outcome verify(String username, String deviceRef, String challengeRef,
            FidoAssertionVerifier.Assertion assertion, ClientContext client, FidoAssertionVerifier verifier) {
        var user = users.findByUsername(username.toLowerCase(Locale.ROOT)).orElse(null);
        var ceremony = store.lockCeremony(challengeRef).orElse(null);
        // A reference is not authentication: never mutate a foreign user's challenge.
        if (user == null || ceremony == null || user.userId() != ceremony.userId()) {
            return Outcome.denied(null, ErrorCode.AUTH_CHALLENGE_NOT_FOUND, "INVALID_CONTEXT");
        }
        if (!"FIDO".equals(ceremony.method()) || !"LOGIN_NEW_DEVICE".equals(ceremony.purpose())) {
            return Outcome.denied(null, ErrorCode.INVALID_STATE_TRANSITION, "INVALID_CONTEXT");
        }
        if (ceremony.consumedAt() != null || !"CREATED".equals(ceremony.status())) {
            ErrorCode code = switch (ceremony.status()) {
                case "LOCKED" -> ErrorCode.AUTH_CHALLENGE_LOCKED;
                case "EXPIRED" -> ErrorCode.AUTH_CHALLENGE_EXPIRED;
                default -> ErrorCode.INVALID_STATE_TRANSITION;
            };
            return Outcome.denied(user.userId(), code, "CEREMONY_UNAVAILABLE");
        }
        store.lockUser(user.userId());
        user = users.findById(user.userId()).orElseThrow(() -> rejection(ErrorCode.AUTH_INVALID_CREDENTIALS));
        checkUser(user);
        var credential = store.lockCredential(ceremony.deviceId(), user.userId()).orElse(null);
        if (!store.isLive(ceremony.challengeId())) {
            store.expireCeremony(ceremony.challengeId());
            return Outcome.denied(user.userId(), ErrorCode.AUTH_CHALLENGE_EXPIRED, "CEREMONY_EXPIRED");
        }
        if (credential == null || !eligible(credential)
                || !deviceRef.equalsIgnoreCase(credential.deviceRef())
                || !Objects.equals(ceremony.credentialId(), credential.credentialId())
                || !Objects.equals(assertion.credentialId(), credential.credentialId())) {
            return failed(user.userId(), ceremony.challengeId(), "INVALID_ASSERTION", client);
        }
        try {
            var verified = verifier.verify(credential, ceremony.nonce(), assertion);
            if (!store.verifyCeremony(ceremony.challengeId())) {
                return Outcome.denied(user.userId(), ErrorCode.AUTH_CHALLENGE_EXPIRED, "CEREMONY_EXPIRED");
            }
            store.advanceCounter(credential.deviceId(), verified.signCount());
            return new Outcome(sessions.completeFido(user.userId(), credential.deviceId(), challengeRef, client),
                    null, null, null);
        } catch (FidoVerificationException invalid) {
            return failed(user.userId(), ceremony.challengeId(), invalid.reason().name(), client);
        }
    }

    private Outcome failed(long userId, long challengeId, String reason, ClientContext client) {
        // Return the denial, do not throw inside TX: failed attempts must commit.
        store.failCeremony(challengeId);
        var failure = users.recordFailedAttempt(userId, lockout.maxFailedAttempts(), lockout.lockDurationMinutes());
        boolean locked = failure.isPresent() && failure.get().lockedNow();
        users.recordAuthAttempt(new UserAccountRepository.AuthAttemptRecord(userId, locked ? "LOCKED" : "FAILURE",
                reason, client.correlationId(), client.clientInfoJson(), "FIDO"));
        return Outcome.denied(userId, locked ? ErrorCode.AUTH_ACCOUNT_LOCKED : ErrorCode.AUTH_INVALID_CREDENTIALS, reason);
    }

    private void checkUser(UserAccountRepository.AppUserSnapshot user) {
        if ("LOCKED".equals(user.status()) || user.lockedUntil() != null && user.lockedUntil().isAfter(clock.instant())) {
            if (!users.unlockIfLockExpired(user.userId())) {
                throw rejection(ErrorCode.AUTH_ACCOUNT_LOCKED);
            }
        } else if (!"ACTIVE".equals(user.status())) {
            throw rejection(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
    }

    private static boolean eligible(FidoCredentialStore.Credential credential) {
        return credential.trusted() && "ACTIVE".equals(credential.status())
                && credential.credentialId() != null && credential.publicKeyCose() != null
                && credential.userHandle() != null;
    }

    private FidoAssertionVerifier requireVerifier() {
        var verifier = policy.enabled() ? verifiers.getIfAvailable() : null;
        if (verifier == null) { throw rejection(ErrorCode.SERVICE_UNAVAILABLE); }
        return verifier;
    }

    private record Outcome(LoginResult result, Long userId, ErrorCode error, String reason) {
        static Outcome denied(Long userId, ErrorCode error, String reason) {
            return new Outcome(null, userId, error, reason);
        }
    }

    private static BankingBusinessException rejection(ErrorCode code) { return new BankingBusinessException(code); }
}
