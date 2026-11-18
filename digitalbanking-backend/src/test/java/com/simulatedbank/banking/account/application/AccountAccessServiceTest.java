package com.simulatedbank.banking.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.account.adapter.out.CommonAccountNumberProtectionAdapter;
import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.account.api.AccountQuery.AccountView;
import com.simulatedbank.banking.account.api.AccountQuery.Status;
import com.simulatedbank.banking.account.application.AccountReadPort.AccountRecord;
import com.simulatedbank.banking.account.domain.AccountStatus;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.crypto.PiiCryptoService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit contract của {@link AccountAccessService} (BE-ACC-04): resolve `userId → customerId` từ
 * CustomerQuery, chỉ trả account của principal, chặn cross-customer và mapping vắng.
 */
class AccountAccessServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:15:30Z");
    private static final String CORRELATION_ID = "corr-self-1";
    private static final long CUSTOMER_A_USER = 101;
    private static final long CUSTOMER_B_USER = 202;

    private InMemoryAccounts accounts;
    private InMemoryCustomers customers;
    private RecordingAudit audit;
    private AccountAccessService service;
    private long customerA;
    private long customerB;
    private long accountA;
    private long accountB;

    @BeforeEach
    void setUp() {
        AccountNumberProtection protection = newProtection();
        accounts = new InMemoryAccounts(protection);
        customers = new InMemoryCustomers();
        customerA = customers.seed(CUSTOMER_A_USER, CustomerQuery.Status.ACTIVE);
        customerB = customers.seed(CUSTOMER_B_USER, CustomerQuery.Status.ACTIVE);
        accountA = accounts.seed(customerA, AccountStatus.ACTIVE, "0012345678");
        accountB = accounts.seed(customerB, AccountStatus.ACTIVE, "0098765432");

        audit = new RecordingAudit();
        AccountQueryService queryService =
                new AccountQueryService(accounts, protection, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        service = new AccountAccessService(
                customers, accounts, protection, queryService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listsOnlyAccountsOfTheResolvedCustomerWithStableOrderAndOneAsOf() {
        long lockedAccount = accounts.seed(customerA, AccountStatus.LOCKED, "0011111111");
        accounts.seed(customerB, AccountStatus.ACTIVE, "0022222222");

        List<AccountView> views = service.listOwnAccounts(CUSTOMER_A_USER, null, CORRELATION_ID).items();

        assertThat(views).extracting(AccountView::accountId).containsExactly(accountA, lockedAccount);
        assertThat(views).extracting(AccountView::accountNumberMasked)
                .containsExactly("**** **** 5678", "**** **** 1111");
        assertThat(views).allSatisfy(view -> {
            assertThat(view.customerId()).isEqualTo(customerA);
            assertThat(view.asOf()).isEqualTo(NOW);
        });
        assertThat(views).noneMatch(view -> view.accountId() == accountB);
        assertThat(views.getFirst().toString())
                .doesNotContain("0012345678", protectionHash("0012345678"));
    }

    @Test
    void forwardsTheOptionalStatusFilterToPersistence() {
        accounts.seed(customerA, AccountStatus.LOCKED, "0011111111");

        List<AccountView> locked =
                service.listOwnAccounts(CUSTOMER_A_USER, Status.LOCKED, CORRELATION_ID).items();

        assertThat(locked).extracting(AccountView::status).containsExactly(Status.LOCKED);
        assertThat(accounts.lastStatusFilter).isEqualTo(AccountStatus.LOCKED);
    }

    @Test
    void missingCustomerMappingIsNotFoundAndNeverQueriesAccounts() {
        long unmappedUser = 303;

        assertThatThrownBy(() -> service.listOwnAccounts(unmappedUser, null, CORRELATION_ID))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND));
        assertThat(accounts.customerLookups).isZero();
        assertThat(customers.lookups).isEqualTo(1);
    }

    @Test
    void invalidUserIdOrCorrelationIsRejectedBeforeCustomerLookup() {
        assertValidation(() -> service.listOwnAccounts(0, null, CORRELATION_ID));
        assertValidation(() -> service.listOwnAccounts(CUSTOMER_A_USER, null, "has space"));
        assertValidation(() -> service.getOwnAccount(CUSTOMER_A_USER, 0, CORRELATION_ID));
        assertThat(customers.lookups).isZero();
        assertThat(accounts.idLookups).isZero();
    }

    @Test
    void inactiveCustomerKeepsReadingItsAccountsBecauseEligibilityIsUnapproved() {
        long inactiveUser = 404;
        long inactiveCustomer = customers.seed(inactiveUser, CustomerQuery.Status.INACTIVE);
        long inactiveAccount = accounts.seed(inactiveCustomer, AccountStatus.ACTIVE, "0044444444");

        List<AccountView> views = service.listOwnAccounts(inactiveUser, null, CORRELATION_ID).items();

        assertThat(views).extracting(AccountView::accountId).containsExactly(inactiveAccount);
    }

    @Test
    void getOwnAccountReturnsOwnerViewForTheAuthenticatedCustomer() {
        AccountView view = service.getOwnAccount(CUSTOMER_A_USER, accountA, CORRELATION_ID);

        assertThat(view.accountId()).isEqualTo(accountA);
        assertThat(view.customerId()).isEqualTo(customerA);
        assertThat(view.accountNumberMasked()).isEqualTo("**** **** 5678");
        assertThat(view.asOf()).isEqualTo(NOW);
    }

    @Test
    void crossCustomerAccessIsRejectedAsForbiddenWithoutLeakingAccountData() {
        assertThatThrownBy(() -> service.getOwnAccount(CUSTOMER_B_USER, accountA, CORRELATION_ID))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN))
                .hasMessageNotContaining("5678")
                .hasMessageNotContaining("0012345678");

        assertThat(audit.events).containsExactly(
                AccountAuditPort.AccountAuditEvent.accessDenied(customerB, accountA));
    }

    @Test
    void unknownAccountIsNotFoundForAnAuthenticatedOwner() {
        assertThatThrownBy(() -> service.getOwnAccount(CUSTOMER_A_USER, 999_999, CORRELATION_ID))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    // ------------------------------------------------------------------ API-ADM-02

    @Test
    void staffListReturnsOnlyTheRequestedCustomerAccountsMaskedAndOrdered() {
        long lockedAccount = accounts.seed(customerA, AccountStatus.LOCKED, "0011111111");
        accounts.seed(customerB, AccountStatus.ACTIVE, "0022222222");

        AccountAccessService.AccountList page = service.listCustomerAccounts(
                customerA, null, Set.of("ACCOUNT_READ", "ROLE_OPERATIONS"), CORRELATION_ID);

        assertThat(page.items()).extracting(AccountView::accountId).containsExactly(accountA, lockedAccount);
        assertThat(page.items()).allSatisfy(view -> {
            assertThat(view.customerId()).isEqualTo(customerA);
            assertThat(view.accountNumberMasked()).matches("^\\*{4} \\*{4} [0-9]{4}$");
            assertThat(view.asOf()).isEqualTo(NOW);
        });
        assertThat(page.asOf()).isEqualTo(NOW);
        // Staff scope must not be derived from the actor's own customer mapping: the only Customer
        // provider call is the exact-ID existence check for the requested customer.
        assertThat(customers.lookups).isZero();
        assertThat(customers.customerIdLookups).isEqualTo(1);
    }

    @Test
    void staffListAppliesTheOptionalStatusFilter() {
        accounts.seed(customerA, AccountStatus.ACTIVE, "0011111111");
        long lockedAccount = accounts.seed(customerA, AccountStatus.LOCKED, "0022222222");

        AccountAccessService.AccountList page = service.listCustomerAccounts(
                customerA, Status.LOCKED, Set.of("ACCOUNT_READ"), CORRELATION_ID);

        assertThat(page.items()).extracting(AccountView::accountId).containsExactly(lockedAccount);
        assertThat(accounts.lastStatusFilter).isEqualTo(AccountStatus.LOCKED);
    }

    @Test
    void staffListRejectsACallerWithoutAccountReadBeforePersistence() {
        assertThatThrownBy(() -> service.listCustomerAccounts(customerA, null,
                Set.of("ACCOUNT_READ_SELF"), CORRELATION_ID))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        assertThatThrownBy(() -> service.listCustomerAccounts(customerA, null, null, CORRELATION_ID))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        // Fail-closed on an inconsistent identity snapshot: a customer principal never gets staff data,
        // even if the permission were mis-seeded onto it.
        assertThatThrownBy(() -> service.listCustomerAccounts(customerA, null,
                Set.of("ACCOUNT_READ", "ROLE_CUSTOMER"), CORRELATION_ID))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));

        assertThat(accounts.customerLookups).isZero();
        assertThat(customers.customerIdLookups).isZero();
        assertThat(audit.events).isEmpty();
    }

    @Test
    void staffListUnknownCustomerIsNotFoundBeforeAnyAccountRead() {
        assertThatThrownBy(() -> service.listCustomerAccounts(999_999, null,
                Set.of("ACCOUNT_READ", "ROLE_OPERATIONS"), CORRELATION_ID))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND));

        assertThat(customers.customerIdLookups).isEqualTo(1);
        assertThat(accounts.customerLookups).isZero();
        assertThat(audit.events).isEmpty();
    }

    @Test
    void staffListNeverHidesCustomerProviderFailuresAsNotFoundOrEmpty() {
        RuntimeException failure = new RuntimeException("synthetic customer provider outage");
        customers.failure = failure;

        assertThatThrownBy(() -> service.listCustomerAccounts(customerA, null,
                Set.of("ACCOUNT_READ", "ROLE_OPERATIONS"), CORRELATION_ID))
                .isSameAs(failure);
        // A technical provider error is fail-closed: no account data is read and it is not converted
        // into 404 (which would hide an outage) or an empty page.
        assertThat(accounts.customerLookups).isZero();
    }

    @Test
    void staffListRejectsInvalidCustomerOrCorrelationBeforePersistence() {
        assertValidation(() -> service.listCustomerAccounts(0, null, Set.of("ACCOUNT_READ"), CORRELATION_ID));
        assertValidation(() -> service.listCustomerAccounts(-5, null, Set.of("ACCOUNT_READ"), CORRELATION_ID));
        assertValidation(() -> service.listCustomerAccounts(customerA, null, Set.of("ACCOUNT_READ"), "has space"));

        assertThat(accounts.customerLookups).isZero();
    }

    private static void assertValidation(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BankingBusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private String protectionHash(String digits) {
        byte[] hmacKey = new byte[32];
        Arrays.fill(hmacKey, (byte) 0x22);
        return new HmacService(hmacKey).hmacSha256Hex(digits);
    }

    private static AccountNumberProtection newProtection() {
        byte[] aesKey = new byte[32];
        byte[] hmacKey = new byte[32];
        Arrays.fill(aesKey, (byte) 0x11);
        Arrays.fill(hmacKey, (byte) 0x22);
        return new CommonAccountNumberProtectionAdapter(
                new PiiCryptoService(aesKey), new HmacService(hmacKey));
    }

    private static final class RecordingAudit implements AccountAuditPort {

        private final List<AccountAuditEvent> events = new ArrayList<>();

        @Override
        public void recordStandalone(AccountAuditEvent event, String correlationId) {
            events.add(event);
        }
    }

    private static final class InMemoryCustomers implements CustomerQuery {

        private final Map<Long, CustomerIdentity> byUserId = new LinkedHashMap<>();
        private int lookups;
        private int customerIdLookups;
        private RuntimeException failure;

        long seed(long userId, Status status) {
            long customerId = 2000 + userId;
            byUserId.put(userId, new CustomerIdentity(customerId, userId, status));
            return customerId;
        }

        @Override
        public Optional<CustomerIdentity> findByUserId(long userId) {
            lookups++;
            return Optional.ofNullable(byUserId.get(userId));
        }

        @Override
        public Optional<CustomerIdentity> findByCustomerId(long customerId) {
            customerIdLookups++;
            if (failure != null) {
                throw failure;
            }
            return byUserId.values().stream()
                    .filter(identity -> identity.customerId() == customerId)
                    .findFirst();
        }
    }

    private static final class InMemoryAccounts implements AccountReadPort {

        private final AccountNumberProtection protection;
        private final List<AccountRecord> rows = new ArrayList<>();
        private final Map<String, Long> hashToId = new LinkedHashMap<>();
        private int idLookups;
        private int customerLookups;
        private long nextAccountId = 3001;
        private AccountStatus lastStatusFilter;

        InMemoryAccounts(AccountNumberProtection protection) {
            this.protection = protection;
        }

        long seed(long customerId, AccountStatus status, String accountNumber) {
            AccountRecord row = new AccountRecord(
                    nextAccountId++,
                    customerId,
                    status,
                    "VND",
                    new BigDecimal("12500000.00"),
                    new BigDecimal("12500000.00"),
                    accountNumber.substring(accountNumber.length() - 4),
                    Instant.parse("2026-01-15T08:00:00Z"),
                    Instant.parse("2026-11-01T09:00:00Z"));
            rows.add(row);
            hashToId.put(protection.blindIndex(accountNumber), row.accountId());
            return row.accountId();
        }

        @Override
        public Optional<AccountRecord> findById(long accountId) {
            idLookups++;
            return rows.stream().filter(row -> row.accountId() == accountId).findFirst();
        }

        @Override
        public Optional<AccountRecord> findByAccountNumberHash(String accountNumberHash) {
            Long accountId = hashToId.get(accountNumberHash);
            return accountId == null ? Optional.empty() : findById(accountId);
        }

        @Override
        public List<AccountRecord> findByCustomerId(long customerId, AccountStatus statusFilter) {
            customerLookups++;
            lastStatusFilter = statusFilter;
            return rows.stream()
                    .filter(row -> row.customerId() == customerId)
                    .filter(row -> statusFilter == null || row.status() == statusFilter)
                    .toList();
        }
    }
}
