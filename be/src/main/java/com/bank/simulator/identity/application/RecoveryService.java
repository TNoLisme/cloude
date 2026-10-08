package com.bank.simulator.identity.application;

import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeService;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.PasswordHashingService;
import com.bank.simulator.identity.infrastructure.security.RecoveryTokenCodec;
import com.bank.simulator.identity.infrastructure.security.RecoveryTokenRepository;
import com.bank.simulator.identity.infrastructure.security.RefreshSessionService;
import com.bank.simulator.shared.error.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class RecoveryService {
    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(5);
    private static final String GENERIC_MESSAGE =
            "If the information is registered, an OTP has been sent to the selected channel.";

    private final IdentityJdbcRepository identities;
    private final OtpChallengeService otp;
    private final RecoveryTokenRepository tokens;
    private final RecoveryTokenCodec tokenCodec;
    private final PasswordHashingService passwords;
    private final RefreshSessionService sessions;
    private final ObjectProvider<AuditWriter> auditWriters;
    private final Clock clock;

    public RecoveryService(IdentityJdbcRepository identities, OtpChallengeService otp,
                           RecoveryTokenRepository tokens, RecoveryTokenCodec tokenCodec,
                           PasswordHashingService passwords, RefreshSessionService sessions,
                           ObjectProvider<AuditWriter> auditWriters, Clock clock) {
        this.identities = identities;
        this.otp = otp;
        this.tokens = tokens;
        this.tokenCodec = tokenCodec;
        this.passwords = passwords;
        this.sessions = sessions;
        this.auditWriters = auditWriters;
        this.clock = clock;
    }

    public InitiateResult initiate(String identifier, String channel) {
        String normalized = normalize(identifier);
        IdentityJdbcRepository.UserRecord user = identities.findByIdentifier(normalized, channel);
        if (user != null) {
            tokens.invalidateActiveForUser(user.userId(), clock.instant());
            otp.issue(normalized, channel, "RECOVERY");
        }
        audit(user, "PASSWORD_RECOVERY_INITIATED", user == null ? "NO_MATCH" : "SUCCESS");
        return new InitiateResult(identifier, channel, 120, GENERIC_MESSAGE);
    }

    @Transactional
    public VerifyResult verify(String identifier, String channel, String code) {
        String normalized = normalize(identifier);
        IdentityJdbcRepository.UserRecord user = identities.findByIdentifier(normalized, channel);
        if (user == null || !user.active()
                || otp.consumeLatest(normalized, channel, "RECOVERY", code) != OtpChallengeService.ConsumeResult.VALID) {
            audit(user, "PASSWORD_RECOVERY_OTP_VERIFIED", "FAILURE");
            return VerifyResult.invalid();
        }

        tokens.lockUser(user.userId());
        IdentityJdbcRepository.UserRecord current = identities.findByUserId(user.userId());
        if (current == null || !current.active()) {
            audit(user, "PASSWORD_RECOVERY_OTP_VERIFIED", "FAILURE");
            return VerifyResult.invalid();
        }
        Instant now = clock.instant();
        String rawToken = tokenCodec.generate();
        tokens.invalidateActiveForUser(user.userId(), now);
        tokens.create(UUID.randomUUID(), user.userId(), tokenCodec.hash(rawToken), now, now.plus(RESET_TOKEN_TTL));
        audit(user, "PASSWORD_RECOVERY_OTP_VERIFIED", "SUCCESS");
        return new VerifyResult(rawToken, (int) RESET_TOKEN_TTL.toSeconds());
    }

    @Transactional
    public void confirm(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 128) throw invalidToken();
        String tokenHash = tokenCodec.hash(rawToken);
        UUID userId = tokens.findUserId(tokenHash);
        if (userId == null) throw invalidToken();

        tokens.lockUser(userId);
        RecoveryTokenRepository.RecoveryTokenRecord proof = tokens.lock(tokenHash);
        Instant now = clock.instant();
        if (proof == null || proof.consumedAt() != null || proof.invalidatedAt() != null
                || !proof.expiresAt().isAfter(now)) throw invalidToken();
        IdentityJdbcRepository.UserRecord user = identities.findByUserId(userId);
        if (user == null || !user.active()) throw invalidToken();

        identities.updatePassword(userId, passwords.encode(newPassword));
        tokens.consume(proof.id(), now);
        tokens.invalidateActiveForUser(userId, now);
        sessions.revokeAll(userId);
        audit(user, "PASSWORD_RECOVERY_CONFIRMED", "SUCCESS");
    }

    private ApiException invalidToken() {
        return new ApiException(HttpStatus.BAD_REQUEST, "RECOVERY_TOKEN_INVALID",
                "Recovery token is invalid or expired.");
    }

    private void audit(IdentityJdbcRepository.UserRecord user, String eventType, String outcome) {
        AuditWriter writer = auditWriters.getIfAvailable();
        if (writer == null || user == null) return;
        writer.record(user.userId(), null, eventType, "USER", user.userId(), outcome,
                correlationId(), "Password recovery step " + outcome.toLowerCase(Locale.ROOT) + ".");
    }

    private UUID correlationId() {
        String value = org.slf4j.MDC.get("correlationId");
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public record InitiateResult(String identifier, String channel, int expiresInSeconds, String message) {}
    public record VerifyResult(String resetToken, int expiresInSeconds) {
        public static VerifyResult invalid() { return new VerifyResult(null, 0); }
        public boolean valid() { return resetToken != null; }
    }
}
