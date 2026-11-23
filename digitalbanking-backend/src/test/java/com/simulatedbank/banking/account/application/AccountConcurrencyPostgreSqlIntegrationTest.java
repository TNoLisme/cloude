package com.simulatedbank.banking.account.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.account.api.AccountBalance;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-ACC-11 — PostgreSQL 16 concurrency, isolation và duplicate-request evidence cho Account.
 *
 * <p>Bổ sung cho primitive evidence của BE-ACC-06/07 (lock, debit/credit, rollback): các kịch bản
 * còn thiếu theo checklist BE-ACC-11 §2.2 — concurrent state transition, READ COMMITTED
 * visibility, nhiều T2 cạnh tranh vượt số dư, duplicate request và unique constraint trên blind
 * index. Không thay PostgreSQL bằng H2/mock, không thêm business rule mới: mọi assertion bám
 * {@code design/02} §7–§8 và DEC-ACC-006/007.</p>
 *
 * <p>Ghi chú phạm vi: Account chỉ có money primitives; ledger entry/transfer state thuộc
 * BE-ACC-08 nên T-CON-01/02 đầy đủ (đếm ledger entry) vẫn BLOCKED. Các test dưới đây chứng minh
 * phần Account của T-CON-02/03: không overdraw, không lost update, không credit phía bị từ chối,
 * tổng tiền hai account bất biến.</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AccountConcurrencyPostgreSqlIntegrationTest {

    private static final BigDecimal START = new BigDecimal("1000.00");
    private static final Duration AWAIT = Duration.ofSeconds(30);

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("account_concurrency");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        // Independent connections per competing transaction; default test pool is 3.
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
            customerA = seedCustomer(seedUser("acc-concurrency-a"));
            customerB = seedCustomer(seedUser("acc-concurrency-b"));
            source = seedAccount(customerA, "0011111111", START, "ACTIVE");
            destination = seedAccount(customerB, "0022222222", START, "ACTIVE");
            unrelated = seedAccount(customerA, "0033333333", START, "ACTIVE");
        });
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ state transitions

    /**
     * Concurrent state transition vs money path: transaction khác đổi {@code status} giữ row lock,
     * debit phải chờ; sau khi status commit thành {@code LOCKED}, debit trả {@code INACTIVE} và
     * không di chuyển tiền. Đây là hệ quả READ COMMITTED + conditional UPDATE của design/02 §8.2,
     * không phải rule mới.
     */
    @Test
    void concurrentStatusChangeAndDebitSerializeAndNeverDebitANonActiveAccount() throws Exception {
        CountDownLatch statusChanged = new CountDownLatch(1);
        CountDownLatch releaseStatus = new CountDownLatch(1);
        CountDownLatch debitAttempted = new CountDownLatch(1);
        CountDownLatch debitCompleted = new CountDownLatch(1);

        Future<?> statusTransaction = executor.submit(() -> newTransaction().executeWithoutResult(ignored -> {
            jdbc.update("UPDATE banking.account SET status = 'LOCKED', updated_at = now() "
                    + "WHERE account_id = ?", source);
            statusChanged.countDown();
            await(releaseStatus);
        }));
        assertThat(statusChanged.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        Future<AccountBalance.DebitOutcome> debit = executor.submit(() -> {
            debitAttempted.countDown();
            try {
                return newTransaction().execute(ignored -> {
                    balanceService.lockTransferAccounts(source, destination);
                    return balanceService.debitConditional(source, new BigDecimal("100.00")).outcome();
                });
            } finally {
                debitCompleted.countDown();
            }
        });
        assertThat(debitAttempted.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        // Row is still locked by the uncommitted status change: the debit must not proceed.
        assertThat(debitCompleted.await(700, TimeUnit.MILLISECONDS)).isFalse();
        releaseStatus.countDown();
        statusTransaction.get(AWAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(debit.get(AWAIT.toSeconds(), TimeUnit.SECONDS))
                .isEqualTo(AccountBalance.DebitOutcome.INACTIVE);
        assertThat(availableOf(source)).isEqualByComparingTo(START);
        assertThat(ledgerOf(source)).isEqualByComparingTo(START);
        assertThat(statusOf(source)).isEqualTo("LOCKED");
        assertThat(availableOf(destination)).isEqualByComparingTo(START);
        assertNoBrokenInvariant();
    }

    @Test
    void debitIsInvisibleToOtherTransactionsUntilCommit() throws Exception {
        CountDownLatch debitApplied = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);

        Future<?> writer = executor.submit(() -> newTransaction().executeWithoutResult(ignored -> {
            balanceService.lockTransferAccounts(source, destination);
            assertThat(balanceService.debitConditional(source, new BigDecimal("100.00")).outcome())
                    .isEqualTo(AccountBalance.DebitOutcome.APPLIED);
            debitApplied.countDown();
            await(releaseWriter);
        }));
        assertThat(debitApplied.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        // READ COMMITTED: another transaction sees the last committed row, never the dirty write.
        assertThat(availableInNewTransaction(source)).isEqualByComparingTo(START);
        assertThat(ledgerInNewTransaction(source)).isEqualByComparingTo(START);

        releaseWriter.countDown();
        writer.get(AWAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(availableInNewTransaction(source)).isEqualByComparingTo("900.00");
        assertThat(ledgerInNewTransaction(source)).isEqualByComparingTo("900.00");
        assertNoBrokenInvariant();
    }

    // ------------------------------------------------------------------ T-CON-02 Account scope

    /**
     * Nhiều T2 rút tiền vượt số dư: chỉ đúng số lượt đủ tiền được áp dụng; lượt bị từ chối không
     * credit đích (atomicity same-transaction) và tổng tiền hai account không đổi.
     */
    @Test
    void concurrentTransfersThatExceedTheBalanceApplyOnlyTheAffordableOnes() throws Exception {
        int workers = 10;
        BigDecimal perTransfer = new BigDecimal("300.00");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AccountBalance.DebitOutcome>> futures = new ArrayList<>();
        for (int index = 0; index < workers; index++) {
            futures.add(executor.submit(() -> {
                await(start);
                return newTransaction().execute(ignored -> {
                    balanceService.lockTransferAccounts(source, destination);
                    AccountBalance.DebitResult debit =
                            balanceService.debitConditional(source, perTransfer);
                    if (debit.outcome() == AccountBalance.DebitOutcome.APPLIED) {
                        balanceService.credit(destination, perTransfer);
                    }
                    return debit.outcome();
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

        // 1000 / 300 = 3 affordable transfers regardless of interleaving.
        assertThat(applied).isEqualTo(3);
        assertThat(rejected).isEqualTo(workers - 3);
        assertThat(availableOf(source)).isEqualByComparingTo("100.00");
        assertThat(ledgerOf(source)).isEqualByComparingTo("100.00");
        assertThat(availableOf(destination)).isEqualByComparingTo("1900.00");
        assertThat(ledgerOf(destination)).isEqualByComparingTo("1900.00");
        assertThat(availableOf(source).add(availableOf(destination)))
                .isEqualByComparingTo(START.add(START));
        assertThat(availableOf(unrelated)).isEqualByComparingTo(START);
        assertNoBrokenInvariant();
    }

    @Test
    void sequentialDebitsInsideOneTransactionSeeEarlierUpdatesAndStayAtomic() {
        transaction.executeWithoutResult(ignored -> {
            balanceService.lockTransferAccounts(source, destination);
            assertThat(balanceService.debitConditional(source, new BigDecimal("100.00")).outcome())
                    .isEqualTo(AccountBalance.DebitOutcome.APPLIED);
            // READ COMMITTED snapshot per statement: the second UPDATE must see the first one.
            assertThat(balanceService.debitConditional(source, new BigDecimal("100.00")).outcome())
                    .isEqualTo(AccountBalance.DebitOutcome.APPLIED);
        });

        assertThat(availableOf(source)).isEqualByComparingTo("800.00");
        assertThat(ledgerOf(source)).isEqualByComparingTo("800.00");
        assertNoBrokenInvariant();
    }

    // ------------------------------------------------------------------ duplicate requests

    /**
     * Ranh giới idempotency: Account không sở hữu idempotency key/dedup (DEC-ACC-007 quyết định 7;
     * design/02 §6) — hai request trùng chạy song song đều được áp dụng. Test khóa hành vi đã
     * được duyệt để reviewer không nhầm Account bảo vệ double debit; dedup thuộc T1
     * idempotency + transfer state guard của BE-ACC-08.
     */
    @Test
    void duplicateConcurrentDebitRequestsAreBothAppliedBecauseAccountDoesNotOwnIdempotency() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AccountBalance.DebitOutcome>> futures = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            futures.add(executor.submit(() -> {
                await(start);
                return newTransaction().execute(ignored -> {
                    balanceService.lockTransferAccounts(source, destination);
                    return balanceService.debitConditional(source, new BigDecimal("100.00")).outcome();
                });
            }));
        }
        start.countDown();

        for (Future<AccountBalance.DebitOutcome> future : futures) {
            assertThat(future.get(AWAIT.toSeconds(), TimeUnit.SECONDS))
                    .isEqualTo(AccountBalance.DebitOutcome.APPLIED);
        }
        // 1000 - 2 x 100: the database stayed consistent, but the request was not deduplicated.
        assertThat(availableOf(source)).isEqualByComparingTo("800.00");
        assertThat(ledgerOf(source)).isEqualByComparingTo("800.00");
        assertNoBrokenInvariant();
    }

    /**
     * Unique constraint dưới cạnh tranh: hai transaction chèn cùng blind index chỉ một thắng;
     * loser nhận {@code 23505 uq_account_no_hash}; không có row một phần. Đây là hành vi schema
     * {@code V8} (BE-ACC-02), không phải write path mới (ACC-DR-04 vẫn BLOCKED).
     */
    @Test
    void duplicateBlindIndexInsertRacesHaveExactlyOneWinnerAndNoPartialRow() throws Exception {
        AccountNumberProtection.ProtectedAccountNumber protectedAccount =
                protection.protect("0055555555");
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        AtomicReference<Throwable> loser = new AtomicReference<>();

        Future<?> first = executor.submit(() -> newTransaction().executeWithoutResult(ignored -> {
            insertAccount(customerA, protectedAccount);
            firstInserted.countDown();
            await(releaseFirst);
        }));
        assertThat(firstInserted.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        Future<?> second = executor.submit(() -> {
            secondAttempted.countDown();
            try {
                newTransaction().executeWithoutResult(ignored ->
                        insertAccount(customerB, protectedAccount));
            } catch (Throwable failure) {
                loser.set(failure);
            }
        });
        assertThat(secondAttempted.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        releaseFirst.countDown();
        first.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
        second.get(AWAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(loser.get()).isInstanceOf(DataAccessException.class);
        assertThat(sqlState(loser.get())).isEqualTo("23505");
        assertThat(sqlState(loser.get())).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM banking.account WHERE account_no_hash = ?",
                Long.class, protectedAccount.blindIndex())).isEqualTo(1);
        // The loser's customer did not receive a partial row either.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM banking.account WHERE customer_id = ?",
                Long.class, customerB)).isEqualTo(1);
        assertNoBrokenInvariant();
    }

    // ------------------------------------------------------------------ helpers

    private void insertAccount(long customerId, AccountNumberProtection.ProtectedAccountNumber protectedAccount) {
        jdbc.update("""
                INSERT INTO banking.account
                    (customer_id, account_no_enc, account_no_hash, account_no_last4,
                     status, available_balance, ledger_balance)
                VALUES (?, ?, ?, ?, 'ACTIVE', 0, 0)
                """,
                customerId,
                protectedAccount.encrypted(),
                protectedAccount.blindIndex(),
                protectedAccount.accountNumberLast4());
    }

    private TransactionTemplate newTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private BigDecimal availableInNewTransaction(long accountId) {
        return newTransaction().execute(ignored -> availableOf(accountId));
    }

    private BigDecimal ledgerInNewTransaction(long accountId) {
        return newTransaction().execute(ignored -> ledgerOf(accountId));
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

    private String statusOf(long accountId) {
        return jdbc.queryForObject(
                "SELECT status FROM banking.account WHERE account_id = ?", String.class, accountId);
    }

    private void assertNoBrokenInvariant() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM banking.account
                WHERE available_balance < 0 OR ledger_balance < 0 OR available_balance > ledger_balance
                """, Long.class)).isZero();
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
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
                "CLOSED".equals(status) ? java.sql.Timestamp.from(Instant.now()) : null));
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
