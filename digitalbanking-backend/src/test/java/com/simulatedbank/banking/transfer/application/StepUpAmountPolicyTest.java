package com.simulatedbank.banking.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.transfer.domain.TransferAmount;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class StepUpAmountPolicyTest {
    private final StepUpAmountPolicy policy = new StepUpAmountPolicy(money("10000000"), money("20000000"));

    @Test
    void singleAmountBoundaryIsInclusive() {
        assertThat(policy.evaluate(amount("9999999.99"), money("0"), money("0")).singleAmountTriggered()).isFalse();
        assertThat(policy.evaluate(amount("10000000"), money("0"), money("0")).singleAmountTriggered()).isTrue();
    }

    @Test
    void projectedDailyBoundaryIsExclusiveAndIncludesPendingAndCurrent() {
        var equal = policy.evaluate(amount("1"), money("10000000"), money("9999999"));
        var greater = policy.evaluate(amount("1.01"), money("10000000"), money("9999999"));
        assertThat(equal.requiresStepUp()).isFalse();
        assertThat(greater.dailyAmountTriggered()).isTrue();
        assertThat(greater.singleAmountTriggered()).isFalse();
    }

    @Test
    void exactDecimalArithmeticAndConfiguredThresholdsAreUsed() {
        var configured = new StepUpAmountPolicy(money("3"), money("0.30"));
        assertThat(configured.evaluate(amount("0.10"), money("0.10"), money("0.10")).requiresStepUp()).isFalse();
        assertThat(configured.evaluate(amount("0.11"), money("0.10"), money("0.10")).requiresStepUp()).isTrue();
        assertThat(configured.evaluate(amount("3"), money("0"), money("0")).singleAmountTriggered()).isTrue();
    }

    @Test
    void invalidSnapshotsOrMissingConfigurationFailClosed() {
        assertThatThrownBy(() -> new StepUpAmountPolicy(null, money("1"))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StepUpAmountPolicy(money("0"), money("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StepUpAmountPolicy(money("1"), money("1.001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.evaluate(amount("1"), money("-1"), money("0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.evaluate(amount("1"), money("0"), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> policy.evaluate(amount("1"), money("0"), money("0.001"))).isInstanceOf(IllegalArgumentException.class);
    }

    private static TransferAmount amount(String value) {
        return new TransferAmount(money(value), "VND");
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
