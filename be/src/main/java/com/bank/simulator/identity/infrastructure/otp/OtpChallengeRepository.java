package com.bank.simulator.identity.infrastructure.otp;

import java.time.Instant;
import java.util.UUID;

public interface OtpChallengeRepository {

    void create(UUID id, String identifier, String channel, String purpose, String otpHash,
                Instant createdAt, Instant expiresAt, int maxAttempts);

    OtpChallengeRecord lockChallenge(UUID id, String identifier, String channel, String purpose, Instant now);

    OtpChallengeRecord lockLatestActive(String identifier, String channel, String purpose, Instant now);

    void incrementAttempts(UUID id, int attempts, Instant invalidatedAt);

    void invalidate(UUID id, Instant invalidatedAt);

    void invalidateActive(String identifier, String channel, String purpose, Instant invalidatedAt);

    boolean consume(UUID id, Instant consumedAt);
}
