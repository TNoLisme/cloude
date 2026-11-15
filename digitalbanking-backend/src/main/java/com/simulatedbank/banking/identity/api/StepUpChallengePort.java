package com.simulatedbank.banking.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface StepUpChallengePort {
    Challenge create(long userId, Purpose purpose, String requestHash, String idempotencyKey);

    long consumeVerifiedChallenge(UUID challengeId, long userId, Purpose expectedPurpose, String expectedRequestHash);

    enum Purpose {
        LOGIN_NEW_DEVICE,
        TRANSFER_STEP_UP,
        SENSITIVE_ACTION
    }

    record Challenge(UUID challengeId, Purpose purpose, List<String> methods, String channel,
            String maskedDestination, Instant expiresAt, int maxAttempts, int remainingAttempts) {
        public Challenge {
            methods = List.copyOf(methods);
        }

        public Map<String, Object> publicView() {
            return Map.of(
                    "challengeId", challengeId.toString(),
                    "purpose", purpose.name(),
                    "methods", methods,
                    "channel", channel,
                    "maskedDestination", maskedDestination,
                    "expiresAt", expiresAt.toString(),
                    "maxAttempts", maxAttempts,
                    "remainingAttempts", remainingAttempts);
        }
    }
}
