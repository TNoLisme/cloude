package com.simulatedbank.banking.transfer.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumer seam for the Identity step-up SPI (TRFC-ID-02 requestChallenge, TRFC-ID-05
 * findVerifiedBindingIfPresent). The provider owns its T0 transaction and OTP dispatch: it returns
 * an issued challenge only after the gateway ACK and SENT persist for the current generation, and
 * signals dispatch failure/unknown with {@link OtpDispatchException}. The verified-binding lookup is
 * a primary read whose empty result means "no valid challenge", never a database error; errors throw.
 * The snapshot is not a right to consume — T1 revalidates and consumes under lock via TRFC-ID-04.
 * Display fields beyond the minimal baseline are a proposal pending Identity review (05 §13.4).
 */
public interface StepUpChallengePort {
    String PURPOSE_TRANSFER_STEP_UP = "TRANSFER_STEP_UP";

    ChallengeIssued requestChallenge(ChallengeRequest request);

    Optional<VerifiedChallenge> findVerifiedBindingIfPresent(ChallengeRequest request);

    /** Binding is server-side actor plus the exact canonical hash and global key; never from a body. */
    record ChallengeRequest(long userId, long customerId, String purpose, String requestHash,
                            String idempotencyKey, String correlationId) {
        public ChallengeRequest {
            if (userId <= 0 || customerId <= 0 || !PURPOSE_TRANSFER_STEP_UP.equals(purpose)
                    || requestHash == null || !requestHash.matches("[0-9a-f]{64}")
                    || idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:~\\-]{1,255}")
                    || correlationId == null || correlationId.isBlank() || correlationId.length() > 64) {
                throw new IllegalArgumentException("Invalid step-up challenge binding");
            }
        }

        @Override public String toString() { return "ChallengeRequest[redacted]"; }
    }

    /** Safe 428 display payload; never carries OTP or raw contact. channel/maskedDestination may be null. */
    record ChallengeIssued(UUID challengeRef, String method, List<String> methods, String channel,
                           String maskedDestination, Instant expiresAt, int maxAttempts, int remainingAttempts) {
        public ChallengeIssued {
            Objects.requireNonNull(challengeRef, "challengeRef");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (method == null || method.isBlank()) throw new IllegalArgumentException("Invalid challenge method");
            methods = List.copyOf(methods);
            if (methods.isEmpty() || maxAttempts < 1 || remainingAttempts < 0 || remainingAttempts > maxAttempts) {
                throw new IllegalArgumentException("Invalid challenge values");
            }
        }
    }

    record VerifiedChallenge(UUID challengeRef, Instant expiresAt) {
        public VerifiedChallenge {
            Objects.requireNonNull(challengeRef, "challengeRef");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /** Gateway dispatch failed, timed out or is unknown; the provider invalidates when it safely can. */
    final class OtpDispatchException extends RuntimeException {
        public OtpDispatchException() { super("OTP dispatch failed"); }
    }
}