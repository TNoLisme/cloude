package com.simulatedbank.banking.account.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.account.application.AccountNumberProtection;
import com.simulatedbank.banking.account.application.AccountNumberProtection.ProtectedAccountNumber;
import com.simulatedbank.banking.account.application.AccountReadPort;
import com.simulatedbank.banking.account.application.AccountReadPort.AccountRecord;
import com.simulatedbank.banking.account.domain.AccountStatus;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
 * BE-ACC-03 PostgreSQL integration: entity mapping, AES-GCM ciphertext, HMAC blind index,
 * repository access paths và constraint enforcement trên schema {@code V8} thật.
 *
 * <p>Spring context chạy {@code hibernate.ddl-auto=validate} trên Flyway schema nên test này cũng
 * chứng minh entity khớp migration; không dùng H2/mock persistence.</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AccountPersistencePostgreSqlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("account_write");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
    }

    @Autowired
    private AccountJpaRepository repository;

    @Autowired
    private AccountReadPort reader;

    @Autowired
    private AccountNumberProtection protection;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    private long customerA;
    private long customerB;

    @BeforeEach
    void clearAndSeedCustomers() {
        transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM banking.account");
            jdbc.update("DELETE FROM banking.customer");
            jdbc.update("DELETE FROM banking.app_user");
        });
        customerA = inTransaction(() -> seedCustomer(seedUser("acc-owner-a")));
        customerB = inTransaction(() -> seedCustomer(seedUser("acc-owner-b")));
    }

    @Test
    void persistsAesGcmCiphertextHmacBlindIndexAndApprovedDefaults() {
        String accountNumber = "0012345678";
        ProtectedAccountNumber protectedAccount = protection.protect(accountNumber);

        AccountEntity saved = repository.saveAndFlush(new AccountEntity(customerA, protectedAccount));

        assertThat(saved.getAccountId()).isPositive();
        assertThat(saved.toString()).isEqualTo("AccountEntity[redacted]");
        AccountRecord reloaded = reader.findById(saved.getAccountId()).orElseThrow();
        assertThat(reloaded.customerId()).isEqualTo(customerA);
        assertThat(reloaded.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(reloaded.currency()).isEqualTo("VND");
        assertThat(reloaded.availableBalance()).isEqualByComparingTo("0.00");
        assertThat(reloaded.ledgerBalance()).isEqualByComparingTo("0.00");
        assertThat(reloaded.accountNumberLast4()).isEqualTo("5678");
        assertThat(reloaded.createdAt()).isNotNull();
        assertThat(reloaded.updatedAt()).isNotNull();

        byte[] stored = jdbc.queryForObject(
                "SELECT account_no_enc FROM banking.account WHERE account_id = ?",
                byte[].class, saved.getAccountId());
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(accountNumber);
        assertThat(protection.decrypt(stored)).isEqualTo(accountNumber);
        assertThat(jdbc.queryForObject(
                "SELECT account_no_hash FROM banking.account WHERE account_id = ?",
                String.class, saved.getAccountId())).isEqualTo(protectedAccount.blindIndex());
        assertThat(jdbc.queryForObject(
                "SELECT account_no_last4 FROM banking.account WHERE account_id = ?",
                String.class, saved.getAccountId())).isEqualTo("5678");
    }

    @Test
    void readsByBlindIndexCustomerAndStatusUsingTheApprovedIndexes() {
        long first = insertAccount(customerA, "0012345678", AccountStatus.ACTIVE, "100.00", "100.00", null);
        long locked = insertAccount(customerA, "0011111111", AccountStatus.LOCKED, "0.00", "0.00", null);
        long foreign = insertAccount(customerB, "0022222222", AccountStatus.ACTIVE, "0.00", "0.00", null);

        assertThat(reader.findByAccountNumberHash(protection.blindIndex("0012345678")))
                .get().extracting(AccountRecord::accountId).isEqualTo(first);
        assertThat(reader.findByAccountNumberHash(protection.blindIndex("0099999999"))).isEmpty();

        assertThat(reader.findByCustomerId(customerA, null))
                .extracting(AccountRecord::accountId).containsExactly(first, locked);
        assertThat(reader.findByCustomerId(customerA, AccountStatus.LOCKED))
                .extracting(AccountRecord::accountId).containsExactly(locked);
        assertThat(reader.findByCustomerId(customerB, AccountStatus.CLOSED)).isEmpty();
        assertThat(reader.findByCustomerId(customerA, null))
                .noneMatch(record -> record.accountId() == foreign);
    }

    @Test
    void rejectsDuplicateBlindIndexAndMissingCustomerForeignKey() {
        insertAccount(customerA, "0012345678", AccountStatus.ACTIVE, "0.00", "0.00", null);
        ProtectedAccountNumber duplicateNumber = protection.protect("0012345678");

        assertThatThrownBy(() -> repository.saveAndFlush(new AccountEntity(customerB, duplicateNumber)))
                .isInstanceOf(DataIntegrityViolationException.class);

        ProtectedAccountNumber anotherNumber = protection.protect("0055555555");
        assertThatThrownBy(() -> repository.saveAndFlush(new AccountEntity(999_999, anotherNumber)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseChecksRejectForgedBalancesAndIncompleteClosedLifecycle() {
        ProtectedAccountNumber protectedAccount = protection.protect("0077777777");

        assertThatThrownBy(() -> insertAccountRaw(
                customerA, protectedAccount, "CLOSED", "0.00", "0.00", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAccountRaw(
                customerA, protectedAccount, "ACTIVE", "100.00", "50.00", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAccountRaw(
                customerA, protectedAccount, "ACTIVE", "-1.00", "0.00", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void closedAccountWithClosedAtIsPersistedAndReadAsClosed() {
        long closed = insertAccount(
                customerA, "0044444444", AccountStatus.CLOSED, "0.00", "0.00",
                Instant.parse("2026-11-01T00:00:00Z"));

        assertThat(reader.findById(closed)).get()
                .extracting(AccountRecord::status).isEqualTo(AccountStatus.CLOSED);
    }

    private long insertAccount(long customerId, String accountNumber, AccountStatus status,
            String available, String ledger, Instant closedAt) {
        ProtectedAccountNumber protectedAccount = protection.protect(accountNumber);
        return inTransaction(() ->
                insertAccountRaw(customerId, protectedAccount, status.name(), available, ledger, closedAt));
    }

    private long insertAccountRaw(long customerId, ProtectedAccountNumber protectedAccount,
            String status, String available, String ledger, Instant closedAt) {
        return jdbc.queryForObject("""
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
                new BigDecimal(available),
                new BigDecimal(ledger),
                closedAt == null ? null : java.time.OffsetDateTime.ofInstant(closedAt, java.time.ZoneOffset.UTC));
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

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }
}
