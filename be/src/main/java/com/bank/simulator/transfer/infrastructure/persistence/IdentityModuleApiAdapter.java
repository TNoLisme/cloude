package com.bank.simulator.transfer.infrastructure.persistence;

import com.bank.simulator.identity.application.PinCredentialService;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeRecord;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeRepository;
import com.bank.simulator.identity.infrastructure.otp.OtpHashingService;
import com.bank.simulator.identity.infrastructure.otp.OtpMessage;
import com.bank.simulator.identity.infrastructure.otp.OtpSender;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.transfer.api.IdentityModuleApi;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Transfer-side adapter for identity. The identity module is not modified: the
 * transfer-specific OTP lifecycle (issue inside the money TX, dispatch after
 * commit, consume/invalidate under lock) is encapsulated here so application
 * services never see identity internals.
 */
@Repository
public class IdentityModuleApiAdapter implements IdentityModuleApi {
    private static final String CHANNEL = "SMS";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration TTL = Duration.ofSeconds(120);

    private final SecureRandom secureRandom = new SecureRandom();
    private final PinCredentialService pinCredentials;
    private final IdentityJdbcRepository identities;
    private final OtpChallengeRepository otpChallenges;
    private final OtpHashingService otpHashing;
    private final OtpSender sender;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdentityModuleApiAdapter(PinCredentialService pinCredentials, IdentityJdbcRepository identities,
                                    OtpChallengeRepository otpChallenges, OtpHashingService otpHashing,
                                    OtpSender sender, JdbcTemplate jdbc, Clock clock) {
        this.pinCredentials = pinCredentials;
        this.identities = identities;
        this.otpChallenges = otpChallenges;
        this.otpHashing = otpHashing;
        this.sender = sender;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public PinVerificationResult verifyPin(UUID customerId, String pin) {
        return switch (pinCredentials.verify(customerId, pin)) {
            case VALID -> PinVerificationResult.VALID;
            case INVALID -> PinVerificationResult.INVALID;
            case LOCKED -> PinVerificationResult.LOCKED;
            case NOT_SET -> PinVerificationResult.NOT_SET;
        };
    }

    @Override
    public CustomerView findCustomerByUserId(UUID userId) {
        return toView(identities.findCustomerByUserId(userId));
    }

    @Override
    public CustomerView findCustomerById(UUID customerId) {
        return toView(identities.findCustomerById(customerId));
    }

    @Override
    public OtpIssue issueOtp(UUID customerId, String purpose) {
        var customer = identities.findCustomerById(customerId);
        if (customer == null) throw new IllegalStateException("Customer is unavailable for OTP issuance");
        Instant now = clock.instant();
        Instant expiresAt = now.plus(TTL);
        UUID challengeId = UUID.randomUUID();
        String code = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
        otpChallenges.create(challengeId, customer.phone(), CHANNEL, purpose, otpHashing.encode(code), now, expiresAt,
                MAX_ATTEMPTS);
        return new OtpIssue(challengeId, customerId, customer.phone(), code, expiresAt);
    }

    @Override
    public void dispatchOtp(OtpIssue issue) {
        sender.send(new OtpMessage(issue.challengeId(), issue.phone(), issue.code(), issue.expiresAt()));
    }

    @Override
    public OtpConsumeResult consumeOtp(UUID challengeId, String otp) {
        Instant now = clock.instant();
        OtpChallengeRecord challenge = lockById(challengeId);
        if (challenge == null) return OtpConsumeResult.of(OtpConsumeResult.Outcome.NOT_FOUND, 0, MAX_ATTEMPTS);
        int attempts = challenge.attempts();
        int maxAttempts = challenge.maxAttempts();
        if (challenge.consumedAt() != null) {
            return OtpConsumeResult.of(OtpConsumeResult.Outcome.ALREADY_USED, attempts, maxAttempts);
        }
        if (challenge.invalidatedAt() != null || attempts >= maxAttempts) {
            return OtpConsumeResult.of(OtpConsumeResult.Outcome.ATTEMPTS_EXCEEDED, attempts, maxAttempts);
        }
        if (now.isAfter(challenge.expiresAt())) {
            return OtpConsumeResult.of(OtpConsumeResult.Outcome.EXPIRED, attempts, maxAttempts);
        }
        if (!otpHashing.matches(otp, challenge.otpHash())) {
            int next = attempts + 1;
            boolean exhausted = next >= maxAttempts;
            otpChallenges.incrementAttempts(challenge.id(), next, exhausted ? now : null);
            return OtpConsumeResult.of(exhausted ? OtpConsumeResult.Outcome.ATTEMPTS_EXCEEDED
                    : OtpConsumeResult.Outcome.INVALID, next, maxAttempts);
        }
        otpChallenges.consume(challenge.id(), now);
        return OtpConsumeResult.of(OtpConsumeResult.Outcome.VALID, attempts, maxAttempts);
    }

    @Override
    public void invalidateOtp(UUID challengeId) {
        otpChallenges.invalidate(challengeId, clock.instant());
    }

    private OtpChallengeRecord lockById(UUID challengeId) {
        List<OtpChallengeRecord> rows = jdbc.query("""
                SELECT id, identifier_normalized, channel, purpose, otp_hash, attempts, max_attempts,
                       expires_at, consumed_at, invalidated_at
                FROM otp_challenges WHERE id = ? FOR UPDATE
                """, (rs, row) -> new OtpChallengeRecord(rs.getObject("id", UUID.class),
                rs.getString("identifier_normalized"), rs.getString("channel"), rs.getString("purpose"),
                rs.getString("otp_hash"), rs.getInt("attempts"), rs.getInt("max_attempts"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                rs.getTimestamp("invalidated_at") == null ? null : rs.getTimestamp("invalidated_at").toInstant()),
                challengeId);
        return rows.stream().findFirst().orElse(null);
    }

    private CustomerView toView(IdentityJdbcRepository.CustomerRecord record) {
        if (record == null) return null;
        return new CustomerView(record.customerId(), record.userId(), record.fullName(), record.phone(), record.pinSet());
    }
}
