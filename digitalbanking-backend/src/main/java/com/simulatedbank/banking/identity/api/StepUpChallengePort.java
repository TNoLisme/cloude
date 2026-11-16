package com.simulatedbank.banking.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Candidate DESIGN-MOD-05 contract. Identity supplies the production implementation. */
public interface StepUpChallengePort {
    /** OWN: outside the business transaction; never creates an approval or returns a credential. */
    Challenge requestChallenge(ActionBinding binding);

    /** JOIN: require VERIFIED, actor/purpose/binding/expiry match; consume once, rollback with caller. */
    void consumeVerified(UUID challengeId, ActionBinding binding);

    record ActionBinding(long userId, String action, Long approvalId, String requestHash) { }

    record Challenge(UUID challengeId, String purpose, List<String> methods, Instant expiresAt,
                     int maxAttempts, int remainingAttempts) {
        public Challenge { methods = List.copyOf(methods); }
    }
}
