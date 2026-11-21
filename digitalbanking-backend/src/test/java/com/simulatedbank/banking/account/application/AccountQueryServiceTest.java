package com.simulatedbank.banking.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.account.adapter.out.CommonAccountNumberProtectionAdapter;
import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.account.api.AccountQuery.AccountSnapshot;
import com.simulatedbank.banking.account.api.AccountQuery.AccountView;
import com.simulatedbank.banking.account.api.AccountQuery.AccountViewQuery;
import com.simulatedbank.banking.account.api.AccountQuery.Status;
import com.simulatedbank.banking.account.application.AccountReadPort.AccountRecord;
import com.simulatedbank.banking.account.domain.AccountStatus;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.crypto.PiiCryptoService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Unit contract của {@link AccountQueryService} (BE-ACC-04): đọc masked, resolve bằng blind
 * index, ownership/IDOR và fail-closed khi persistence/crypto lỗi.
 */
class AccountQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:15:30Z");
    private static final String ACCOUNT_NUMBER = "0012345678";
    private static final String CORRELATION_ID = "corr-acc-1";

    private InMemoryAccounts accounts;
    private RecordingAudit audit;
    private AccountNumberProtection protection;
    private AccountQueryService service;
    private long customerId;
    private long accountId;

    @BeforeEach
    void setUp() {
        accounts = new InMemoryAccounts();
        audit = new RecordingAudit();
        protection = newProtection();
        service = new AccountQueryService(accounts, protection, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        customerId = 2001;
        accountId = accounts.seed(customerId, AccountStatus.ACTIVE, ACCOUNT_NUMBER);
    }

    @Test
    void findAccountReturnsMaskedSnapshotWithoutCiphertextOrBlindIndex() {
        Optional<AccountSnapshot> snapshot = service.findAccount(accountId);

        assertThat(snapshot).get().satisfies(found -> {
            assertThat(found.accountId()).isEqualTo(accountId);
            assertThat(found.customerId()).isEqualTo(customerId);
            assertThat(found.status()).isEqualTo(Status.ACTIVE);
            assertThat(found.currency()).isEqualTo("VND");
            assertThat(found.accountNumberMasked()).isEqualTo("**** **** 5678");
            assertThat(found.accountNumberLast4()).isEqualTo("5678");
        });
        assertThat(snapshot.orElseThrow().toString())
                .doesNotContain(ACCOUNT_NUMBER)
                .doesNotContain(protection.blindIndex(ACCOUNT_NUMBER))
                .contains("**** **** 5678");
    }

    @Test
    void findAccountRejectsNonPositiveIdWithoutTouchingPersistence() {
        assertValidation(() -> service.findAccount(0));
        assertValidation(() -> service.findAccount(-5));
        assertThat(accounts.idLookups).isZero();
    }

    @Test
    void findAccountReturnsEmptyWhenAccountDoesNotExist() {
        assertThat(service.findAccount(999_999)).isEmpty();
    }

    @Test
    void resolveDestinationUsesTheHmacBlindIndexOfTheNormalizedNumber() {
        Optional<AccountSnapshot> resolved = service.resolveDestination(ACCOUNT_NUMBER);

        assertThat(resolved).get().extracting(AccountSnapshot::accountId).isEqualTo(accountId);
        assertThat(accounts.lastHashLookup).isEqualTo(protection.blindIndex(ACCOUNT_NUMBER));
        assertThat(service.resolveDestination("0012345679")).isEmpty();
    }

    @Test
    void resolveDestinationRejectsEveryInvalidFormatBeforeHashing() {
        for (String invalid : new String[] {null, "", "12345", "123456789012345678901", " 123456",
                "123456 ", "12345a", "+123456"}) {
            assertValidation(() -> service.resolveDestination(invalid));
        }
        assertThat(accounts.hashLookups).isZero();
    }

    @Test
    void getAccountViewReturnsMaskedOwnerViewWithSingleAsOf() {
        AccountView view = service.getAccountView(
                AccountViewQuery.forOwner(accountId, customerId, CORRELATION_ID));

        assertThat(view.accountNumberMasked()).isEqualTo("**** **** 5678");
        assertThat(view.availableBalance()).isEqualByComparingTo("12500000.00");
        assertThat(view.ledgerBalance()).isEqualByComparingTo("12500000.00");
        assertThat(view.asOf()).isEqualTo(NOW);
        assertThat(audit.events).isEmpty();
    }

    @Test
    void getAccountViewStaffScopeDoesNotApplyOwnerComparison() {
        AccountView view = service.getAccountView(
                AccountViewQuery.forStaff(accountId, CORRELATION_ID));

        assertThat(view.accountId()).isEqualTo(accountId);
        assertThat(audit.events).isEmpty();
    }

    @Test
    void ownerMismatchIsForbiddenAuditedAndLeaksNoAccountData() {
        long foreignCustomer = 2999;

        assertThatThrownBy(() -> service.getAccountView(
                AccountViewQuery.forOwner(accountId, foreignCustomer, CORRELATION_ID)))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN))
                .hasMessageNotContaining(ACCOUNT_NUMBER)
                .hasMessageNotContaining("5678");

        assertThat(audit.events).containsExactly(
                AccountAuditPort.AccountAuditEvent.accessDenied(foreignCustomer, accountId));
        assertThat(audit.correlations).containsExactly(CORRELATION_ID);
    }

    @Test
    void missingAccountIsNotFoundWithoutAudit() {
        assertThatThrownBy(() -> service.getAccountView(
                AccountViewQuery.forOwner(999_999, customerId, CORRELATION_ID)))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
        assertThat(audit.events).isEmpty();
    }

    @Test
    void assertOwnershipAcceptsOwnerAndRejectsMismatchWithAuditAndMissingAsNotFound() {
        service.assertOwnership(accountId, customerId, CORRELATION_ID);
        assertThat(audit.events).isEmpty();

        assertThatThrownBy(() -> service.assertOwnership(accountId, 2999, CORRELATION_ID))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        assertThat(audit.events).containsExactly(
                AccountAuditPort.AccountAuditEvent.accessDenied(2999, accountId));

        assertThatThrownBy(() -> service.assertOwnership(888_888, customerId, CORRELATION_ID))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
        assertThat(audit.events).hasSize(1);
    }

    @Test
    void assertOwnershipRejectsInvalidArgumentsBeforePersistence() {
        assertValidation(() -> service.assertOwnership(0, customerId, CORRELATION_ID));
        assertValidation(() -> service.assertOwnership(accountId, 0, CORRELATION_ID));
        assertValidation(() -> service.assertOwnership(accountId, customerId, null));
        assertValidation(() -> service.assertOwnership(accountId, customerId, "has space"));
        assertThat(accounts.idLookups).isZero();
    }

    @Test
    void auditFailureNeverChangesTheForbiddenDecision() {
        RecordingAudit failingAudit = new RecordingAudit() {
            @Override
            public void recordStandalone(AccountAuditEvent event, String correlationId) {
                throw new IllegalStateException("audit unavailable");
            }
        };
        AccountQueryService serviceWithFailingAudit = new AccountQueryService(
                accounts, protection, failingAudit, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> serviceWithFailingAudit.assertOwnership(accountId, 2999, CORRELATION_ID))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    }

    @Test
    void technicalPersistenceFailurePropagatesInsteadOfBecomingEmptyOrNotFound() {
        DataAccessResourceFailureException databaseDown =
                new DataAccessResourceFailureException("connection refused");
        accounts.failure = databaseDown;

        assertThatThrownBy(() -> service.findAccount(accountId)).isSameAs(databaseDown);
        assertThatThrownBy(() -> service.getAccountView(
                AccountViewQuery.forOwner(accountId, customerId, CORRELATION_ID)))
                .isSameAs(databaseDown);
        assertThatThrownBy(() -> service.assertOwnership(accountId, customerId, CORRELATION_ID))
                .isSameAs(databaseDown);
    }

    @Test
    void blindIndexFailureFailsClosedAsInternalError() {
        AccountNumberProtection broken = new AccountNumberProtection() {
            @Override
            public ProtectedAccountNumber protect(String accountNumberDigits) {
                throw new IllegalStateException("crypto unavailable");
            }

            @Override
            public String blindIndex(String accountNumberDigits) {
                throw new IllegalStateException("crypto unavailable");
            }

            @Override
            public String decrypt(byte[] ciphertext) {
                throw new IllegalStateException("crypto unavailable");
            }

            @Override
            public String mask(String accountNumberLast4) {
                throw new IllegalStateException("crypto unavailable");
            }
        };
        AccountQueryService brokenService = new AccountQueryService(
                accounts, broken, audit, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> brokenService.resolveDestination(ACCOUNT_NUMBER))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        assertThatThrownBy(() -> brokenService.findAccount(accountId))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    private static void assertValidation(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BankingBusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private static AccountNumberProtection newProtection() {
        byte[] aesKey = new byte[32];
        byte[] hmacKey = new byte[32];
        Arrays.fill(aesKey, (byte) 0x11);
        Arrays.fill(hmacKey, (byte) 0x22);
        return new CommonAccountNumberProtectionAdapter(
                new PiiCryptoService(aesKey), new HmacService(hmacKey));
    }

    /** In-memory read port: 1 lookup counter per access path, failure injection, seed helper. */
    private static final class InMemoryAccounts implements AccountReadPort {

        private final Map<Long, AccountRecord> byId = new LinkedHashMap<>();
        private final Map<String, AccountRecord> byHash = new LinkedHashMap<>();
        private int idLookups;
        private int hashLookups;
        private String lastHashLookup;
        private RuntimeException failure;
        private long nextAccountId = 3001;

        long seed(long customerId, AccountStatus status, String accountNumber) {
            String hash = newProtection().blindIndex(accountNumber);
            AccountRecord record = new AccountRecord(
                    nextAccountId++,
                    customerId,
                    status,
                    "VND",
                    new BigDecimal("12500000.00"),
                    new BigDecimal("12500000.00"),
                    accountNumber.substring(accountNumber.length() - 4),
                    Instant.parse("2026-01-15T08:00:00Z"),
                    Instant.parse("2026-11-01T09:00:00Z"));
            byId.put(record.accountId(), record);
            byHash.put(hash, record);
            return record.accountId();
        }

        @Override
        public Optional<AccountRecord> findById(long accountId) {
            failIfConfigured();
            idLookups++;
            return Optional.ofNullable(byId.get(accountId));
        }

        @Override
        public Optional<AccountRecord> findByAccountNumberHash(String accountNumberHash) {
            failIfConfigured();
            hashLookups++;
            lastHashLookup = accountNumberHash;
            return Optional.ofNullable(byHash.get(accountNumberHash));
        }

        @Override
        public List<AccountRecord> findByCustomerId(long ownerCustomerId, AccountStatus statusFilter) {
            failIfConfigured();
            return byId.values().stream()
                    .filter(record -> record.customerId() == ownerCustomerId)
                    .filter(record -> statusFilter == null || record.status() == statusFilter)
                    .toList();
        }

        private void failIfConfigured() {
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** Records C3 events; the service must call it only on ownership mismatch. */
    private static class RecordingAudit implements AccountAuditPort {

        private final List<AccountAuditEvent> events = new ArrayList<>();
        private final List<String> correlations = new ArrayList<>();

        @Override
        public void recordStandalone(AccountAuditEvent event, String correlationId) {
            events.add(event);
            correlations.add(correlationId);
        }
    }
}
