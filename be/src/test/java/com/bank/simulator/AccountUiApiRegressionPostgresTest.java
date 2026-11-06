package com.bank.simulator;

import com.bank.simulator.account.application.AccountOperatorService;
import com.bank.simulator.account.application.AccountQueryService;
import com.bank.simulator.account.application.AccountStatusService;
import com.bank.simulator.account.application.SeedBalanceCommand;
import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.shared.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class AccountUiApiRegressionPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            DisposablePostgresTestSupport.newDisposablePostgres("account_ui_api_regression_test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        DisposablePostgresTestSupport.registerDatasource(registry, postgres);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AccountOperatorService operatorService;

    @Autowired
    private AccountQueryService queryService;

    @Autowired
    private AccountStatusService statusService;

    @Autowired
    private AccountJdbcRepository accountRepository;

    @Autowired
    private IdentityJdbcRepository identityRepository;

    @Autowired
    private AccountNumberGenerator accountNumberGenerator;

    private final Instant now = Instant.parse("2026-10-22T10:00:00Z");

    @BeforeEach
    void resetTables() {
        DisposablePostgresTestSupport.clearBusinessTables(jdbcTemplate, postgres);
    }

    // Helper to create user + customer + default account in Postgres
    private TestAccountFixture createCustomerWithAccount(String phone, String email, String fullName, String initialBalance) {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);
        identityRepository.createUser(userId, phone, normalizedEmail, "dummy-hash-1234567890", "CUSTOMER", now);
        identityRepository.createCustomer(customerId, userId, fullName, "123 Demo St", now);
        IdentityJdbcRepository.AccountRecord account = identityRepository.createDefaultAccount(customerId, accountNumberGenerator, now);
        if (initialBalance != null && !initialBalance.equals("0")) {
            jdbcTemplate.update("UPDATE accounts SET balance = CAST(? AS NUMERIC) WHERE id = ?", initialBalance, account.accountId());
        }
        return new TestAccountFixture(userId, customerId, account.accountId(), account.accountNumber());
    }

    private AuthenticatedActor createOperatorActor() {
        UUID operatorUserId = UUID.randomUUID();
        identityRepository.createUser(operatorUserId, "0999999999", "operator@example.test", "dummy-operator-hash-123456", "OPERATOR", now);
        return new AuthenticatedActor(operatorUserId, Set.of("OPERATOR"));
    }

    private record TestAccountFixture(UUID userId, UUID customerId, UUID accountId, String accountNumber) {}

    // ==========================================================================================
    // 1. DUPLICATE DEPOSIT PREVENTION & PESSIMISTIC LOCKING
    // ==========================================================================================

    @Test
    @DisplayName("Concurrent deposits to same account with distinct idempotency keys serialize via pessimistic lock without lost updates")
    void concurrentDepositsSerializeViaPessimisticLock() throws Exception {
        TestAccountFixture fixture = createCustomerWithAccount("0912345671", "user1@example.test", "Customer One", "0");
        AuthenticatedActor operator = createOperatorActor();

        int threadCount = 5;
        String amountPerThread = "1000000";
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AccountOperatorService.SeedBalanceResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String idempotencyKey = "key-concurrent-deposit-" + i + "-1234567890";
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return operatorService.seed(operator, fixture.accountId(),
                        new SeedBalanceCommand(amountPerThread, "VND", "Concurrent seed"),
                        idempotencyKey);
            }));
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        for (Future<AccountOperatorService.SeedBalanceResult> future : futures) {
            AccountOperatorService.SeedBalanceResult result = future.get(10, TimeUnit.SECONDS);
            assertThat(result.replayed()).isFalse();
            assertThat(result.amount()).isEqualTo(amountPerThread);
        }
        executor.shutdown();

        // Verify database ground truth
        String finalBalance = jdbcTemplate.queryForObject("SELECT balance::text FROM accounts WHERE id = ?", String.class, fixture.accountId());
        assertThat(finalBalance).isEqualTo("5000000");

        Integer ledgerCount = jdbcTemplate.queryForObject("SELECT count(*) FROM account_seed_records WHERE account_id = ?", Integer.class, fixture.accountId());
        assertThat(ledgerCount).isEqualTo(5);
    }

    @Test
    @DisplayName("Concurrent requests with the exact same idempotency key result in only ONE ledger record and single balance credit")
    void concurrentSameIdempotencyKeyAppliesCreditOnlyOnce() throws Exception {
        TestAccountFixture fixture = createCustomerWithAccount("0912345672", "user2@example.test", "Customer Two", "0");
        AuthenticatedActor operator = createOperatorActor();

        int threadCount = 4;
        String idempotencyKey = "same-key-replay-protection-1234567890";
        String amount = "2000000";
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return operatorService.seed(operator, fixture.accountId(),
                            new SeedBalanceCommand(amount, "VND", "Same key seed"),
                            idempotencyKey);
                } catch (ApiException e) {
                    return e;
                }
            }));
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        int successCount = 0;
        int inProgressOrReplayedCount = 0;
        for (Future<Object> future : futures) {
            Object res = future.get(10, TimeUnit.SECONDS);
            if (res instanceof AccountOperatorService.SeedBalanceResult sbr) {
                if (!sbr.replayed()) {
                    successCount++;
                } else {
                    inProgressOrReplayedCount++;
                }
            } else if (res instanceof ApiException apiEx) {
                assertThat(apiEx.code()).isIn("IDEMPOTENCY_IN_PROGRESS", "IDEMPOTENCY_KEY_REUSED");
                inProgressOrReplayedCount++;
            }
        }
        executor.shutdown();

        // Exactly ONE thread should have performed the original credit
        assertThat(successCount).isEqualTo(1);

        // Ground truth: Balance must be EXACTLY 2,000,000, NEVER multiplied
        String finalBalance = jdbcTemplate.queryForObject("SELECT balance::text FROM accounts WHERE id = ?", String.class, fixture.accountId());
        assertThat(finalBalance).isEqualTo("2000000");

        Integer ledgerCount = jdbcTemplate.queryForObject("SELECT count(*) FROM account_seed_records WHERE account_id = ?", Integer.class, fixture.accountId());
        assertThat(ledgerCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Replaying same idempotency key with different payload is rejected with 409 IDEMPOTENCY_KEY_REUSED")
    void idempotencyKeyWithDifferentPayloadIsRejected() {
        TestAccountFixture fixture = createCustomerWithAccount("0912345673", "user3@example.test", "Customer Three", "0");
        AuthenticatedActor operator = createOperatorActor();

        String idempotencyKey = "key-mismatch-test-1234567890";
        AccountOperatorService.SeedBalanceResult first = operatorService.seed(operator, fixture.accountId(),
                new SeedBalanceCommand("1000000", "VND", "Initial deposit"),
                idempotencyKey);
        assertThat(first.replayed()).isFalse();

        // Attempt reuse with different amount
        assertThatThrownBy(() -> operatorService.seed(operator, fixture.accountId(),
                new SeedBalanceCommand("2000000", "VND", "Initial deposit"),
                idempotencyKey))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> {
                    ApiException ex = (ApiException) err;
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });

        // Attempt reuse with different reference
        assertThatThrownBy(() -> operatorService.seed(operator, fixture.accountId(),
                new SeedBalanceCommand("1000000", "VND", "Tampered reference"),
                idempotencyKey))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> {
                    ApiException ex = (ApiException) err;
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });

        // Balance remains 1,000,000
        String balance = jdbcTemplate.queryForObject("SELECT balance::text FROM accounts WHERE id = ?", String.class, fixture.accountId());
        assertThat(balance).isEqualTo("1000000");
    }

    @Test
    @DisplayName("Sequential replay with identical payload returns cached response with replayed=true without adding funds")
    void sequentialReplayReturnsCachedResultWithoutCrediting() {
        TestAccountFixture fixture = createCustomerWithAccount("0912345674", "user4@example.test", "Customer Four", "500000");
        AuthenticatedActor operator = createOperatorActor();

        String idempotencyKey = "sequential-replay-test-1234567890";
        SeedBalanceCommand cmd = new SeedBalanceCommand("1500000", "VND", "Payroll seed");

        AccountOperatorService.SeedBalanceResult first = operatorService.seed(operator, fixture.accountId(), cmd, idempotencyKey);
        assertThat(first.replayed()).isFalse();
        assertThat(first.balanceAfter()).isEqualTo("2000000");

        // Second call
        AccountOperatorService.SeedBalanceResult replay = operatorService.seed(operator, fixture.accountId(), cmd, idempotencyKey);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.seedTransactionId()).isEqualTo(first.seedTransactionId());
        assertThat(replay.balanceAfter()).isEqualTo("2000000");

        // Verify ground truth
        String balance = jdbcTemplate.queryForObject("SELECT balance::text FROM accounts WHERE id = ?", String.class, fixture.accountId());
        assertThat(balance).isEqualTo("2000000");

        Integer ledgerCount = jdbcTemplate.queryForObject("SELECT count(*) FROM account_seed_records WHERE account_id = ?", Integer.class, fixture.accountId());
        assertThat(ledgerCount).isEqualTo(1);
    }

    // ==========================================================================================
    // 2. IDOR & OBJECT-LEVEL AUTHORIZATION PREVENTION
    // ==========================================================================================

    @Test
    @DisplayName("Customer A listing accounts sees ONLY their own accounts in opened_at DESC, id DESC order")
    void customerListingAccountsNeverLeaksOtherCustomerAccounts() {
        TestAccountFixture customerA = createCustomerWithAccount("0912345681", "userA@example.test", "Customer A", "100000");
        TestAccountFixture customerB = createCustomerWithAccount("0912345682", "userB@example.test", "Customer B", "200000");

        // Also create a non-default second account for customer A (is_default = false)
        UUID accountA2Id = UUID.randomUUID();
        String numA2 = accountNumberGenerator.generate();
        jdbcTemplate.update("INSERT INTO accounts (id, account_number, customer_id, account_type, balance, currency, status, is_default, opened_at, updated_at) VALUES (?, ?, ?, 'CHECKING', 0, 'VND', 'ACTIVE', FALSE, ?, ?)",
                accountA2Id, numA2, customerA.customerId(), java.sql.Timestamp.from(now.plusSeconds(10)), java.sql.Timestamp.from(now.plusSeconds(10)));

        AuthenticatedActor actorA = new AuthenticatedActor(customerA.userId(), Set.of("CUSTOMER"));
        List<AccountJdbcRepository.AccountRow> listA = queryService.myAccounts(actorA);

        assertThat(listA).hasSize(2);
        assertThat(listA).extracting(AccountJdbcRepository.AccountRow::customerId)
                .containsOnly(customerA.customerId())
                .doesNotContain(customerB.customerId());

        // Ordering verification: opened_at DESC
        assertThat(listA.get(0).id()).isEqualTo(accountA2Id);
        assertThat(listA.get(1).id()).isEqualTo(customerA.accountId());
    }

    @Test
    @DisplayName("Customer A directly accessing Customer B accountId is blocked with 403 FORBIDDEN")
    void customerAccessingOtherCustomerAccountThrowsForbidden() {
        TestAccountFixture customerA = createCustomerWithAccount("0912345683", "userA3@example.test", "Customer A", "100000");
        TestAccountFixture customerB = createCustomerWithAccount("0912345684", "userB3@example.test", "Customer B", "200000");

        AuthenticatedActor actorA = new AuthenticatedActor(customerA.userId(), Set.of("CUSTOMER"));

        // Customer A accessing Customer B's account
        assertThatThrownBy(() -> queryService.get(actorA, customerB.accountId()))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> {
                    ApiException ex = (ApiException) err;
                    assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.code()).isEqualTo("FORBIDDEN");
                });

        // Customer A accessing non-existent account -> 404
        assertThatThrownBy(() -> queryService.get(actorA, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> {
                    ApiException ex = (ApiException) err;
                    assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.code()).isEqualTo("ACCOUNT_NOT_FOUND");
                });
    }

    @Test
    @DisplayName("Customer cannot execute Operator operations (seed, block, unblock, lookup) -> 403 FORBIDDEN")
    void customerRoleCannotExecuteOperatorEndpoints() {
        TestAccountFixture customerA = createCustomerWithAccount("0912345685", "userA4@example.test", "Customer A", "100000");
        AuthenticatedActor customerActor = new AuthenticatedActor(customerA.userId(), Set.of("CUSTOMER"));
        AuthenticatedActor auditorActor = new AuthenticatedActor(UUID.randomUUID(), Set.of("AUDITOR"));

        // Customer attempting to seed balance
        assertThatThrownBy(() -> operatorService.seed(customerActor, customerA.accountId(),
                new SeedBalanceCommand("500000", "VND", "Tamper seed"), "key-tamper-customer-12345"))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> assertThat(((ApiException) err).status()).isEqualTo(HttpStatus.FORBIDDEN));

        // Auditor attempting to seed balance
        assertThatThrownBy(() -> operatorService.seed(auditorActor, customerA.accountId(),
                new SeedBalanceCommand("500000", "VND", "Tamper seed"), "key-tamper-auditor-123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> assertThat(((ApiException) err).status()).isEqualTo(HttpStatus.FORBIDDEN));

        // Customer attempting to block account
        assertThatThrownBy(() -> statusService.change(customerActor, customerA.accountId(), "BLOCKED", "Tamper block"))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> assertThat(((ApiException) err).status()).isEqualTo(HttpStatus.FORBIDDEN));

        // Customer attempting to lookup customer
        assertThatThrownBy(() -> queryService.lookup(customerActor, "0912345685", null))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> assertThat(((ApiException) err).status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ==========================================================================================
    // 4. ACCOUNT LOCK/UNLOCK & OPERATOR AUTHORIZATION
    // ==========================================================================================

    @Test
    @DisplayName("Operator transitions account ACTIVE -> BLOCKED -> ACTIVE with audit event recording")
    void operatorCanBlockAndUnblockAccount() {
        TestAccountFixture fixture = createCustomerWithAccount("0912345691", "user_status@example.test", "Customer Status", "1000000");
        AuthenticatedActor operator = createOperatorActor();

        // 1. ACTIVE -> BLOCKED
        var blocked = statusService.change(operator, fixture.accountId(), "BLOCKED", "Suspicious activity detected");
        assertThat(blocked.status()).isEqualTo("BLOCKED");

        String statusInDb = jdbcTemplate.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, fixture.accountId());
        assertThat(statusInDb).isEqualTo("BLOCKED");

        // Verify audit event
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_events WHERE target_id = ? AND event_type = 'ACCOUNT_STATUS_CHANGED'",
                Integer.class, fixture.accountId());
        assertThat(auditCount).isEqualTo(1);

        // 2. BLOCKED -> ACTIVE
        var activated = statusService.change(operator, fixture.accountId(), "ACTIVE", "Verification passed");
        assertThat(activated.status()).isEqualTo("ACTIVE");

        statusInDb = jdbcTemplate.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, fixture.accountId());
        assertThat(statusInDb).isEqualTo("ACTIVE");

        auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_events WHERE target_id = ? AND event_type = 'ACCOUNT_STATUS_CHANGED'",
                Integer.class, fixture.accountId());
        assertThat(auditCount).isEqualTo(2);
    }

    @Test
    @DisplayName("BLOCKED account cannot receive seed balance deposit -> 409 ACCOUNT_NOT_ELIGIBLE")
    void blockedAccountRejectsDeposit() {
        TestAccountFixture fixture = createCustomerWithAccount("0912345692", "user_blocked@example.test", "Customer Blocked", "500000");
        AuthenticatedActor operator = createOperatorActor();

        statusService.change(operator, fixture.accountId(), "BLOCKED", "Temporary freeze");

        // Attempt seed balance on BLOCKED account
        assertThatThrownBy(() -> operatorService.seed(operator, fixture.accountId(),
                new SeedBalanceCommand("1000000", "VND", "Deposit attempt"), "key-blocked-deposit-123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> {
                    ApiException ex = (ApiException) err;
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo("ACCOUNT_NOT_ELIGIBLE");
                });

        // Balance unchanged
        String balance = jdbcTemplate.queryForObject("SELECT balance::text FROM accounts WHERE id = ?", String.class, fixture.accountId());
        assertThat(balance).isEqualTo("500000");
    }

    @Test
    @DisplayName("CLOSED account cannot change status (cannot reopen) -> 409 ACCOUNT_NOT_ELIGIBLE")
    void closedAccountCannotChangeStatus() {
        TestAccountFixture fixture = createCustomerWithAccount("0912345693", "user_closed@example.test", "Customer Closed", "0");
        AuthenticatedActor operator = createOperatorActor();

        // Directly mark account as CLOSED in database to test the rule
        jdbcTemplate.update("UPDATE accounts SET status = 'CLOSED' WHERE id = ?", fixture.accountId());

        // Attempt CLOSED -> ACTIVE
        assertThatThrownBy(() -> statusService.change(operator, fixture.accountId(), "ACTIVE", "Attempt reopen"))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> {
                    ApiException ex = (ApiException) err;
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo("ACCOUNT_NOT_ELIGIBLE");
                });

        // Attempt CLOSED -> BLOCKED
        assertThatThrownBy(() -> statusService.change(operator, fixture.accountId(), "BLOCKED", "Attempt block closed"))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> {
                    ApiException ex = (ApiException) err;
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo("ACCOUNT_NOT_ELIGIBLE");
                });

        // Status remains CLOSED
        String status = jdbcTemplate.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, fixture.accountId());
        assertThat(status).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("Account status change validates reason parameter (not blank, <= 500 chars)")
    void statusChangeValidatesReason() {
        TestAccountFixture fixture = createCustomerWithAccount("0912345694", "user_reason@example.test", "Customer Reason", "0");
        AuthenticatedActor operator = createOperatorActor();

        // Blank reason
        assertThatThrownBy(() -> statusService.change(operator, fixture.accountId(), "BLOCKED", "   "))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> assertThat(((ApiException) err).status()).isEqualTo(HttpStatus.BAD_REQUEST));

        // Null reason
        assertThatThrownBy(() -> statusService.change(operator, fixture.accountId(), "BLOCKED", null))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> assertThat(((ApiException) err).status()).isEqualTo(HttpStatus.BAD_REQUEST));

        // Over 500 chars
        String longReason = "a".repeat(501);
        assertThatThrownBy(() -> statusService.change(operator, fixture.accountId(), "BLOCKED", longReason))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> assertThat(((ApiException) err).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
