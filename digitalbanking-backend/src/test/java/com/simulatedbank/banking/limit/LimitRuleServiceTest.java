package com.simulatedbank.banking.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.limit.adapter.out.LimitJdbcRepository;
import com.simulatedbank.banking.limit.api.ApplicableLimits;
import com.simulatedbank.banking.limit.api.LimitConfiguration;
import com.simulatedbank.banking.limit.api.LimitRules.CheckOutcome;
import com.simulatedbank.banking.limit.application.LimitRuleService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LimitRuleServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    private final LimitJdbcRepository repository = mock(LimitJdbcRepository.class);
    private final LimitRuleService service = new LimitRuleService(repository);

    @Test
    void missingConfigurationIsDistinctFromPassAndHasNoEffectiveCeiling() {
        when(repository.findEffective(11, 21, NOW)).thenReturn(List.of());
        assertThat(service.resolve(11, 21, NOW).effectivePerTransactionLimit()).isEmpty();
        assertThat(service.checkPerTransaction(11, 21, new BigDecimal("1.00"), NOW))
                .isEqualTo(CheckOutcome.NOT_CONFIGURED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bothScopesAreCheckedWhicheverIsStricter(boolean customerIsStricter) {
        var customer = config(null, customerIsStricter ? "100.00" : "200.00");
        var account = config(21L, customerIsStricter ? "200.00" : "100.00");
        when(repository.findEffective(11, 21, NOW)).thenReturn(List.of(customer, account));
        assertThat(service.resolve(11, 21, NOW).effectivePerTransactionLimit()).hasValue(new BigDecimal("100.00"));
        assertThat(service.checkPerTransaction(11, 21, new BigDecimal("99.99"), NOW)).isEqualTo(CheckOutcome.PASS);
        assertThat(service.checkPerTransaction(11, 21, new BigDecimal("100"), NOW)).isEqualTo(CheckOutcome.PASS);
        assertFailure(() -> service.checkPerTransaction(11, 21, new BigDecimal("100.01"), NOW), ErrorCode.LIMIT_EXCEEDED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void eitherScopeCanBeTheOnlyConfiguration(boolean customerOnly) {
        when(repository.findEffective(11, 21, NOW)).thenReturn(List.of(config(customerOnly ? null : 21L, "100")));
        assertThat(service.checkPerTransaction(11, 21, new BigDecimal("100.00"), NOW)).isEqualTo(CheckOutcome.PASS);
        assertFailure(() -> service.checkPerTransaction(11, 21, new BigDecimal("101"), NOW), ErrorCode.LIMIT_EXCEEDED);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-0.01", "1.001", "1.000", "100000000000000000", "1E+100"})
    void invalidAmountIsNeverAcceptedEvenWithNoConfiguration(String amount) {
        assertFailure(() -> service.checkPerTransaction(11, 21, amount == null ? null : new BigDecimal(amount), NOW),
                ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void maximumRepresentableAmountPassesWithoutRounding() {
        when(repository.findEffective(11, 21, NOW)).thenReturn(List.of(config(null, "99999999999999999.99")));
        assertThat(service.checkPerTransaction(11, 21, new BigDecimal("99999999999999999.99"), NOW))
                .isEqualTo(CheckOutcome.PASS);
    }

    @Test
    void applicableItemsAreDefensivelyCopiedAndImmutable() {
        var items = new ArrayList<>(List.of(config(null, "100")));
        var applicable = new ApplicableLimits(11, 21, NOW, items);
        items.clear();
        assertThat(applicable.items()).hasSize(1);
        assertThatThrownBy(() -> applicable.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static LimitConfiguration config(Long accountId, String perTransaction) {
        return new LimitConfiguration(1L, 11, accountId, new BigDecimal(perTransaction),
                new BigDecimal("99999999999999999.99"), "VND", NOW, null, LimitConfiguration.Status.ACTIVE,
                1, 1, 2L, null, NOW, NOW, NOW);
    }

    private static void assertFailure(Runnable operation, ErrorCode code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BankingBusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
