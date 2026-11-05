package com.bank.simulator.transfer.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound port to the identity module. The transfer module only talks to
 * identity through this interface; the adapter lives in transfer infrastructure.
 *
 * <p>OTP lifecycle is split in two phases so that dispatch can happen after the
 * money transaction commits: {@link #issueOtp} only persists the challenge,
 * {@link #dispatchOtp} delivers it, and {@link #invalidateOtp} supports the
 * compensation path.</p>
 */
public interface IdentityModuleApi {

    PinVerificationResult verifyPin(UUID customerId, String pin);

    CustomerView findCustomerByUserId(UUID userId);

    CustomerView findCustomerById(UUID customerId);

    OtpIssue issueOtp(UUID customerId, String purpose);

    void dispatchOtp(OtpIssue issue);

    OtpConsumeResult consumeOtp(UUID challengeId, String otp);

    void invalidateOtp(UUID challengeId);

    enum PinVerificationResult { VALID, INVALID, LOCKED, NOT_SET }

    record CustomerView(UUID customerId, UUID userId, String fullName, String phone, boolean pinSet) {}

    record OtpIssue(UUID challengeId, UUID customerId, String phone, String code, Instant expiresAt) {}

    record OtpConsumeResult(Outcome outcome, int attempts, int maxAttempts) {
        public enum Outcome {
            VALID, INVALID, EXPIRED, ATTEMPTS_EXCEEDED, INVALIDATED, ALREADY_USED, NOT_FOUND
        }

        public static OtpConsumeResult of(Outcome outcome, int attempts, int maxAttempts) {
            return new OtpConsumeResult(outcome, attempts, maxAttempts);
        }
    }
}
