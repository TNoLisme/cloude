package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.transfer.domain.TransferAmount;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Pure amount triggers only. The caller must obtain both totals and the policy
 * snapshot under the customer guard, count each transfer once, and evaluate
 * other independent risk triggers. This class does not acquire locks, reserve
 * funds, authenticate a challenge, or approve a transfer.
 */
public final class StepUpAmountPolicy {
    private final BigDecimal singleThreshold;
    private final BigDecimal dailyThreshold;

    public StepUpAmountPolicy(BigDecimal singleThreshold, BigDecimal dailyThreshold) {
        this.singleThreshold = positive(singleThreshold, "single threshold");
        this.dailyThreshold = positive(dailyThreshold, "daily threshold");
    }

    public Decision evaluate(TransferAmount current, BigDecimal completedToday, BigDecimal pendingReservations) {
        Objects.requireNonNull(current, "current");
        BigDecimal completed = nonnegative(completedToday, "completed total");
        BigDecimal pending = nonnegative(pendingReservations, "pending total");
        BigDecimal projected = completed.add(pending).add(current.value());
        return new Decision(current.value().compareTo(singleThreshold) >= 0,
                projected.compareTo(dailyThreshold) > 0);
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        BigDecimal result = nonnegative(value, name);
        if (result.signum() == 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return result;
    }

    private static BigDecimal nonnegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0 || value.scale() > 2) {
            throw new IllegalArgumentException(name + " must be nonnegative with at most two decimal places");
        }
        return value;
    }

    public record Decision(boolean singleAmountTriggered, boolean dailyAmountTriggered) {
        public boolean requiresStepUp() {
            return singleAmountTriggered || dailyAmountTriggered;
        }
    }
}
