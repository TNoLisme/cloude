package com.simulatedbank.banking.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.simulatedbank.banking.account.api.AccountBalance;
import com.simulatedbank.banking.account.api.AccountQuery;
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
import java.util.concurrent.atomic.AtomicReference;
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
 * BE-ACC-06 PostgreSQL 16 concurrency evidence cho pessimistic row lock của T2.
 *
 * <p>Chứng minh trên engine thật (không H2/mock): hai transaction cùng row bị serialize; lock giữ
 * tới commit/rollback; rollback nhả lock và không để lại thay đổi; {@code FOR UPDATE NOWAIT} từ
 * connection khác thấy row đang bị khóa; row không liên quan không bị khóa; thứ tự
 * {@code account_id ASC} không deadlock với chiều chuyển ngược; lock wait có bound theo
 * {@code lock_timeout} và lỗi {@code 55P03} là technical failure.</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AccountLockPostgreSqlIntegrationTest {

    private static final BigDecimal START = new BigDecimal("1000.00");
    private static final Duration AWAIT = Duration.ofSeconds(20);

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("account_lock");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        // Concurrency scenarios need several independent connections; the default test pool is 3.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
    }

    @Autowired
    private AccountLockService lockService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private long customerA;
    private long customerB;
    private long source;
    private long destination;
    private long unrelated;
    private ExecutorService executor;

    @BeforeEach
    void seedTwoCustomersAndAccounts() {
        transaction = new TransactionTemplate(transactionManager);
        executor = Executors.newCachedThreadPool();
        transaction.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM banking.account");
            jdbc.update("DELETE FROM banking.customer");
            jdbc.update("DELETE FROM banking.app_user");
        });
        transaction.executeWithoutResult(ignored -> {
            long userA = seedUser("acc-lock-a");
            long userB = seedUser("acc-lock-b");
            customerA = seedCustomer(userA);
            customerB = seedCustomer(userB);
            source = seedAccount(customerA, "0011111111");
            destination = seedAccount(customerB, "0022222222");
            unrelated = seedAccount(customerA, "0033333333");
        });
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ basic contract

    @Test
    void locksBothRowsAndMapsSourceDestinationRolesFromTheArguments() {
        AccountBalance.LockedAccounts locked = inTransaction(
                () -> lockService.lockTransferAccounts(source, destination));

        assertThat(locked.source().accountId()).isEqualTo(source);
        assertThat(locked.source().customerId()).isEqualTo(customerA);
        assertThat(locked.destination().accountId()).isEqualTo(destination);
        assertThat(locked.destination().customerId()).isEqualTo(customerB);
        assertThat(locked.source().status()).isEqualTo(AccountQuery.Status.ACTIVE);
        assertThat(locked.source().currency()).isEqualTo("VND");
        assertThat(locked.source().availableBalance()).isEqualByComparingTo(START);
        assertThat(locked.source().ledgerBalance()).isEqualByComparingTo(START);

        // Role mapping follows the argument order; SQL lock acquisition order stays account_id ASC.
        AccountBalance.LockedAccounts reversed = inTransaction(
                () -> lockService.lockTransferAccounts(destination, source));
        assertThat(reversed.source().accountId()).isEqualTo(destination);
        assertThat(reversed.destination().accountId()).isEqualTo(source);
    }

    @Test
    void lockRequiresAnActiveTransactionAndFailsFast() {
        assertThatThrownBy(() -> lockService.lockTransferAccounts(source, destination))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertRowNotLocked(source);
    }

    @Test
    void sameOrNonPositiveIdentifiersAreRejectedBeforeAnySql() {
        for (long[] invalid : List.of(
                new long[] {source, source},
                new long[] {0, destination},
                new long[] {source, -1})) {
            assertThatThrownBy(() -> inTransaction(
                    () -> lockService.lockTransferAccounts(invalid[0], invalid[1])))
                    .isInstanceOfSatisfying(BankingBusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }
        assertRowNotLocked(source);
        assertThat(balanceOf(source)).isEqualByComparingTo(START);
    }

    @Test
    void missingRowIsAccountNotFoundAndLeavesNoLockBehindAfterRollback() {
        assertThatThrownBy(() -> inTransaction(
                () -> lockService.lockTransferAccounts(source, 999_999)))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));

        assertRowNotLocked(source);
        assertRowNotLocked(destination);
        assertThat(balanceOf(source)).isEqualByComparingTo(START);
    }

    // ------------------------------------------------------------------ real PostgreSQL locks

    @Test
    void postgresRejectsNoWaitLockOnTheHeldRowsButNotOnUnrelatedRows() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = executor.submit(() -> {
            newTransaction().executeWithoutResult(ignored -> {
                lockService.lockTransferAccounts(source, destination);
                locked.countDown();
                await(release);
            });
        });
        assertThat(locked.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        try {
            assertLockNotAvailable(catchThrowable(() -> inTransaction(() ->
                    jdbc.queryForObject(
                            "SELECT account_id FROM banking.account WHERE account_id = ? FOR UPDATE NOWAIT",
                            Long.class, source))));
            assertLockNotAvailable(catchThrowable(() -> inTransaction(() ->
                    jdbc.queryForObject(
                            "SELECT account_id FROM banking.account WHERE account_id = ? FOR UPDATE NOWAIT",
                            Long.class, destination))));
            // Only the two requested rows are locked; a sibling account is still available.
            assertRowNotLocked(unrelated);
        } finally {
            release.countDown();
        }
        holder.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
        assertRowNotLocked(source);
        assertRowNotLocked(destination);
    }

    @Test
    void secondTransactionWaitsForTheFirstCommitAndSeesCommittedBalancesWithoutDirtyRead() throws Exception {
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondLocked = new CountDownLatch(1);
        AtomicReference<BigDecimal> secondSeen = new AtomicReference<>();

        Future<?> first = executor.submit(() -> newTransaction().executeWithoutResult(ignored -> {
            lockService.lockTransferAccounts(source, destination);
            jdbc.update("UPDATE banking.account "
                    + "SET available_balance = available_balance + 500, "
                    + "ledger_balance = ledger_balance + 500, updated_at = now() "
                    + "WHERE account_id = ?", source);
            firstLocked.countDown();
            await(releaseFirst);
        }));
        assertThat(firstLocked.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        Future<?> second = executor.submit(() -> newTransaction().executeWithoutResult(ignored -> {
            secondAttempted.countDown();
            lockService.lockTransferAccounts(source, destination);
            secondSeen.set(balanceOf(source));
            secondLocked.countDown();
        }));
        assertThat(secondAttempted.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        // While the first transaction is uncommitted, the second must not acquire the row.
        assertThat(secondLocked.await(700, TimeUnit.MILLISECONDS)).isFalse();
        releaseFirst.countDown();
        first.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
        second.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(secondLocked.getCount()).isZero();
        // READ COMMITTED: after the lock is granted the second transaction sees the committed row.
        assertThat(secondSeen.get()).isEqualByComparingTo("1500.00");
        assertThat(balanceOf(source)).isEqualByComparingTo("1500.00");
    }

    @Test
    void concurrentReadModifyWriteOnTheSameRowsLosesNoUpdates() throws Exception {
        int workers = 6;
        BigDecimal increment = new BigDecimal("10.00");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < workers; index++) {
            futures.add(executor.submit(() -> {
                await(start);
                return newTransaction().execute(ignored -> {
                    lockService.lockTransferAccounts(source, destination);
                    BigDecimal current = balanceOf(source);
                    sleep(25);
                    jdbc.update("UPDATE banking.account "
                            + "SET available_balance = ?, ledger_balance = ?, updated_at = now() "
                            + "WHERE account_id = ?",
                            current.add(increment), current.add(increment), source);
                    return null;
                });
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
        }

        BigDecimal expected = START.add(increment.multiply(BigDecimal.valueOf(workers)));
        assertThat(balanceOf(source)).isEqualByComparingTo(expected);
        assertThat(jdbc.queryForObject(
                "SELECT ledger_balance FROM banking.account WHERE account_id = ?",
                BigDecimal.class, source)).isEqualByComparingTo(expected);
        // Documented invariant ck_account_balance_within_ledger still holds.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM banking.account
                WHERE available_balance > ledger_balance OR available_balance < 0
                """, Long.class)).isZero();
    }

    @Test
    void oppositeTransferDirectionsDoNotDeadlockBecauseRowsAreLockedInAscendingIdOrder() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (boolean forward : new boolean[] {true, false}) {
            futures.add(executor.submit(() -> {
                await(start);
                for (int iteration = 0; iteration < 25; iteration++) {
                    newTransaction().executeWithoutResult(ignored -> {
                        if (forward) {
                            lockService.lockTransferAccounts(source, destination);
                        } else {
                            lockService.lockTransferAccounts(destination, source);
                        }
                    });
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
        }
        assertThat(balanceOf(source)).isEqualByComparingTo(START);
        assertThat(balanceOf(destination)).isEqualByComparingTo(START);
    }

    @Test
    void lockWaitIsBoundedByAnExplicitCallerTimeoutAndReportsTechnicalFailure() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = executor.submit(() -> newTransaction().executeWithoutResult(ignored -> {
            lockService.lockTransferAccounts(source, destination);
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        try {
            long start = System.nanoTime();
            assertLockNotAvailable(catchThrowable(() -> newTransaction().executeWithoutResult(ignored -> {
                jdbc.execute("SET LOCAL lock_timeout = '250ms'");
                lockService.lockTransferAccounts(source, destination);
            })));
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(elapsedMillis).isGreaterThanOrEqualTo(200).isLessThan(1_500);
        } finally {
            release.countDown();
        }
        holder.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(balanceOf(source)).isEqualByComparingTo(START);
    }

    @Test
    void defaultLockWaitIsBoundedWhenTheCallerDidNotConfigureOne() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = executor.submit(() -> newTransaction().executeWithoutResult(ignored -> {
            lockService.lockTransferAccounts(source, destination);
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        try {
            long start = System.nanoTime();
            assertLockNotAvailable(catchThrowable(() -> inTransaction(
                    () -> lockService.lockTransferAccounts(source, destination))));
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            // Documented T2 default is 2s; assert a bounded wait, not an unbounded/instant result.
            assertThat(elapsedMillis).isGreaterThanOrEqualTo(1_500).isLessThan(5_000);
        } finally {
            release.countDown();
        }
        holder.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    void rollbackReleasesTheLockAndDiscardsTheProtectedMutation() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            lockService.lockTransferAccounts(source, destination);
            jdbc.update("UPDATE banking.account "
                    + "SET available_balance = available_balance - 100, "
                    + "ledger_balance = ledger_balance - 100, updated_at = now() "
                    + "WHERE account_id = ?", source);
            throw new IllegalStateException("simulated failure after debit");
        })).isInstanceOf(IllegalStateException.class);

        assertRowNotLocked(source);
        assertRowNotLocked(destination);
        assertThat(balanceOf(source)).isEqualByComparingTo(START);
        assertThat(jdbc.queryForObject(
                "SELECT ledger_balance FROM banking.account WHERE account_id = ?",
                BigDecimal.class, source)).isEqualByComparingTo(START);
    }

    // ------------------------------------------------------------------ helpers

    private TransactionTemplate newTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return newTransaction().execute(status -> work.get());
    }

    /** {@code 55P03} là technical failure; orchestrator map 503/retry, không phải business result. */
    private static void assertLockNotAvailable(Throwable failure) {
        assertThat(failure).isInstanceOf(DataAccessException.class);
        assertThat(sqlState(failure)).contains("55P03");
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private void assertRowNotLocked(long accountId) {
        inTransaction(() -> jdbc.queryForObject(
                "SELECT account_id FROM banking.account WHERE account_id = ? FOR UPDATE NOWAIT",
                Long.class, accountId));
    }

    private BigDecimal balanceOf(long accountId) {
        return jdbc.queryForObject(
                "SELECT available_balance FROM banking.account WHERE account_id = ?",
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

    private long seedAccount(long customerId, String accountNumber) {
        AccountNumberProtection.ProtectedAccountNumber protectedAccount = protectAccount(accountNumber);
        return jdbc.queryForObject("""
                INSERT INTO banking.account
                    (customer_id, account_no_enc, account_no_hash, account_no_last4,
                     status, available_balance, ledger_balance)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                RETURNING account_id
                """,
                Long.class,
                customerId,
                protectedAccount.encrypted(),
                protectedAccount.blindIndex(),
                protectedAccount.accountNumberLast4(),
                START,
                START);
    }

    @Autowired
    private AccountNumberProtection protection;

    private AccountNumberProtection.ProtectedAccountNumber protectAccount(String accountNumber) {
        return protection.protect(accountNumber);
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during read-modify-write window");
        }
    }
}
