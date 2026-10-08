package com.bank.simulator.identity.infrastructure.otp;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class OtpChallengeService {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration TTL = Duration.ofSeconds(120);

    private final java.security.SecureRandom secureRandom = new java.security.SecureRandom();
    private final OtpChallengeRepository repository;
    private final OtpHashingService hashingService;
    private final OtpSender sender;
    private final Clock clock;

    public OtpChallengeService(OtpChallengeRepository repository, OtpHashingService hashingService,
                               OtpSender sender, Clock clock) {
        this.repository = repository;
        this.hashingService = hashingService;
        this.sender = sender;
        this.clock = clock;
    }

    public OtpIssueResult issue(String identifier, String channel, String purpose) {
        UUID challengeId = UUID.randomUUID();
        String code = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
        Instant now = clock.instant();
        Instant expiresAt = now.plus(TTL);
        if ("RECOVERY".equals(purpose)) repository.invalidateActive(identifier, channel, purpose, now);
        repository.create(challengeId, identifier, channel, purpose, hashingService.encode(code), now, expiresAt, MAX_ATTEMPTS);
        try {
            sender.send(new OtpMessage(challengeId, identifier, code, expiresAt));
        } catch (RuntimeException exception) {
            repository.invalidate(challengeId, now);
            throw exception;
        }
        return new OtpIssueResult(challengeId, expiresAt, (int) TTL.toSeconds());
    }

    @Transactional
    public ConsumeResult consume(UUID challengeId, String identifier, String channel, String purpose, String code) {
        Instant now = clock.instant();
        OtpChallengeRecord challenge = repository.lockChallenge(challengeId, identifier, channel, purpose, now);
        return consumeLocked(challenge, code, now, identifier, channel, purpose);
    }

    @Transactional
    public ConsumeResult consumeLatest(String identifier, String channel, String purpose, String code) {
        Instant now = clock.instant();
        OtpChallengeRecord challenge = repository.lockLatestActive(identifier, channel, purpose, now);
        return consumeLocked(challenge, code, now, identifier, channel, purpose);
    }

    private ConsumeResult consumeLocked(OtpChallengeRecord challenge, String code, Instant now,
                                        String identifier, String channel, String purpose) {
        if (challenge == null) return ConsumeResult.NOT_FOUND;
        if (challenge.consumedAt() != null) return ConsumeResult.ALREADY_USED;
        if (challenge.invalidatedAt() != null || challenge.attempts() >= challenge.maxAttempts()) return ConsumeResult.ATTEMPTS_EXCEEDED;
        if (!challenge.expiresAt().isAfter(now)) return ConsumeResult.EXPIRED;
        if (!hashingService.matches(code, challenge.otpHash())) {
            int attempts = challenge.attempts() + 1;
            repository.incrementAttempts(challenge.id(), attempts, attempts >= challenge.maxAttempts() ? now : null);
            return attempts >= challenge.maxAttempts() ? ConsumeResult.ATTEMPTS_EXCEEDED : ConsumeResult.INVALID;
        }
        return repository.consume(challenge.id(), now) ? ConsumeResult.VALID : ConsumeResult.ALREADY_USED;
    }

    public enum ConsumeResult { VALID, INVALID, EXPIRED, ALREADY_USED, ATTEMPTS_EXCEEDED, NOT_FOUND }

    public record OtpIssueResult(UUID challengeId, Instant expiresAt, int expiresInSeconds) {
    }
}
