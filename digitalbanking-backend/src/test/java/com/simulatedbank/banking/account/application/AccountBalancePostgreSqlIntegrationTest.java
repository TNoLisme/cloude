package com.simulatedbank.banking.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.account.api.AccountBalance;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-ACC-07 PostgreSQL 16 evidence cho conditional debit/credit và rollback của T2.
 *
 * <p>Chứng minh trên engine thật (không H2/mock): debit/credit conditional cập nhật cả
 * {@code available_balance}/{@code ledger_balance}; insufficient/inactive là business outcome
 * không đổi tiền; amount sai bị chặn trước SQL; precision biên {@code NUMERIC(19,2)}; nhiều
 * transaction đồng thời không overdraw/lost update; credit fail hoặc DB fail sau debit rollback
 * toàn T2; retry sau rollback không nhân đôi debit; operation bắt buộc có transaction.</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AccountBalancePostgreSqlIntegrationTest {

    private static final BigDecimal START = new BigDecimal("1000.00");
    private static final BigDecimal MAX_NUMERIC = new BigDecimal("99999999999999999.99");
    private static final Duration AWAIT = Duration.ofSeconds(30);

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("account_balance");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        // Concurrency scenarios need more independent connections than the 3-connection test default.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "16");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
    }

    @Autowired
    private AccountBalanceService balanceService;

    @Autowired
    private AccountNumberProtection protection;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private ExecutorService executor;
    private long customerA;
    private long customerB;
    private long source;
    private long destination;
    private long unrelated;

    @BeforeEach
    void seedTwoCustomersAndAccounts() {
        transaction = new TransactionTemplate(transactionManager);
        executor = Executors.newCachedThreadPool();
        transaction.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM banking.account");
            jdbc.update("DELETE FROM banking.customer");
            jdbc.update("DELETE FROM banking.app_user WHERE username <> '__fraud_rule_bootstrap__'");
        });
        transaction.executeWithoutResult(ignored -> {
            customerA = seedCustomer(seedUser("acc-balance-a"));
            customerB = seedCustomer(seedUser("acc-balance-b"));
            source = seedAccount(customerA, "0011111111", START, "ACTIVE");
            destination = seedAccount(customerB, "0022222222", START, "ACTIVE");
            unrelated = seedAccount(customerA, "0033333333", START, "ACTIVE");
        });
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void debitReducesAvailableAndLedgerAndReturnsPostMutationBalances() {
        AccountBalance.DebitResult result = inTransaction(() -> {
            balanceService.lockTransferAccounts(source, destination);
            return balanceService.debitConditional(source, new BigDecimal("250.00"));
        });

        assertThat(result.outcome()).isEqualTo(AccountBalance.DebitOutcome.APPLIED);
        assertThat(result.balanceAfter().availableBalance()).isEqualByComparingTo("750.00");
        assertThat(result.balanceAfter().ledgerBalance()).isEqualByComparingTo("750.00");
        assertThat(result.balanceAfter().availableBalance().scale()).isEqualTo(2);
        assertThat(availableOf(source)).isEqualByComparingTo("750.00");
        assertThat(ledgerOf(source)).isEqualByComparingTo("750.00");
        assertThat(availableOf(destination)).isEqualByComparingTo(START);
        assertThat(availableOf(unrelated)).isEqualByComparingTo(START);
    }

    @Test
    void creditIncreasesAvailableAndLedgerAndReturnsPostMutationBalances() {
        AccountBalance.CreditResult result = inTransaction(() -> {
            balanceService.lockTransferAccounts(source, destination);
            return balanceService.credit(destination, new BigDecimal("0.01"));
        });

        assertThat(result.balanceAfter().availableBalance()).isEqualByComparingTo("1000.01");
        assertThat(result.balanceAfter().ledgerBalance()).isEqualByComparingTo("1000.01");
        assertThat(availableOf(destination)).isEqualByComparingTo("1000.01");
        assertThat(ledgerOf(destination)).isEqualByComparingTo("1000.01");
        assertThat(availableOf(source)).isEqualByComparingTo(START);
    }

    @Test
    void insufficientFundsIsABusinessOutcomeThatLeavesBalancesUntouched() {
        AccountBalance.DebitResult result = inTransaction(() -> {
            balanceService.lockTransferAccounts(source, destination);
            return balanceService.debitConditional(source, new BigDecimal("1000.01"));
        });

        assertThat(result.outcome()).isEqualTo(AccountBalance.DebitOutcome.INSUFFICIENT_BALANCE);
        assertThat(result.balanceAfter()).isNull();
        assertThat(availableOf(source)).isEqualByComparingTo(START);
        assertThat(ledgerOf(source)).isEqualByComparingTo(START);
    }

    @Test
    void nonActiveSourceIsInactiveForDebitAndSystemErrorForCredit() {
        for (String status : List.of("LOCKED", "CLOSED")) {
            setStatus(source, status);

            AccountBalance.DebitResult debit = inTransaction(() -> {
                balanceService.lockTransferAccounts(source, destination);
                return balanceService.debitConditional(source, new BigDecimal("1.00"));
            });
            assertThat(debit.outcome())
                    .as("status %s", status)
                    .isEqualTo(AccountBalance.DebitOutcome.INACTIVE);
            assertThat(debit.balanceAfter()).isNull();

            assertThatThrownBy(() -> inTransaction(() -> {
                balanceService.lockTransferAccounts(source, destination);
                return balanceService.credit(source, new BigDecimal("1.00"));
            })).isInstanceOfSatisfying(BankingBusinessException.class,
                    failure -> assertThat(failure.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));

            assertThat(availableOf(source)).isEqualByComparingTo(START);
            assertThat(ledgerOf(source)).isEqualByComparingTo(START);
        }
    }

    @Test
    void missingAccountIsNotFoundOnDebitAndSystemErrorOnCredit() {
        assertThatThrownBy(() -> inTransaction(
                () -> balanceService.debitConditional(999_999L, new BigDecimal("1.00"))))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));

        assertThatThrownBy(() -> inTransaction(
                () -> balanceService.credit(999_999L, new BigDecimal("1.00"))))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    // ------------------------------------------------------------------ validation / precision

    @Test
    void invalidAmountsAreRejectedBeforeAnySqlAndWithoutTakingLocks() {
        List<BigDecimal> invalid = java.util.Arrays.asList(
                null,
                new BigDecimal("0.00"),
                new BigDecimal("-100.00"),
                new BigDecimal("1.001"),
                new BigDecimal("100000000000000000.00"));

        for (BigDecimal amount : invalid) {
            assertThatThrownBy(() -> inTransaction(
                    () -> balanceService.debitConditional(source, amount)))
                    .as("debit amount %s", amount)
                    .isInstanceOfSatisfying(BankingBusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
            assertThatThrownBy(() -> inTransaction(
                    () -> balanceService.credit(destination, amount)))
                    .as("credit amount %s", amount)
                    .isInstanceOfSatisfying(BankingBusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }

        assertRowNotLocked(source);
        assertRowNotLocked(destination);
        assertThat(availableOf(source)).isEqualByComparingTo(START);
        assertThat(availableOf(destination)).isEqualByComparingTo(START);
    }

    @Test
    void exactBoundaryAmountsNeverOverdrawAndKeepScaleTwo() {
        AccountBalance.DebitResult almostAll = inTransaction(() -> {
            balanceService.lockTransferAccounts(source, destination);
            return balanceService.debitConditional(source, new BigDecimal("999.99"));
        });
        assertThat(almostAll.outcome()).isEqualTo(AccountBalance.DebitOutcome.APPLIED);
        assertThat(availableOf(source)).isEqualByComparingTo("0.01");

        AccountBalance.DebitResult oneCentTooMuch = inTransaction(() -> {
            balanceService.lockTransferAccounts(source, destination);
            return balanceService.debitConditional(source, new BigDecimal("0.02"));
        });
        assertThat(oneCentTooMuch.outcome()).isEqualTo(AccountBalance.DebitOutcome.INSUFFICIENT_BALANCE);
        assertThat(availableOf(source)).isEqualByComparingTo("0.01");

        AccountBalance.DebitResult exactRemainder = inTransaction(() -> {
            balanceService.lockTransferAccounts(source, destination);
            return balanceService.debitConditional(source, new BigDecimal("0.01"));
        });
        assertThat(exactRemainder.outcome()).isEqualTo(AccountBalance.DebitOutcome.APPLIED);
        assertThat(exactRemainder.balanceAfter().availableBalance()).isEqualByComparingTo("0.00");
        assertThat(exactRemainder.balanceAfter().availableBalance().scale()).isEqualTo(2);
        assertThat(availableOf(source)).isEqualByComparingTo("0.00");
        assertThat(ledgerOf(source)).isEqualByComparingTo("0.00");
    }

    @Test
    void maximumNumericBalanceDebitsToZeroAndCreditOverflowFailsClosed() {
        long empty = seedAccount(customerA, "0044444444", BigDecimal.ZERO, "ACTIVE");

        AccountBalance.CreditResult credited = inTransaction(() -> {
            balanceService.lockTransferAccounts(empty, destination);
            return balanceService.credit(empty, MAX_NUMERIC);
        });
        assertThat(credited.balanceAfter().availableBalance()).isEqualByComparingTo(MAX_NUMERIC);
        assertThat(availableOf(empty)).isEqualByComparingTo(MAX_NUMERIC);

        assertThatThrownBy(() -> inTransaction(() -> {
            balanceService.lockTransferAccounts(empty, destination);
            return balanceService.credit(empty, new BigDecimal("0.01"));
        })).isInstanceOf(DataAccessException.class);

        assertThat(availableOf(empty)).isEqualByComparingTo(MAX_NUMERIC);

        AccountBalance.DebitResult drained = inTransaction(() -> {
            balanceService.lockTransferAccounts(empty, destination);
            return balanceService.debitConditional(empty, MAX_NUMERIC);
        });
        assertThat(drained.outcome()).isEqualTo(AccountBalance.DebitOutcome.APPLIED);
        assertThat(drained.balanceAfter().availableBalance()).isEqualByComparingTo("0.00");
        assertThat(ledgerOf(empty)).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrentDebitsNeverOverdrawAndNeverLoseAnUpdate() throws Exception {
        int workers = 12;
        BigDecimal perDebit = new BigDecimal("150.00");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AccountBalance.DebitOutcome>> futures = new ArrayList<>();
        for (int index = 0; index < workers; index++) {
            futures.add(executor.submit(() -> {
                await(start);
                return newTransaction().execute(ignored -> {
                    balanceService.lockTransferAccounts(source, destination);
                    return balanceService.debitConditional(source, perDebit).outcome();
                });
            }));
        }
        start.countDown();

        int applied = 0;
        int rejected = 0;
        for (Future<AccountBalance.DebitOutcome> future : futures) {
            AccountBalance.DebitOutcome outcome = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
            if (outcome == AccountBalance.DebitOutcome.APPLIED) {
                applied++;
            } else {
                assertThat(outcome).isEqualTo(AccountBalance.DebitOutcome.INSUFFICIENT_BALANCE);
                rejected++;
            }
        }

        assertThat(applied).isEqualTo(6);
        assertThat(rejected).isEqualTo(workers - 6);
        assertThat(availableOf(source)).isEqualByComparingTo("100.00");
        assertThat(ledgerOf(source)).isEqualByComparingTo("100.00");
        assertThat(perDebit.multiply(BigDecimal.valueOf(applied)).add(availableOf(source)))
                .isEqualByComparingTo(START);
        assertNoBrokenInvariant();
    }

    @Test
    void concurrentCreditsAndDebitsKeepTheTotalBalanceConstant() throws Exception {
        int transfers = 8;
        BigDecimal perTransfer = new BigDecimal("100.00");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < transfers; index++) {
            futures.add(executor.submit(() -> {
                await(start);
                counterTransfer(perTransfer);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
        }

        assertThat(availableOf(source)).isEqualByComparingTo("200.00");
        assertThat(availableOf(destination)).isEqualByComparingTo("1800.00");
        assertThat(availableOf(source).add(availableOf(destination)))
                .isEqualByComparingTo(START.add(START));
        assertThat(availableOf(unrelated)).isEqualByComparingTo(START);
        assertNoBrokenInvariant();
    }

    // ------------------------------------------------------------------ rollback / retry

    @Test
    void creditFailureAfterDebitRollsBackTheWholeTransaction() {
        setStatus(destination, "LOCKED");

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            balanceService.lockTransferAccounts(source, destination);
            assertThat(balanceService.debitConditional(source, new BigDecimal("100.00")).outcome())
                    .isEqualTo(AccountBalance.DebitOutcome.APPLIED);
            balanceService.credit(destination, new BigDecimal("100.00"));
        })).isInstanceOfSatisfying(BankingBusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));

        assertThat(availableOf(source)).isEqualByComparingTo(START);
        assertThat(ledgerOf(source)).isEqualByComparingTo(START);
        assertThat(availableOf(destination)).isEqualByComparingTo(START);
        assertRowNotLocked(source);
    }

    @Test
    void databaseFailureAfterDebitRollsBackTheWholeTransaction() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            balanceService.lockTransferAccounts(source, destination);
            assertThat(balanceService.debitConditional(source, new BigDecimal("100.00")).outcome())
                    .isEqualTo(AccountBalance.DebitOutcome.APPLIED);
            // Violates ck_account_ledger_balance_non_negative: PostgreSQL aborts the transaction.
            jdbc.update("UPDATE banking.account SET ledger_balance = -1 WHERE account_id = ?", destination);
        })).isInstanceOf(DataAccessException.class);

        assertThat(availableOf(source)).isEqualByComparingTo(START);
        assertThat(ledgerOf(source)).isEqualByComparingTo(START);
        assertThat(ledgerOf(destination)).isEqualByComparingTo(START);
        assertRowNotLocked(source);
    }

    @Test
    void rolledBackAttemptCanBeRetriedWithoutDuplicatingTheDebit() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            balanceService.lockTransferAccounts(source, destination);
            assertThat(balanceService.debitConditional(source, new BigDecimal("100.00")).outcome())
                    .isEqualTo(AccountBalance.DebitOutcome.APPLIED);
            throw new IllegalStateException("simulated connection loss before commit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(availableOf(source)).isEqualByComparingTo(START);

        AccountBalance.DebitResult retry = inTransaction(() -> {
            balanceService.lockTransferAccounts(source, destination);
            return balanceService.debitConditional(source, new BigDecimal("100.00"));
        });

        assertThat(retry.outcome()).isEqualTo(AccountBalance.DebitOutcome.APPLIED);
        assertThat(availableOf(source)).isEqualByComparingTo("900.00");
        assertThat(ledgerOf(source)).isEqualByComparingTo("900.00");
    }

    @Test
    void moneyOperationsRequireAnActiveTransactionAndFailFast() {
        assertThatThrownBy(() -> balanceService.debitConditional(source, new BigDecimal("1.00")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> balanceService.credit(destination, new BigDecimal("1.00")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> balanceService.lockTransferAccounts(source, destination))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(availableOf(source)).isEqualByComparingTo(START);
        assertThat(availableOf(destination)).isEqualByComparingTo(START);
    }

    // ------------------------------------------------------------------ helpers

    /** Một lượt T2 rút gọn: lock → debit → credit, giống orchestrator sẽ gọi ở BE-ACC-08. */
    private void counterTransfer(BigDecimal amount) {
        newTransaction().executeWithoutResult(ignored -> {
            balanceService.lockTransferAccounts(source, destination);
            assertThat(balanceService.debitConditional(source, amount).outcome())
                    .isEqualTo(AccountBalance.DebitOutcome.APPLIED);
            balanceService.credit(destination, amount);
        });
    }

    private TransactionTemplate newTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return newTransaction().execute(status -> work.get());
    }

    private void assertRowNotLocked(long accountId) {
        inTransaction(() -> jdbc.queryForObject(
                "SELECT account_id FROM banking.account WHERE account_id = ? FOR UPDATE NOWAIT",
                Long.class, accountId));
    }

    private void assertNoBrokenInvariant() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM banking.account
                WHERE available_balance < 0 OR ledger_balance < 0 OR available_balance > ledger_balance
                """, Long.class)).isZero();
    }

    private void setStatus(long accountId, String status) {
        // Hikari runs with auto-commit=false (application.yml); DML outside a transaction is rolled
        // back when the connection returns to the pool, so commit through a TransactionTemplate.
        newTransaction().executeWithoutResult(ignored -> {
            if ("CLOSED".equals(status)) {
                jdbc.update("UPDATE banking.account SET status = ?, closed_at = now() WHERE account_id = ?",
                        status, accountId);
            } else {
                jdbc.update("UPDATE banking.account SET status = ?, closed_at = NULL WHERE account_id = ?",
                        status, accountId);
            }
        });
    }

    private BigDecimal availableOf(long accountId) {
        return jdbc.queryForObject(
                "SELECT available_balance FROM banking.account WHERE account_id = ?",
                BigDecimal.class, accountId);
    }

    private BigDecimal ledgerOf(long accountId) {
        return jdbc.queryForObject(
                "SELECT ledger_balance FROM banking.account WHERE account_id = ?",
                BigDecimal.class, accountId);
    }

    private long seedUser(String prefix) {
        return jdbc.queryForObject("""
                INSERT INTO banking.app_user (username, password_hash, user_type)
                VALUES (?, 'synthetic-not-a-credential', 'CUSTOMER')
                RETURNING user_id
                """, Long.class, prefix + "-" + UUID.randomUUID());
    }

    private long seedCustomer(long userId) {
        return jdbc.queryForObject("""
                INSERT INTO banking.customer (user_id, full_name_enc)
                VALUES (?, ?)
                RETURNING customer_id
                """, Long.class, userId, new byte[] {1, 2, 3});
    }

    private long seedAccount(long customerId, String accountNumber, BigDecimal balance, String status) {
        AccountNumberProtection.ProtectedAccountNumber protectedAccount = protection.protect(accountNumber);
        // Same auto-commit=false rule as setStatus: seed through a transaction so the row is visible
        // to the money-path transactions that follow.
        return newTransaction().execute(ignored -> jdbc.queryForObject("""
                INSERT INTO banking.account
                    (customer_id, account_no_enc, account_no_hash, account_no_last4,
                     status, available_balance, ledger_balance, closed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING account_id
                """,
                Long.class,
                customerId,
                protectedAccount.encrypted(),
                protectedAccount.blindIndex(),
                protectedAccount.accountNumberLast4(),
                status,
                balance,
                balance,
                "CLOSED".equals(status)
                        ? java.sql.Timestamp.from(java.time.Instant.now())
                        : null));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(AWAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for concurrency latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for concurrency latch");
        }
    }
}
