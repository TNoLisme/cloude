package com.simulatedbank.banking.notification.api;

import java.time.Instant;

/**
 * Synchronous, single-attempt OTP delivery boundary (design/05 §11.3; BE-ID-IMP-06).
 *
 * <p>The caller invokes this port only after challenge commit, outside any transaction.
 * {@code recipientUserId} is a non-PII routing handle, not a client-supplied destination:
 * the adapter must resolve the recipient's trusted contact and enforce channel policy.
 * {@code challengeRef} is the provider deduplication key. ACCEPTED means the provider
 * accepted delivery, not that the recipient received/read the message.</p>
 *
 * <p>The OTP array is borrowed only until this synchronous call returns. Adapters must
 * never retain, log, serialize to durable storage, or asynchronously capture it; they must
 * also clear any transient secret buffers they create. The caller owns wiping the array.
 * Never include OTPs, contact details, credentials, or provider response bodies in results.
 * Adapters must enforce finite connect/response deadlines in their transport. This port
 * cannot safely impose a deadline on an arbitrary blocking adapter without leaking a
 * still-running secret-bearing task; no executor timeout or automatic retry is supplied.
 * An uncertain send must remain ambiguous, never be retried automatically.</p>
 *
 * <p>No production fallback implementation is provided. Durable secret handoff,
 * crash recovery, resend/invalidation and leases remain blocked on MOD-OI-07.</p>
 */
public interface OtpGateway {

    enum Outcome {
        ACCEPTED, RETRYABLE, FAILED
    }

    enum Failure {
        NONE, TIMEOUT, UNAVAILABLE, THROTTLED, REJECTED, INVALID_RESPONSE, UNCONFIGURED, AMBIGUOUS
    }

    /**
     * ACCEPTED requires NONE; other outcomes require a failure. RETRYABLE is only a
     * provider classification (TIMEOUT/UNAVAILABLE/THROTTLED/AMBIGUOUS), not retry permission.
     */
    record Result(Outcome outcome, Failure failure) {
    }

    Result sendOtp(String challengeRef, long recipientUserId, String channel, char[] otp,
            Instant expiresAt, String correlationId);
}
