package com.simulatedbank.banking.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.crypto.PiiCryptoService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.adapter.out.CommonCustomerPiiProtectionAdapter;
import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomerPiiServiceTest {
    private static final byte[] KEY = java.util.HexFormat.of().parseHex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");

    private InMemoryStore store;
    private CountingProtection protection;
    private CustomerPiiService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        protection = new CountingProtection(new CommonCustomerPiiProtectionAdapter(new PiiCryptoService(KEY)));
        service = new CustomerPiiService(store, protection);
    }

    @Test
    void createsCiphertextWithFreshIvAndReturnsOnlyMaskedContacts() {
        OwnerCustomerPiiView first = service.create(
                101, "Nguyễn An", "+84900000123", "n.synthetic@example.com", CustomerStatus.ACTIVE);
        OwnerCustomerPiiView second = service.create(
                102, "Nguyễn An", "+84900000123", "n.synthetic@example.com", CustomerStatus.ACTIVE);

        CustomerProfileStore.StoredCustomer firstStored = store.rows.get(first.getCustomerId());
        CustomerProfileStore.StoredCustomer secondStored = store.rows.get(second.getCustomerId());
        assertThat(protection.decrypt(firstStored.fullNameEncrypted())).isEqualTo("Nguyễn An");
        assertThat(protection.decrypt(firstStored.phoneEncrypted())).isEqualTo("+84900000123");
        assertThat(protection.decrypt(firstStored.emailEncrypted())).isEqualTo("n.synthetic@example.com");
        assertThat(firstStored.fullNameEncrypted()).isNotEqualTo(secondStored.fullNameEncrypted());
        assertThat(firstStored.phoneEncrypted()).isNotEqualTo(secondStored.phoneEncrypted());
        assertThat(firstStored.emailEncrypted()).isNotEqualTo(secondStored.emailEncrypted());
        assertThat(new String(firstStored.phoneEncrypted(), StandardCharsets.UTF_8))
                .doesNotContain("+84900000123");
        assertThat(first.getFullName()).isEqualTo("Nguyễn An");
        assertThat(first.getPhoneMasked()).isEqualTo("********123");
        assertThat(first.getEmailMasked()).isEqualTo("n***@example.com");
        assertThat(first.toString()).isEqualTo("OwnerCustomerPiiView[redacted]");
        assertThat(firstStored.toString()).isEqualTo("StoredCustomer[redacted]");
    }

    @Test
    void nullableContactsStayAbsentAndInvalidInputNeverWrites() {
        OwnerCustomerPiiView view = service.create(101, "Synthetic Customer", null, null, CustomerStatus.ACTIVE);
        CustomerProfileStore.StoredCustomer stored = store.rows.get(view.getCustomerId());
        assertThat(stored.phoneEncrypted()).isNull();
        assertThat(stored.emailEncrypted()).isNull();
        assertThat(view.getPhoneMasked()).isNull();
        assertThat(view.getEmailMasked()).isNull();

        assertValidation(() -> service.create(102, " ", null, null, CustomerStatus.ACTIVE));
        assertValidation(() -> service.create(103, "Name", "raw-phone", null, CustomerStatus.ACTIVE));
        assertValidation(() -> service.create(104, "Name", null, "raw-email", CustomerStatus.ACTIVE));
        assertThat(store.createCalls).isEqualTo(1);
    }

    @Test
    void verifiesOwnershipBeforeDecryptingAnyPii() {
        OwnerCustomerPiiView created = service.create(
                101, "Owner Name", "0900000123", "owner@example.com", CustomerStatus.ACTIVE);
        protection.decryptCalls = 0;

        assertThatThrownBy(() -> service.getForOwner(created.getCustomerId(), 202))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        assertThat(protection.decryptCalls).isZero();

        OwnerCustomerPiiView ownerView = service.getForOwner(created.getCustomerId(), 101);
        assertThat(ownerView.getFullName()).isEqualTo("Owner Name");
        assertThat(ownerView.getPhoneMasked()).isEqualTo("********123");
        assertThat(ownerView.getEmailMasked()).isEqualTo("o***@example.com");
    }

    @Test
    void corruptedCiphertextFailsClosedWithoutProviderOrPiiDetails() {
        OwnerCustomerPiiView created = service.create(
                101, "Protected Name", "0900000123", "safe@example.com", CustomerStatus.ACTIVE);
        CustomerProfileStore.StoredCustomer stored = store.rows.get(created.getCustomerId());
        byte[] corrupted = stored.fullNameEncrypted();
        corrupted[corrupted.length - 1] ^= 1;
        store.rows.put(created.getCustomerId(), copy(stored, corrupted, stored.phoneEncrypted(), stored.emailEncrypted()));

        assertThatThrownBy(() -> service.getForOwner(created.getCustomerId(), 101))
                .isInstanceOfSatisfying(BankingBusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(error.getMessage()).isEqualTo(ErrorCode.INTERNAL_ERROR.detail());
                    assertThat(error).hasNoCause();
                    assertThat(error.getMessage()).doesNotContain("Protected Name", "safe@example.com", "GCM");
                });
    }

    @Test
    void replacementUsesFreshIvAndPreservesUnchangedCiphertext() {
        OwnerCustomerPiiView created = service.create(
                101, "Owner Name", "0900000123", "owner@example.com", CustomerStatus.ACTIVE);
        CustomerProfileStore.StoredCustomer before = store.rows.get(created.getCustomerId());

        OwnerCustomerPiiView updated = service.replacePhoneForOwner(created.getCustomerId(), 101, "0900000123");
        CustomerProfileStore.StoredCustomer afterPhone = store.rows.get(created.getCustomerId());
        assertThat(afterPhone.phoneEncrypted()).isNotEqualTo(before.phoneEncrypted());
        assertThat(afterPhone.fullNameEncrypted()).containsExactly(before.fullNameEncrypted());
        assertThat(afterPhone.emailEncrypted()).containsExactly(before.emailEncrypted());
        assertThat(updated.getPhoneMasked()).isEqualTo("********123");

        OwnerCustomerPiiView cleared = service.replaceEmailForOwner(created.getCustomerId(), 101, null);
        CustomerProfileStore.StoredCustomer afterEmail = store.rows.get(created.getCustomerId());
        assertThat(afterEmail.fullNameEncrypted()).containsExactly(before.fullNameEncrypted());
        assertThat(afterEmail.phoneEncrypted()).containsExactly(afterPhone.phoneEncrypted());
        assertThat(afterEmail.emailEncrypted()).isNull();
        assertThat(cleared.getEmailMasked()).isNull();
    }

    @Test
    void encryptionFailureCannotReachPersistence() {
        CustomerPiiProtection failing = new CustomerPiiProtection() {
            private int calls;

            @Override
            public byte[] encrypt(String plaintext) {
                if (++calls == 2) {
                    throw new IllegalStateException("synthetic crypto failure");
                }
                return protection.encrypt(plaintext);
            }

            @Override
            public String decrypt(byte[] ciphertext) {
                return protection.decrypt(ciphertext);
            }

            @Override
            public String maskPhone(String phone) {
                return protection.maskPhone(phone);
            }

            @Override
            public String maskEmail(String email) {
                return protection.maskEmail(email);
            }
        };

        CustomerPiiService failingService = new CustomerPiiService(store, failing);
        assertThatThrownBy(() -> failingService.create(
                101, "Never Stored", "0900000123", "safe@example.com", CustomerStatus.ACTIVE))
                .isInstanceOfSatisfying(BankingBusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(error.getMessage()).doesNotContain("Never Stored", "synthetic crypto failure");
                });
        assertThat(store.createCalls).isZero();
        assertThat(store.rows).isEmpty();
    }

    private static void assertValidation(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private static CustomerProfileStore.StoredCustomer copy(
            CustomerProfileStore.StoredCustomer source,
            byte[] fullName,
            byte[] phone,
            byte[] email) {
        return new CustomerProfileStore.StoredCustomer(
                source.customerId(), source.userId(), fullName, phone, email, source.status(),
                source.createdAt(), source.updatedAt());
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
    }

    private static final class InMemoryStore implements CustomerProfileStore {
        private final Map<Long, StoredCustomer> rows = new LinkedHashMap<>();
        private long nextId = 1;
        private int createCalls;

        @Override
        public StoredCustomer create(
                long userId,
                byte[] fullNameEncrypted,
                byte[] phoneEncrypted,
                byte[] emailEncrypted,
                CustomerStatus status) {
            createCalls++;
            Instant now = Instant.parse("2026-11-17T00:00:00Z").plusNanos(nextId);
            StoredCustomer stored = new StoredCustomer(
                    nextId++, userId, fullNameEncrypted, phoneEncrypted, emailEncrypted, status, now, now);
            rows.put(stored.customerId(), stored);
            return stored;
        }

        @Override
        public Optional<StoredCustomer> findById(long customerId) {
            return Optional.ofNullable(rows.get(customerId));
        }

        @Override
        public StoredCustomer replaceEncryptedPii(
                long customerId,
                byte[] fullNameEncrypted,
                byte[] phoneEncrypted,
                byte[] emailEncrypted) {
            StoredCustomer current = rows.get(customerId);
            StoredCustomer updated = new StoredCustomer(
                    current.customerId(), current.userId(), fullNameEncrypted, phoneEncrypted, emailEncrypted,
                    current.status(), current.createdAt(), current.updatedAt().plusNanos(1));
            rows.put(customerId, updated);
            return updated;
        }
    }
}
