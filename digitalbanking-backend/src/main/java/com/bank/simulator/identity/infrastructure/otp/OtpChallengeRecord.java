package com.bank.simulator.identity.infrastructure.otp;

import java.time.Instant;
import java.util.UUID;

public record OtpChallengeRecord(UUID id, String identifier, String channel, String purpose,
                                 String otpHash, int attempts, int maxAttempts,
                                 Instant expiresAt, Instant consumedAt, Instant invalidatedAt) {
}
