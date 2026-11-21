package com.simulatedbank.banking.limit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** OI-API-05: optional policy bounds must be optional and never silently invented. */
class LimitPolicyTest {

    @Test
    void acceptsValuesAtOrBelowConfiguredBounds() {
        LimitPolicy policy = new LimitPolicy(properties("1000", "5000"));

        policy.requireWithinPolicy(money("1000"), money("5000"));
        policy.requireWithinPolicy(money("1"), money("1000"));
    }

    @Test
    void rejectsPerTransactionAboveTheConfiguredBound() {
        LimitPolicy policy = new LimitPolicy(properties("1000", "5000"));

        assertOutOfPolicy(() -> policy.requireWithinPolicy(money("1000.01"), money("5000")));
    }

    @Test
    void rejectsDailyAboveTheConfiguredBound() {
        LimitPolicy policy = new LimitPolicy(properties("1000", "5000"));

        assertOutOfPolicy(() -> policy.requireWithinPolicy(money("1000"), money("5000.01")));
    }

    @Test
    void absentBoundsImposeNoLimit() {
        LimitPolicy policy = new LimitPolicy(new LimitPolicyProperties(null, null));

        policy.requireWithinPolicy(money("99999999999999999.99"), money("99999999999999999.99"));
    }

    @Test
    void invalidConfiguredBoundsFailFastInsteadOfWeakeningThePolicy() {
        assertThatThrownBy(() -> properties("0", "5000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("-1", "5000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("1000", "999")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("1000", "0")).isInstanceOf(IllegalArgumentException.class);
    }

    private static LimitPolicyProperties properties(String maxPerTransaction, String maxDaily) {
        return new LimitPolicyProperties(optionalMoney(maxPerTransaction), optionalMoney(maxDaily));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal optionalMoney(String value) {
        return value == null ? null : money(value);
    }

    private static void assertOutOfPolicy(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.LIMIT_OUT_OF_POLICY));
    }
}
