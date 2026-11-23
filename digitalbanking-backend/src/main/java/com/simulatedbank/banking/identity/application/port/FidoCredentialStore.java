package com.simulatedbank.banking.identity.application.port;

import java.time.Instant;
import java.util.Optional;

/** FIDO material and ceremony state; every operation joins a caller-owned transaction. */
public interface FidoCredentialStore {
    record Credential(long deviceId, long userId, String deviceRef, String credentialId,
            String publicKeyCose, String userHandle, long signCount, boolean trusted, String status) {
        @Override
        public String toString() { return "Credential[redacted]"; }
    }

    record Ceremony(long challengeId, String challengeRef, long userId, long deviceId,
            String credentialId, String nonce, String status, int attemptCount, int maxAttempts,
            Instant expiresAt, Instant consumedAt, String purpose, String method) {
        @Override
        public String toString() { return "Ceremony[redacted]"; }
    }

    Optional<Credential> findCredential(String deviceRef, long userId);

    /** Acquire the cross-instance device lock and refresh any already-managed projection. */
    Optional<Credential> lockCredential(long deviceId, long userId);

    void lockUser(long userId);

    /** Acquire the cross-instance ceremony lock and refresh any already-managed projection. */
    Optional<Ceremony> lockCeremony(String challengeRef);

    Ceremony createCeremony(long userId, long deviceId, String credentialId, String nonce,
            Instant expiresAt, int maxAttempts, String correlationId);

    /** Called only after locking this device; committed atomically with ceremony verification. */
    void advanceCounter(long deviceId, long signCount);

    boolean verifyCeremony(long challengeId);

    void failCeremony(long challengeId);

    void expireCeremony(long challengeId);

    /** Whether an unconsumed CREATED FIDO login ceremony can still be verified at database wall time. */
    boolean isLive(long challengeId);
}
