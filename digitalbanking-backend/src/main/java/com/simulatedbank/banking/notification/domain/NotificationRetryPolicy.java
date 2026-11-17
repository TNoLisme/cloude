package com.simulatedbank.banking.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Deterministic retry policy for outbox delivery.
 */
public class NotificationRetryPolicy {

    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration leaseDuration;

    public NotificationRetryPolicy(int maxAttempts, Duration baseBackoff, Duration leaseDuration) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.baseBackoff = baseBackoff == null ? Duration.ofSeconds(5) : baseBackoff;
        this.leaseDuration = leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration leaseDuration() {
        return leaseDuration;
    }

    public Instant calculateNextRetry(Instant current, int currentRetryCount) {
        long multiplier = 1L << Math.min(Math.max(0, currentRetryCount - 1), 6);
        long delaySeconds = baseBackoff.getSeconds() * multiplier;
        return current.plusSeconds(delaySeconds);
    }

    public NotificationStatus determineNextStatus(int newRetryCount) {
        return newRetryCount >= maxAttempts ? NotificationStatus.FAILED : NotificationStatus.RETRYING;
    }

    public String toSafeFailureCode(Throwable throwable) {
        if (throwable == null) {
            return "UNKNOWN_ERROR";
        }
        String msg = throwable.getMessage() == null ? "" : throwable.getMessage().toLowerCase(Locale.ROOT);
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return "GATEWAY_TIMEOUT";
        }
        if (msg.contains("unavailable") || msg.contains("connection refused")) {
            return "PROVIDER_UNAVAILABLE";
        }
        return "PROVIDER_TEMPORARY_ERROR";
    }

    public String exhaustedFailureCode() {
        return "DELIVERY_EXHAUSTED";
    }
}
