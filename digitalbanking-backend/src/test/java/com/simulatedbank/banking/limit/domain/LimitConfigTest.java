package com.simulatedbank.banking.limit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for the framework-free Limit aggregate and its transition/version rules. */
class LimitConfigTest {

    private static final Instant EFFECTIVE_FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant APPROVED_AT = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void pendingNormalizesMoneyAndUsesTheNextVersion() {
        LimitConfig pending = pending(0L, "1000", "2000");

        assertThat(pending.status()).isEqualTo(LimitConfigStatus.PENDING_APPROVAL);
        assertThat(pending.version()).isEqualTo(1);
        assertThat(pending.perTransactionLimit()).isEqualByComparingTo("1000.00");
        assertThat(pending.dailyLimit()).isEqualByComparingTo("2000.00");
        assertThat(pending.perTransactionLimit().scale()).isEqualTo(2);
        assertThat(pending.dailyLimit().scale()).isEqualTo(2);
        assertThat(pending.currency()).isEqualTo("VND");
        assertThat(pending.effectiveFrom()).isEqualTo(EFFECTIVE_FROM);
        assertThat(pending.approvedBy()).isNull();
        assertThat(pending.limitConfigId()).isZero();
    }

    @Test
    void pendingRejectsNonPositiveOrNotRepresentableMoney() {
        assertValidation(() -> pending(0L, "0", "2000"));
        assertValidation(() -> pending(0L, "-1", "2000"));
        assertValidation(() -> pending(0L, "1000", "0"));
        assertValidation(() -> pending(0L, "1000", "999.99"));
        assertValidation(() -> pending(0L, "1000.001", "2000"));
        assertValidation(() -> pending(0L, "100000000000000000", "100000000000000000"));
    }

    @Test
    void pendingRejectsUnsupportedCurrencyAndMissingEffectiveFrom() {
        assertValidation(() -> LimitConfig.pending(1, 2, 3, null, money("1000"), money("2000"),
                "USD", EFFECTIVE_FROM, 0));
        assertValidation(() -> LimitConfig.pending(1, 2, 3, null, money("1000"), money("2000"),
                null, EFFECTIVE_FROM, 0));
        assertValidation(() -> LimitConfig.pending(1, 2, 3, null, money("1000"), money("2000"),
                "VND", null, 0));
    }

    @Test
    void pendingRejectsInvalidExpectedVersionAndIdentifiers() {
        assertValidation(() -> pending(-1L, "1000", "2000"));
        assertValidation(() -> pending((long) Integer.MAX_VALUE, "1000", "2000"));
        assertValidation(() -> LimitConfig.pending(0, 2, 3, null, money("1000"), money("2000"),
                "VND", EFFECTIVE_FROM, 0));
        assertValidation(() -> LimitConfig.pending(1, 0, 3, null, money("1000"), money("2000"),
                "VND", EFFECTIVE_FROM, 0));
        assertValidation(() -> LimitConfig.pending(1, 2, 0, null, money("1000"), money("2000"),
                "VND", EFFECTIVE_FROM, 0));
        assertValidation(() -> LimitConfig.pending(1, 2, 3, -4L, money("1000"), money("2000"),
                "VND", EFFECTIVE_FROM, 0));
    }

    @Test
    void activateRecordsTheCheckerAndEnforcesMakerCheckerSeparation() {
        LimitConfig active = pending(3L, "1000", "2000").activate(99L, APPROVED_AT);

        assertThat(active.status()).isEqualTo(LimitConfigStatus.ACTIVE);
        assertThat(active.approvedBy()).isEqualTo(99L);
        assertThat(active.approvalId()).isEqualTo(7L);
        assertThat(active.perTransactionLimit()).isEqualByComparingTo("1000.00");

        assertCode(() -> pending(3L, "1000", "2000").activate(pending(3L, "1000", "2000").createdBy(),
                APPROVED_AT), ErrorCode.ROLE_CONFLICT);
    }

    @Test
    void activateIsOnlyLegalFromPendingApproval() {
        LimitConfig active = pending(3L, "1000", "2000").activate(99L, APPROVED_AT);

        assertCode(() -> active.activate(100L, APPROVED_AT), ErrorCode.INVALID_STATE_TRANSITION);
        assertCode(() -> active.reject(), ErrorCode.INVALID_STATE_TRANSITION);
        assertCode(() -> active.supersede().supersede(), ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void supersedeIsOnlyLegalFromActiveAndKeepsTheDecisionFields() {
        LimitConfig active = pending(3L, "1000", "2000").activate(99L, APPROVED_AT);
        LimitConfig superseded = active.supersede();

        assertThat(superseded.status()).isEqualTo(LimitConfigStatus.SUPERSEDED);
        assertThat(superseded.approvedBy()).isEqualTo(active.approvedBy());
        assertThat(superseded.perTransactionLimit()).isEqualByComparingTo(active.perTransactionLimit());

        assertCode(pending(3L, "1000", "2000")::supersede, ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void rejectIsOnlyLegalFromPendingApprovalAndClearsDecisionFields() {
        LimitConfig rejected = pending(3L, "1000", "2000").reject();

        assertThat(rejected.status()).isEqualTo(LimitConfigStatus.REJECTED);
        assertThat(rejected.approvedBy()).isNull();

        LimitConfig active = pending(3L, "1000", "2000").activate(99L, APPROVED_AT);
        assertCode(active::reject, ErrorCode.INVALID_STATE_TRANSITION);
        assertCode(rejected::reject, ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void requirePendingVersionRejectsEveryStaleOrFutureExpectation() {
        LimitConfig pending = pending(4L, "1000", "2000");

        pending.requirePendingVersion(4L);

        assertCode(() -> pending.requirePendingVersion(0L), ErrorCode.ROLE_CONFLICT);
        assertCode(() -> pending.requirePendingVersion(3L), ErrorCode.ROLE_CONFLICT);
        assertCode(() -> pending.requirePendingVersion(5L), ErrorCode.ROLE_CONFLICT);
    }

    @Test
    void matchesActiveVersionOnlyForTheExpectedActiveVersion() {
        LimitConfig pending = pending(2L, "1000", "2000");
        LimitConfig active = pending.activate(99L, APPROVED_AT);

        assertThat(active.matchesActiveVersion(3L)).isTrue();
        assertThat(active.matchesActiveVersion(0L)).isFalse();
        assertThat(active.matchesActiveVersion(2L)).isFalse();
        assertThat(pending.matchesActiveVersion(3L)).isFalse();
    }

    private static LimitConfig pending(long expectedVersion, String perTransaction, String daily) {
        return LimitConfig.pending(7L, 3L, 11L, null, money(perTransaction), money(daily), "VND",
                EFFECTIVE_FROM, expectedVersion);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static void assertValidation(Runnable operation) {
        assertCode(operation, ErrorCode.VALIDATION_ERROR);
    }

    private static void assertCode(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(expected));
    }
}
