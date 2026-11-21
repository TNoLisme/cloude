package com.simulatedbank.banking.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Contract guards của {@code account.api} (BE-ACC-01): mask số tài khoản theo OpenAPI, money
 * {@code NUMERIC(19,2)} VND, ID dương, trace ID hợp lệ và hình dạng kết quả debit/credit.
 *
 * <p>Đây là hợp đồng cross-module: nếu guard mất, consumer có thể log/DTO hoá số tài khoản đầy đủ
 * hoặc truyền số dư sai scale — lỗi người dùng cuối thấy được (AC-023/AC-024).</p>
 */
class AccountApiContractTest {

    private static final String MASKED = "**** **** 6789";
    private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");

    @Test
    void snapshotAcceptsOnlyMaskedVndAccountWithPositiveIds() {
        AccountQuery.AccountSnapshot snapshot = new AccountQuery.AccountSnapshot(
                3001, 2001, AccountQuery.Status.ACTIVE, "VND", MASKED, "6789");

        assertThat(snapshot.accountNumberMasked()).isEqualTo(MASKED);
        assertThat(snapshot.customerId()).isEqualTo(2001);
    }

    @Test
    void snapshotRejectsUnmaskedNumberMismatchedLast4AndForeignCurrency() {
        assertThatThrownBy(() -> new AccountQuery.AccountSnapshot(
                1, 2, AccountQuery.Status.ACTIVE, "VND", "0123456789", "6789"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountQuery.AccountSnapshot(
                1, 2, AccountQuery.Status.ACTIVE, "VND", MASKED, "1234"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountQuery.AccountSnapshot(
                1, 2, AccountQuery.Status.ACTIVE, "USD", MASKED, "6789"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountQuery.AccountSnapshot(
                0, 2, AccountQuery.Status.ACTIVE, "VND", MASKED, "6789"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void viewRejectsNegativeOrOverScaledMoney() {
        assertThatThrownBy(() -> view(new BigDecimal("-0.01"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> view(new BigDecimal("1.001"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(view(BigDecimal.ZERO, BigDecimal.ZERO).availableBalance())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void accessQuerySeparatesOwnerAndStaffScopeExplicitly() {
        assertThat(AccountQuery.AccountViewQuery.forOwner(3001, 2001, "corr-1").ownerScope()).isTrue();
        assertThat(AccountQuery.AccountViewQuery.forStaff(3001, "corr-1").ownerScope()).isFalse();
        assertThatThrownBy(() -> AccountQuery.AccountViewQuery.forStaff(3001, "has space"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountQuery.AccountViewQuery.forOwner(0, 2001, "corr-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lockedAccountsRequireDistinctPositiveAccounts() {
        AccountBalance.LockedAccount source = locked(3001);
        AccountBalance.LockedAccount destination = locked(3002);

        assertThat(new AccountBalance.LockedAccounts(source, destination).destination().accountId())
                .isEqualTo(3002);
        assertThatThrownBy(() -> new AccountBalance.LockedAccounts(source, source))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void debitResultCarriesBalanceOnlyWhenApplied() {
        AccountBalance.BalanceAfter balanceAfter = new AccountBalance.BalanceAfter(
                new BigDecimal("1000.00"), new BigDecimal("1000.00"));

        assertThat(new AccountBalance.DebitResult(AccountBalance.DebitOutcome.APPLIED, balanceAfter)
                .balanceAfter()).isEqualTo(balanceAfter);
        assertThat(new AccountBalance.DebitResult(AccountBalance.DebitOutcome.INSUFFICIENT_BALANCE, null)
                .balanceAfter()).isNull();
        assertThatThrownBy(() -> new AccountBalance.DebitResult(
                AccountBalance.DebitOutcome.INSUFFICIENT_BALANCE, balanceAfter))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountBalance.DebitResult(AccountBalance.DebitOutcome.APPLIED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AccountQuery.AccountView view(BigDecimal available, BigDecimal ledger) {
        return new AccountQuery.AccountView(3001, 2001, AccountQuery.Status.ACTIVE, "VND",
                available, ledger, MASKED, "6789", NOW, NOW, NOW);
    }

    private static AccountBalance.LockedAccount locked(long accountId) {
        return new AccountBalance.LockedAccount(accountId, 2001, AccountQuery.Status.ACTIVE, "VND",
                new BigDecimal("1000.00"), new BigDecimal("1000.00"));
    }
}
