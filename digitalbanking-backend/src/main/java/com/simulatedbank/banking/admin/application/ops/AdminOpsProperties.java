package com.simulatedbank.banking.admin.application.ops;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * BE-ADM-OPS-01: operational thresholds for the Admin approval workflow (no business semantics).
 *
 * <p>The sweep is read-only detection. {@code stuckAfter} is the age at which a still-{@code PENDING} approval is
 * reported as stuck, {@code attemptWindow} is the rolling window used to count failed checker attempts, and
 * {@code maxFailedAttempts} is the retry-exhaustion threshold. All values are bounded configuration, never
 * per-actor data, so they can safely influence metrics/alerts.</p>
 */
@ConfigurationProperties(prefix = "app.admin.ops")
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
@Validated
public record AdminOpsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT30M") Duration stuckAfter,
        @DefaultValue("PT1H") Duration attemptWindow,
        @DefaultValue("5") int maxFailedAttempts,
        @DefaultValue("PT5M") Duration sweepDelay,
        @DefaultValue("PT1M") Duration sweepInitialDelay
) {

    public AdminOpsProperties {
        requirePositive(stuckAfter, "app.admin.ops.stuck-after");
        requirePositive(attemptWindow, "app.admin.ops.attempt-window");
        requirePositive(sweepDelay, "app.admin.ops.sweep-delay");
        if (sweepInitialDelay == null || sweepInitialDelay.isNegative()) {
            throw new IllegalArgumentException("app.admin.ops.sweep-initial-delay must not be negative");
        }
        if (maxFailedAttempts < 1) {
            throw new IllegalArgumentException("app.admin.ops.max-failed-attempts must be positive");
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }
}
