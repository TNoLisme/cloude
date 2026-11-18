package com.simulatedbank.banking.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.limit.application.LimitChangePolicy;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

class LimitChangePolicyTest {
    @ParameterizedTest
    @CsvSource(value = {"NULL,NULL", "100,NULL", "NULL,200", "'',200", "invalid,200", "0,200",
            "-1,200", "1.001,200", "201,200", "100,0", "100,NaN", "1E+100,200"}, nullValues = "NULL")
    void absentIncompleteOrInvalidPolicyRefusesChanges(String per, String daily) {
        var environment = new MockEnvironment();
        if (per != null) environment.setProperty("app.limit.policy.max-per-transaction-vnd", per);
        if (daily != null) environment.setProperty("app.limit.policy.max-daily-vnd", daily);
        assertThatThrownBy(() -> new LimitChangePolicy(environment).check(new BigDecimal("1"), new BigDecimal("2")))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void equalityIsAllowedAndEitherMaximumIsEnforced() {
        var policy = new LimitChangePolicy(new MockEnvironment()
                .withProperty("app.limit.policy.max-per-transaction-vnd", "100")
                .withProperty("app.limit.policy.max-daily-vnd", "200"));
        assertThatCode(() -> policy.check(new BigDecimal("100"), new BigDecimal("200"))).doesNotThrowAnyException();
        for (var pair : new String[][]{{"100.01", "200"}, {"100", "200.01"}}) {
            assertThatThrownBy(() -> policy.check(new BigDecimal(pair[0]), new BigDecimal(pair[1])))
                    .isInstanceOfSatisfying(BankingBusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.LIMIT_OUT_OF_POLICY));
        }
    }
}
