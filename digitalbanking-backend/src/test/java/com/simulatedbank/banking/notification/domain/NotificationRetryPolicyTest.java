package com.simulatedbank.banking.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRetryPolicyTest {

    private final NotificationRetryPolicy policy = new NotificationRetryPolicy(3, Duration.ofSeconds(5), Duration.ofSeconds(30));

    @Test
    @DisplayName("Initial retry calculates backoff deterministically")
    void calculatesBackoff() {
        Instant now = Instant.parse("2026-11-15T10:00:00Z");

        // retryCount = 1 -> delay 5s
        Instant next1 = policy.calculateNextRetry(now, 1);
        assertThat(next1).isEqualTo(now.plusSeconds(5));

        // retryCount = 2 -> delay 10s (exponential 5 * 2^(2-1))
        Instant next2 = policy.calculateNextRetry(now, 2);
        assertThat(next2).isEqualTo(now.plusSeconds(10));
    }

    @Test
    @DisplayName("Next status becomes FAILED when retry count reaches or exceeds max attempts")
    void reachesTerminalFailedState() {
        assertThat(policy.determineNextStatus(1)).isEqualTo(NotificationStatus.RETRYING);
        assertThat(policy.determineNextStatus(2)).isEqualTo(NotificationStatus.RETRYING);
        assertThat(policy.determineNextStatus(3)).isEqualTo(NotificationStatus.FAILED);
        assertThat(policy.determineNextStatus(4)).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    @DisplayName("Safe internal failure codes do not expose provider details")
    void safeFailureCodes() {
        assertThat(policy.toSafeFailureCode(new RuntimeException("Connection timed out to third-party Twilio provider")))
                .isEqualTo("GATEWAY_TIMEOUT");
        assertThat(policy.toSafeFailureCode(new IllegalStateException("Raw provider 500 internal server error text with secret")))
                .isEqualTo("PROVIDER_TEMPORARY_ERROR");
        assertThat(policy.exhaustedFailureCode())
                .isEqualTo("DELIVERY_EXHAUSTED");
    }
}
