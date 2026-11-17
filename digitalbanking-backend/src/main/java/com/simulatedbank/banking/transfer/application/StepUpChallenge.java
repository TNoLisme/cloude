package com.simulatedbank.banking.transfer.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Public step-up challenge payload for 428 (API contracts §6.3); never carries OTP or raw contact. */
public record StepUpChallenge(UUID challengeId, String purpose, List<String> methods, String channel,
                              String maskedDestination, Instant expiresAt, int maxAttempts, int remainingAttempts) {
    public StepUpChallenge {
        Objects.requireNonNull(challengeId, "challengeId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(expiresAt, "expiresAt");
        methods = List.copyOf(methods);
        if (methods.isEmpty() || maxAttempts < 1 || remainingAttempts < 0 || remainingAttempts > maxAttempts) {
            throw new IllegalArgumentException("Invalid challenge values");
        }
    }
}