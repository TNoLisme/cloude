package com.simulatedbank.banking.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.account.api.AccountBalance;
import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.account.domain.AccountStatus;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Unit contract của {@link AccountBalanceService} (BE-ACC-07): validation trước SQL, mapping
 * {@code RETURNING}, phân loại 0-row debit, credit 0-row là system error, delegation lock và
 * lan truyền lỗi kỹ thuật. Bằng chứng transaction/lock thật nằm ở
 * {@code AccountBalancePostgreSqlIntegrationTest}.
 */
class AccountBalanceServiceTest {

    private static final long SOURCE_ID = 3001;
    private static final long DESTINATION_ID = 3002;
    private static final long CUSTOMER_ID = 2001;
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private StubBalances balances;
    private StubLocks locks;
    private AccountBalanceService service;

    @BeforeEach
    void setUp() {
        balances = new StubBalances();
        locks = new StubLocks();
        service = new AccountBalanceService(new AccountLockService(locks), balances);
    }

    @Test
    void lockTransferAccountsDelegatesToTheSharedAscendingLockPrimitive() {
        locks.rows = List.of(
                row(SOURCE_ID, CUSTOMER_ID, AccountStatus.ACTIVE, "1000.00"),
                row(DESTINATION_ID, 2002, AccountStatus.LOCKED, "50.00"));

        AccountBalance.LockedAccounts locked = service.lockTransferAccounts(SOURCE_ID, DESTINATION_ID);

        assertThat(locks.calls).isEqualTo(1);
        assertThat(locks.firstId).isEqualTo(SOURCE_ID);
        assertThat(locks.secondId).isEqualTo(DESTINATION_ID);
        assertThat(locked.source().accountId()).isEqualTo(SOURCE_ID);
        assertThat(locked.source().status()).isEqualTo(AccountQuery.Status.ACTIVE);
        assertThat(locked.source().availableBalance()).isEqualByComparingTo("1000.00");
        assertThat(locked.destination().accountId()).isEqualTo(DESTINATION_ID);
        assertThat(locked.destination().status()).isEqualTo(AccountQuery.Status.LOCKED);
        assertThat(balances.debitCalls).isZero();
    }

    @Test
    void debitAppliedMapsReturningBalances() {
        balances.debitResult = Optional.of(balanceRow(SOURCE_ID, "900.00", "900.00"));

        AccountBalance.DebitResult result = service.debitConditional(SOURCE_ID, AMOUNT);

        assertThat(result.outcome()).isEqualTo(AccountBalance.DebitOutcome.APPLIED);
        assertThat(result.balanceAfter()).isNotNull();
        assertThat(result.balanceAfter().availableBalance()).isEqualByComparingTo("900.00");
        assertThat(result.balanceAfter().ledgerBalance()).isEqualByComparingTo("900.00");
        assertThat(balances.debitCalls).isEqualTo(1);
        assertThat(balances.debitAccountId).isEqualTo(SOURCE_ID);
        assertThat(balances.debitAmount).isEqualByComparingTo(AMOUNT);
        assertThat(balances.reReadCalls).isZero();
    }

    @Test
    void debitZeroRowOnActiveAccountIsInsufficientBalance() {
        balances.debitResult = Optional.empty();
        balances.reReadResult = Optional.of(balanceRow(SOURCE_ID, "50.00", "50.00"));

        AccountBalance.DebitResult result = service.debitConditional(SOURCE_ID, AMOUNT);

        assertThat(result.outcome()).isEqualTo(AccountBalance.DebitOutcome.INSUFFICIENT_BALANCE);
        assertThat(result.balanceAfter()).isNull();
        assertThat(balances.reReadAccountId).isEqualTo(SOURCE_ID);
    }

    @Test
    void debitZeroRowOnNonActiveAccountIsInactiveRegardlessOfBalance() {
        for (AccountStatus status : List.of(AccountStatus.LOCKED, AccountStatus.CLOSED)) {
            StubBalances stub = new StubBalances();
            stub.reReadResult = Optional.of(new AccountBalancePort.BalanceRow(
                    SOURCE_ID, status, new BigDecimal("1000.00"), new BigDecimal("1000.00")));
            AccountBalanceService subject = new AccountBalanceService(new AccountLockService(new StubLocks()), stub);

            AccountBalance.DebitResult result = subject.debitConditional(SOURCE_ID, AMOUNT);

            assertThat(result.outcome()).isEqualTo(AccountBalance.DebitOutcome.INACTIVE);
            assertThat(result.balanceAfter()).isNull();
        }
    }

    @Test
    void debitZeroRowOnMissingAccountFailsClosedWithAccountNotFound() {
        balances.debitResult = Optional.empty();
        balances.reReadResult = Optional.empty();

        assertThatThrownBy(() -> service.debitConditional(SOURCE_ID, AMOUNT))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
        assertThat(balances.reReadCalls).isEqualTo(1);
    }

    @Test
    void creditMapsReturningBalances() {
        balances.creditResult = Optional.of(balanceRow(DESTINATION_ID, "1100.00", "1100.00"));

        AccountBalance.CreditResult result = service.credit(DESTINATION_ID, AMOUNT);

        assertThat(result.balanceAfter().availableBalance()).isEqualByComparingTo("1100.00");
        assertThat(result.balanceAfter().ledgerBalance()).isEqualByComparingTo("1100.00");
        assertThat(balances.creditCalls).isEqualTo(1);
    }

    @Test
    void creditZeroRowIsSystemErrorBecauseTheDebitSideMayAlreadyBeApplied() {
        balances.creditResult = Optional.empty();

        assertThatThrownBy(() -> service.credit(DESTINATION_ID, AMOUNT))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void invalidAmountsAreRejectedBeforeAnyPersistenceCall() {
        List<BigDecimal> invalid = java.util.Arrays.asList(
                null,
                new BigDecimal("0.00"),
                new BigDecimal("-0.01"),
                new BigDecimal("1.001"),
                new BigDecimal("100000000000000000.00"));

        for (BigDecimal amount : invalid) {
            assertThatThrownBy(() -> service.debitConditional(SOURCE_ID, amount))
                    .as("debit amount %s", amount)
                    .isInstanceOfSatisfying(BankingBusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
            assertThatThrownBy(() -> service.credit(DESTINATION_ID, amount))
                    .as("credit amount %s", amount)
                    .isInstanceOfSatisfying(BankingBusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }

        assertThat(balances.debitCalls).isZero();
        assertThat(balances.creditCalls).isZero();
        assertThat(balances.reReadCalls).isZero();
    }

    @Test
    void invalidAccountIdentifiersAreRejectedBeforeAnyPersistenceCall() {
        for (long invalid : new long[] {0, -1}) {
            assertThatThrownBy(() -> service.debitConditional(invalid, AMOUNT))
                    .isInstanceOfSatisfying(BankingBusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
            assertThatThrownBy(() -> service.credit(invalid, AMOUNT))
                    .isInstanceOfSatisfying(BankingBusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }
        assertThat(balances.debitCalls).isZero();
        assertThat(balances.creditCalls).isZero();
    }

    @Test
    void technicalPersistenceFailuresPropagateUnchanged() {
        DataAccessResourceFailureException debitFailure =
                new DataAccessResourceFailureException("connection lost during debit");
        balances.debitFailure = debitFailure;
        assertThatThrownBy(() -> service.debitConditional(SOURCE_ID, AMOUNT)).isSameAs(debitFailure);

        DataAccessResourceFailureException creditFailure =
                new DataAccessResourceFailureException("connection lost during credit");
        balances.creditFailure = creditFailure;
        assertThatThrownBy(() -> service.credit(DESTINATION_ID, AMOUNT)).isSameAs(creditFailure);
    }

    private static AccountBalancePort.BalanceRow balanceRow(long accountId, String available, String ledger) {
        return new AccountBalancePort.BalanceRow(accountId, AccountStatus.ACTIVE,
                new BigDecimal(available), new BigDecimal(ledger));
    }

    private static AccountLockPort.LockedAccountRow row(
            long accountId, long customerId, AccountStatus status, String balance) {
        return new AccountLockPort.LockedAccountRow(accountId, customerId, status, "VND",
                new BigDecimal(balance), new BigDecimal(balance));
    }

    private static final class StubBalances implements AccountBalancePort {

        private Optional<BalanceRow> debitResult = Optional.empty();
        private Optional<BalanceRow> creditResult = Optional.empty();
        private Optional<BalanceRow> reReadResult = Optional.empty();
        private RuntimeException debitFailure;
        private RuntimeException creditFailure;
        private int debitCalls;
        private int creditCalls;
        private int reReadCalls;
        private long debitAccountId;
        private BigDecimal debitAmount;
        private long reReadAccountId;

        @Override
        public Optional<BalanceRow> debitIfActiveAndSufficient(long accountId, BigDecimal amount) {
            debitCalls++;
            debitAccountId = accountId;
            debitAmount = amount;
            if (debitFailure != null) {
                throw debitFailure;
            }
            return debitResult;
        }

        @Override
        public Optional<BalanceRow> creditIfActive(long accountId, BigDecimal amount) {
            creditCalls++;
            if (creditFailure != null) {
                throw creditFailure;
            }
            return creditResult;
        }

        @Override
        public Optional<BalanceRow> findByIdForUpdate(long accountId) {
            reReadCalls++;
            reReadAccountId = accountId;
            return reReadResult;
        }
    }

    private static final class StubLocks implements AccountLockPort {

        private List<LockedAccountRow> rows = List.of();
        private int calls;
        private long firstId;
        private long secondId;

        @Override
        public List<LockedAccountRow> lockTwoForUpdateOrderByIdAsc(long firstAccountId, long secondAccountId) {
            calls++;
            firstId = firstAccountId;
            secondId = secondAccountId;
            return rows;
        }
    }
}
