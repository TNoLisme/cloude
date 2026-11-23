package com.simulatedbank.banking.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.account.api.AccountQuery.AccountSnapshot;
import com.simulatedbank.banking.account.api.AccountQuery.AccountView;
import com.simulatedbank.banking.account.api.AccountQuery.Status;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * BE-ACC-04 PostgreSQL integration: read service + ownership trên schema/AES/HMAC thật và
 * CustomerQuery thật (không mock module Customer).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AccountReadPostgreSqlIntegrationTest {

    private static final String CORRELATION_ID = "corr-acc-read-1";
    private static final String ACCOUNT_A = "0012345678";
    private static final String ACCOUNT_A_LOCKED = "0011111111";
    private static final String ACCOUNT_B = "0098765432";

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("account_read");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
    }

    @Autowired
    private AccountQueryService queryService;

    @Autowired
    private AccountAccessService accessService;

    @Autowired
    private AccountNumberProtection protection;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private long userA;
    private long userB;
    private long customerA;
    private long customerB;
    private long accountA;
    private long accountALocked;
    private long accountB;

    @BeforeEach
    void clearAndSeed() {
        transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM banking.account");
            jdbc.update("DELETE FROM banking.customer");
            // Keep uniquely named users because durable C3 rows reference them.
        });
        inTransaction(() -> {
            userA = seedUser("account-owner-a");
            userB = seedUser("account-owner-b");
            customerA = seedCustomer(userA, "ACTIVE");
            customerB = seedCustomer(userB, "ACTIVE");
            accountA = seedAccount(customerA, ACCOUNT_A, "ACTIVE", "12500000.00", null);
            accountALocked = seedAccount(customerA, ACCOUNT_A_LOCKED, "LOCKED", "0.00", null);
            accountB = seedAccount(customerB, ACCOUNT_B, "ACTIVE", "500000.00", null);
            return null;
        });
    }

    @Test
    void selfListReturnsOnlyThePrincipalAccountsInStableOrderWithMaskedNumbers() {
        List<AccountView> views = accessService.listOwnAccounts(userA, null, CORRELATION_ID).items();

        assertThat(views).extracting(AccountView::accountId)
                .containsExactly(accountA, accountALocked);
        assertThat(views).extracting(AccountView::accountNumberMasked)
                .containsExactly("**** **** 5678", "**** **** 1111");
        assertThat(views).allSatisfy(view -> assertThat(view.toString())
                .doesNotContain(ACCOUNT_A)
                .doesNotContain(protection.blindIndex(ACCOUNT_A)));
    }

    @Test
    void selfListFiltersByStatusWithoutLeakingOtherCustomers() {
        List<AccountView> locked = accessService.listOwnAccounts(userA, Status.LOCKED, CORRELATION_ID).items();

        assertThat(locked).extracting(AccountView::accountId).containsExactly(accountALocked);
        assertThat(accessService.listOwnAccounts(userA, Status.CLOSED, CORRELATION_ID).items()).isEmpty();
    }

    @Test
    void selfDetailReturnsMaskedOwnerViewFromTheRealCustomerMapping() {
        AccountView view = accessService.getOwnAccount(userA, accountA, CORRELATION_ID).account();

        assertThat(view.customerId()).isEqualTo(customerA);
        assertThat(view.accountNumberMasked()).isEqualTo("**** **** 5678");
        assertThat(view.accountNumberLast4()).isEqualTo("5678");
        assertThat(view.availableBalance()).isEqualByComparingTo("12500000.00");
        assertThat(view.status()).isEqualTo(Status.ACTIVE);
        assertThat(view.asOf()).isNotNull();
        assertThat(view.toString()).doesNotContain(ACCOUNT_A, protection.blindIndex(ACCOUNT_A));
    }

    @Test
    void crossCustomerDetailIsForbiddenAuditedAndDoesNotTouchForeignData() {
        double before = auditDeniedCount();

        assertThatThrownBy(() -> accessService.getOwnAccount(userB, accountA, CORRELATION_ID))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN))
                .hasMessageNotContaining(ACCOUNT_A)
                .hasMessageNotContaining("5678");

        assertThat(auditDeniedCount()).isEqualTo(before + 1);
    }

    @Test
    void missingAccountIsNotFoundAndNotAudited() {
        double before = auditDeniedCount();

        assertThatThrownBy(() -> accessService.getOwnAccount(userA, 999_999, CORRELATION_ID))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));

        assertThat(auditDeniedCount()).isEqualTo(before);
    }

    @Test
    void principalWithoutCustomerMappingIsCustomerNotFound() {
        long unmappedUser = inTransaction(() -> seedUser("account-no-customer"));

        assertThatThrownBy(() -> accessService.listOwnAccounts(unmappedUser, null, CORRELATION_ID))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND));
    }

    @Test
    void inactiveCustomerStillReadsItsAccountsBecauseDownstreamEligibilityIsUnapproved() {
        long inactiveUser = inTransaction(() -> seedUser("account-inactive-owner"));
        long inactiveCustomer = inTransaction(() -> seedCustomer(inactiveUser, "INACTIVE"));
        long inactiveAccount = inTransaction(
                () -> seedAccount(inactiveCustomer, "0044444444", "ACTIVE", "1000.00", null));

        List<AccountView> views = accessService.listOwnAccounts(inactiveUser, null, CORRELATION_ID).items();

        assertThat(views).extracting(AccountView::accountId).containsExactly(inactiveAccount);
    }

    @Test
    void resolveDestinationUsesTheStoredBlindIndexAndRejectsInvalidInput() {
        Optional<AccountSnapshot> resolved = queryService.resolveDestination(ACCOUNT_A);

        assertThat(resolved).get().extracting(AccountSnapshot::accountId).isEqualTo(accountA);
        assertThat(resolved.orElseThrow().accountNumberMasked()).isEqualTo("**** **** 5678");
        assertThat(queryService.resolveDestination(ACCOUNT_B))
                .get().extracting(AccountSnapshot::accountId).isEqualTo(accountB);
        assertThat(queryService.resolveDestination("0055555555")).isEmpty();

        for (String invalid : new String[] {null, "12345", "123456789012345678901", "12a456"}) {
            assertThatThrownBy(() -> queryService.resolveDestination(invalid))
                    .isInstanceOfSatisfying(BankingBusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }
    }

    @Test
    void assertOwnershipAcceptsTheOwnerAndRejectsForeignCustomers() {
        queryService.assertOwnership(accountA, customerA, CORRELATION_ID);

        assertThatThrownBy(() -> queryService.assertOwnership(accountA, customerB, CORRELATION_ID))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    }

    @Test
    void findAccountReturnsTheMaskedSnapshotAndEmptyForUnknownIds() {
        assertThat(queryService.findAccount(accountA)).get()
                .extracting(AccountSnapshot::accountNumberMasked).isEqualTo("**** **** 5678");
        assertThat(queryService.findAccount(888_888)).isEmpty();
    }

    private double auditDeniedCount() {
        return meterRegistry.find("account.audit.events")
                .tag("action", "SECURITY_ACCESS_DENIED")
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private long seedAccount(long customerId, String accountNumber, String status,
            String availableBalance, Instant closedAt) {
        AccountNumberProtection.ProtectedAccountNumber protectedAccount =
                protection.protect(accountNumber);
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
                new BigDecimal(availableBalance),
                new BigDecimal(availableBalance),
                closedAt == null ? null : OffsetDateTime.ofInstant(closedAt, ZoneOffset.UTC));
    }

    private long seedUser(String prefix) {
        return jdbc.queryForObject("""
                INSERT INTO banking.app_user (username, password_hash, user_type)
                VALUES (?, 'synthetic-not-a-credential', 'CUSTOMER')
                RETURNING user_id
                """, Long.class, prefix + "-" + UUID.randomUUID());
    }

    private long seedCustomer(long userId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO banking.customer (user_id, full_name_enc, status)
                VALUES (?, ?, ?)
                RETURNING customer_id
                """, Long.class, userId, new byte[] {1, 2, 3}, status);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }
}
