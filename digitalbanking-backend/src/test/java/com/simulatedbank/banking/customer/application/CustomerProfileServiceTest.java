package com.simulatedbank.banking.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.crypto.PiiCryptoService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.adapter.out.CommonCustomerPiiProtectionAdapter;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/**
 * Unit contract of the {@code API-CUS-01} application service (BE-CUS-IMP-05): composition of the
 * real IMP-04 {@link CustomerQueryService} binding lookup and real IMP-03 {@link CustomerPiiService}
 * owner projection over in-memory ports — no Spring, no mocks of the security-relevant logic.
 */
class CustomerProfileServiceTest {

    private static final byte[] KEY = java.util.HexFormat.of().parseHex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");

    private InMemoryIdentityReader reader;
    private ReadOnlyInMemoryStore store;
    private CountingProtection protection;
    private CustomerProfileService service;

    @BeforeEach
    void setUp() {
        reader = new InMemoryIdentityReader();
        store = new ReadOnlyInMemoryStore();
        protection = new CountingProtection(new CommonCustomerPiiProtectionAdapter(new PiiCryptoService(KEY)));
        CustomerPiiService piiService = new CustomerPiiService(store, protection);
        CustomerQuery customerQuery = new CustomerQueryService(reader);
        service = new CustomerProfileService(customerQuery, piiService);
    }

    @Test
    void resolvesProfileThroughTheApprovedUserIdBindingExactlyOnce() {
        seed(101, "Nguyễn An", "+84900000123", "n.synthetic@example.com", CustomerStatus.ACTIVE);

        OwnerCustomerPiiView view = service.getMyProfile(101);

        assertThat(reader.lookups).containsExactly(101L);
        assertThat(store.findByIdCalls).isEqualTo(1);
        assertThat(view.getUserId()).isEqualTo(101);
        assertThat(view.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    void ownerSeesFullNameInFullAndContactsAlwaysMasked() {
        seed(101, "Nguyễn An", "+84900000123", "n.synthetic@example.com", CustomerStatus.ACTIVE);

        OwnerCustomerPiiView view = service.getMyProfile(101);

        assertThat(view.getFullName()).isEqualTo("Nguyễn An");
        assertThat(view.getPhoneMasked()).isEqualTo("********123");
        assertThat(view.getEmailMasked()).isEqualTo("n***@example.com");
        // Chỉ projection owner-safe; ciphertext không bao giờ rời application layer.
        assertThat(new String(store.rows.values().iterator().next().fullNameEncrypted(), StandardCharsets.UTF_8))
                .doesNotContain("Nguyễn An");
    }

    @Test
    void nullableContactsStayAbsentInOwnerProjection() {
        seed(101, "Synthetic Customer", null, null, CustomerStatus.ACTIVE);

        OwnerCustomerPiiView view = service.getMyProfile(101);

        assertThat(view.getPhoneMasked()).isNull();
        assertThat(view.getEmailMasked()).isNull();
    }

    @Test
    void missingCustomerMappingReturnsCustomerNotFoundWithoutTouchingPii() {
        assertThatThrownBy(() -> service.getMyProfile(404))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND));
        assertThat(protection.decryptCalls).isZero();
        assertThat(store.findByIdCalls).isZero();
        assertThat(store.rows).isEmpty();
    }

    @Test
    void invalidPrincipalIsRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.getMyProfile(0))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThat(reader.lookups).isEmpty();
        assertThat(store.rows).isEmpty();
    }

    @Test
    void corruptBindingOwnershipMismatchStaysForbiddenAndNeverDecryptsForeignPii() {
        OwnerCustomerPiiView owned = seed(101, "Owner Name", "0900000123", "owner@example.com", CustomerStatus.ACTIVE);
        // Ánh xạ hỏng: user 202 bị map vào customer của user 101 — service không được rò PII.
        reader.rows.put(202L, new CustomerIdentityReader.Identity(owned.getCustomerId(), 101, CustomerStatus.ACTIVE));
        int writesDuringSeed = store.createCalls + store.replaceCalls;

        assertThatThrownBy(() -> service.getMyProfile(202))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        assertThat(protection.decryptCalls).isZero();
        assertThat(store.createCalls + store.replaceCalls).isEqualTo(writesDuringSeed);
    }

    @Test
    void inactiveCustomerStillReturnsItsOwnProfileWithRealStatus() {
        seed(101, "Inactive Owner", null, null, CustomerStatus.INACTIVE);

        OwnerCustomerPiiView view = service.getMyProfile(101);

        assertThat(view.getStatus()).isEqualTo(CustomerStatus.INACTIVE);
    }

    @Test
    void retrievalIsStrictlyReadOnly() {
        OwnerCustomerPiiView seeded = seed(101, "Nguyễn An", "+84900000123", "n.synthetic@example.com",
                CustomerStatus.ACTIVE);
        int writesDuringSeed = store.createCalls + store.replaceCalls;

        service.getMyProfile(101);
        service.getMyProfile(101);

        assertThat(store.createCalls + store.replaceCalls).isEqualTo(writesDuringSeed);
        assertThat(store.rows.get(seeded.getCustomerId()).updatedAt()).isEqualTo(seeded.getUpdatedAt());
    }

    @Test
    void persistenceFailurePropagatesUnchangedInsteadOfBeingReclassifiedAs404() {
        RuntimeException syntheticFailure = new QueryTimeoutException("synthetic persistence failure");
        reader.failure = syntheticFailure;

        assertThatThrownBy(() -> service.getMyProfile(101)).isSameAs(syntheticFailure);
    }

    @Test
    void cryptoFailureFailsClosedWithInternalErrorAndNoPiiDetails() {
        OwnerCustomerPiiView created = seed(101, "Protected Name", "0900000123", "safe@example.com",
                CustomerStatus.ACTIVE);
        ReadOnlyInMemoryStore.StoredRow stored = store.rows.get(created.getCustomerId());
        byte[] corrupted = stored.fullNameEncrypted();
        corrupted[corrupted.length - 1] ^= 1;
        store.rows.put(created.getCustomerId(), new ReadOnlyInMemoryStore.StoredRow(
                stored.customerId(), stored.userId(), corrupted, stored.phoneEncrypted(), stored.emailEncrypted(),
                stored.status(), stored.createdAt(), stored.updatedAt()));

        assertThatThrownBy(() -> service.getMyProfile(101))
                .isInstanceOfSatisfying(BankingBusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(error.getMessage()).doesNotContain("Protected Name", "safe@example.com", "GCM");
                });
    }

    private OwnerCustomerPiiView seed(long userId, String fullName, String phone, String email,
            CustomerStatus status) {
        OwnerCustomerPiiView created = new CustomerPiiService(store, protection)
                .create(userId, fullName, phone, email, status);
        reader.rows.put(userId, new CustomerIdentityReader.Identity(
                created.getCustomerId(), userId, status));
        return created;
    }

    /** Minimal fake of the approved binding port (IMP-04); records authoritative lookups. */
    private static final class InMemoryIdentityReader implements CustomerIdentityReader {
        private final Map<Long, Identity> rows = new LinkedHashMap<>();
        private final List<Long> lookups = new java.util.ArrayList<>();
        private RuntimeException failure;

        @Override
        public Optional<Identity> findByUserId(long userId) {
            lookups.add(userId);
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(rows.get(userId));
        }

        @Override
        public Optional<Identity> findById(long customerId) {
            throw new AssertionError("the profile path must not resolve a customer by id");
        }
    }

    /** Store that proves the profile path never writes: mutation calls fail loudly. */
    private static final class ReadOnlyInMemoryStore implements CustomerProfileStore {
        private final Map<Long, StoredRow> rows = new LinkedHashMap<>();
        private int createCalls;
        private int replaceCalls;
        private int findByIdCalls;
        private long customerIdSequence = 5000;

        @Override
        public StoredCustomer create(long userId, byte[] fullNameEncrypted, byte[] phoneEncrypted,
                byte[] emailEncrypted, CustomerStatus status) {
            createCalls++;
            long customerId = customerIdSequence++;
            StoredCustomer stored = new StoredCustomer(customerId, userId, fullNameEncrypted, phoneEncrypted,
                    emailEncrypted, status, Instant.now(), Instant.now());
            rows.put(customerId, toRow(stored));
            return stored;
        }

        @Override
        public Optional<StoredCustomer> findById(long customerId) {
            findByIdCalls++;
            return rows.values().stream()
                    .filter(row -> row.customerId() == customerId)
                    .findFirst()
                    .map(ReadOnlyInMemoryStore::toStored);
        }

        @Override
        public StoredCustomer replaceEncryptedPii(long customerId, byte[] fullNameEncrypted,
                byte[] phoneEncrypted, byte[] emailEncrypted) {
            replaceCalls++;
            throw new AssertionError("Profile retrieval must never write");
        }

        private static StoredCustomer toStored(StoredRow row) {
            return new StoredCustomer(row.customerId(), row.userId(), row.fullNameEncrypted(),
                    row.phoneEncrypted(), row.emailEncrypted(), row.status(), row.createdAt(), row.updatedAt());
        }

        private static StoredRow toRow(StoredCustomer stored) {
            return new StoredRow(stored.customerId(), stored.userId(), stored.fullNameEncrypted(),
                    stored.phoneEncrypted(), stored.emailEncrypted(), stored.status(), stored.createdAt(),
                    stored.updatedAt());
        }

        private record StoredRow(long customerId, long userId, byte[] fullNameEncrypted, byte[] phoneEncrypted,
                byte[] emailEncrypted, CustomerStatus status, Instant createdAt, Instant updatedAt) {
        }
    }

    private static final class CountingProtection implements CustomerPiiProtection {
        private final CustomerPiiProtection delegate;
        private int decryptCalls;

        private CountingProtection(CustomerPiiProtection delegate) {
            this.delegate = delegate;
        }

        @Override
        public byte[] encrypt(String plaintext) {
            return delegate.encrypt(plaintext);
        }

        @Override
        public String decrypt(byte[] ciphertext) {
            decryptCalls++;
            return delegate.decrypt(ciphertext);
        }

        @Override
        public String maskPhone(String phone) {
            return delegate.maskPhone(phone);
        }

        @Override
        public String maskEmail(String email) {
            return delegate.maskEmail(email);
        }

        @Override
        public String maskFullName(String fullName) {
            return delegate.maskFullName(fullName);
        }
    }
}
