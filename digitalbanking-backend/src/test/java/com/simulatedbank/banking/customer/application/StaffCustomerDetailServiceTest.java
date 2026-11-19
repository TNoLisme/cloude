package com.simulatedbank.banking.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.crypto.PiiCryptoService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.adapter.out.CommonCustomerPiiProtectionAdapter;
import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit contract của service `API-ADM-01` `GET /api/v1/admin/customers/{customerId}`
 * (BE-CUS-IMP-06): composition thật của {@link CustomerPiiService} (IMP-03) + AES thật trên
 * port in-memory — không mock logic bảo mật/PII; port audit là fake thu thập sự kiện để khẳng
 * định metadata đúng hợp đồng design/01 §5.3/§5.6.
 */
class StaffCustomerDetailServiceTest {

    private static final byte[] KEY = java.util.HexFormat.of().parseHex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");

    private RecordingStore store;
    private CountingProtection protection;
    private RecordingAuditPort audit;
    private StaffCustomerDetailService service;

    @BeforeEach
    void setUp() {
        store = new RecordingStore();
        protection = new CountingProtection(new CommonCustomerPiiProtectionAdapter(new PiiCryptoService(KEY)));
        audit = new RecordingAuditPort();
        service = new StaffCustomerDetailService(new CustomerPiiService(store, protection), audit);
    }

    @Test
    void staffWithRequiredPermissionReceivesFullyMaskedProjection() {
        long customerId = seed("Nguyễn Văn An", "+84900000123", "nva.synthetic@example.com",
                CustomerStatus.ACTIVE);

        StaffCustomerPiiView view = service.getCustomerDetail(customerId, 501L,
                Set.of("CUSTOMER_READ", "ROLE_OPERATIONS"), "OPERATIONS", "corr-staff-200");

        assertThat(view.getCustomerId()).isEqualTo(customerId);
        assertThat(view.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
        // Mask đúng oracle DEC-CUS-005: chỉ ký tự đầu mỗi token, phần còn lại `***`.
        assertThat(view.getFullName()).isEqualTo("N*** V*** A***");
        assertThat(view.getFullName()).doesNotContain("Nguyễn", "Văn", "An");
        assertThat(view.getPhoneMasked()).isEqualTo("********123");
        assertThat(view.getEmailMasked()).isEqualTo("n***@example.com");
        // Truy cập hợp lệ KHÔNG phát audit event (design/01 §5.7: chỉ technical access log).
        assertThat(audit.events).isEmpty();
        assertThat(store.findByIdCalls).isEqualTo(1);
        assertThat(protection.decryptCalls).isEqualTo(3); // fullName + phone + email
    }

    @Test
    void customerRoleIsDeniedBeforeAnyLookupOrDecryption() {
        long customerId = seed("Khách Hàng A", "+84900000456", "kha.synthetic@example.com",
                CustomerStatus.ACTIVE);

        assertThatThrownBy(() -> service.getCustomerDetail(customerId, 901L,
                Set.of("ACCOUNT_READ_SELF", "TRANSFER_CREATE", "ROLE_CUSTOMER"), "CUSTOMER",
                "corr-cus-403"))
                .isInstanceOf(BankingBusinessException.class)
                .extracting(e -> ((BankingBusinessException) e).code())
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);

        // 403 xảy ra TRƯỚC lookup: sự tồn tại của customer không được tiết lộ.
        assertThat(store.findByIdCalls).isZero();
        assertThat(protection.decryptCalls).isZero();
        // CUSTOMER bị từ chối phải được audit theo design/01 §5.3 (SECURITY_ACCESS_DENIED, REJECTED).
        assertThat(audit.events).hasSize(1);
        assertThat(audit.events.get(0).action()).isEqualTo(CustomerAuditPort.CustomerAction.SECURITY_ACCESS_DENIED);
        assertThat(audit.events.get(0).actorUserId()).isEqualTo(901L);
        assertThat(audit.events.get(0).actorRoleCodes()).isEqualTo("CUSTOMER");
        assertThat(audit.events.get(0).entityType()).isEqualTo("CUSTOMER");
        assertThat(audit.events.get(0).entityId()).isEqualTo(Long.toString(customerId));
        assertThat(audit.events.get(0).reasonCode()).isEqualTo("AUTH_FORBIDDEN");
        assertThat(audit.correlationIds).containsExactly("corr-cus-403");
    }

    @Test
    void mixedCustomerAndStaffAuthoritiesAreDeniedBeforeAnyLookup() {
        long customerId = seed("Hỗn Hợp", null, null, CustomerStatus.ACTIVE);

        // Even if a customer principal were mis-seeded with the staff permission, the snapshot carries
        // ROLE_CUSTOMER and is an inconsistent identity context: staff data must stay closed.
        assertThatThrownBy(() -> service.getCustomerDetail(customerId, 902L,
                Set.of("CUSTOMER_READ", "ROLE_CUSTOMER"), "CUSTOMER", "corr-mixed-403"))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));

        assertThat(store.findByIdCalls).isZero();
        assertThat(protection.decryptCalls).isZero();
        assertThat(audit.events).hasSize(1);
        assertThat(audit.events.get(0).reasonCode()).isEqualTo("AUTH_FORBIDDEN");
    }

    @Test
    void staffWithoutRequiredPermissionIsDeniedAndAudited() {
        long customerId = seed("Nhân Viên Lẻ", null, null, CustomerStatus.INACTIVE);

        assertThatThrownBy(() -> service.getCustomerDetail(customerId, 502L,
                Set.of("ACCOUNT_READ", "ROLE_OPERATIONS"), "OPERATIONS", "corr-staff-noperm"))
                .isInstanceOf(BankingBusinessException.class)
                .extracting(e -> ((BankingBusinessException) e).code())
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);

        assertThat(store.findByIdCalls).isZero();
        assertThat(audit.events).hasSize(1);
        assertThat(audit.events.get(0).entityId()).isEqualTo(Long.toString(customerId));
    }

    @Test
    void missingTrustedAuthoritySnapshotFailsClosed() {
        long customerId = seed("Snapshot Thiếu", null, null, CustomerStatus.ACTIVE);

        assertThatThrownBy(() -> service.getCustomerDetail(customerId, 503L, null, "OPERATIONS",
                "corr-no-snapshot"))
                .isInstanceOf(BankingBusinessException.class)
                .extracting(e -> ((BankingBusinessException) e).code())
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);
        assertThat(store.findByIdCalls).isZero();
        assertThat(audit.events).hasSize(1);
    }

    @Test
    void invalidCustomerIdIsRejectedBeforeLookupWithoutAudit() {
        assertThatThrownBy(() -> service.getCustomerDetail(0L, 501L,
                Set.of("CUSTOMER_READ", "ROLE_OPERATIONS"), "OPERATIONS", "corr-bad-id"))
                .isInstanceOf(BankingBusinessException.class)
                .extracting(e -> ((BankingBusinessException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> service.getCustomerDetail(-7L, 501L,
                Set.of("CUSTOMER_READ", "ROLE_OPERATIONS"), "OPERATIONS", "corr-bad-id"))
                .isInstanceOf(BankingBusinessException.class)
                .extracting(e -> ((BankingBusinessException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(store.findByIdCalls).isZero();
        assertThat(audit.events).isEmpty();
    }

    @Test
    void nonexistentCustomerReturnsNotFoundWithoutAudit() {
        assertThatThrownBy(() -> service.getCustomerDetail(424_242L, 501L,
                Set.of("CUSTOMER_READ", "ROLE_OPERATIONS"), "OPERATIONS", "corr-404"))
                .isInstanceOf(BankingBusinessException.class)
                .extracting(e -> ((BankingBusinessException) e).code())
                .isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND);
        // 404 không phải security denial → không audit (design/01 §5.7).
        assertThat(audit.events).isEmpty();
        assertThat(store.findByIdCalls).isEqualTo(1);
    }

    @Test
    void inactiveCustomerIsReturnedWithRealStatusAndNullableContacts() {
        long customerId = seed("Hồ Sơ Ngừng Hoạt", null, null, CustomerStatus.INACTIVE);

        StaffCustomerPiiView view = service.getCustomerDetail(customerId, 501L,
                Set.of("CUSTOMER_READ", "ROLE_OPERATIONS"), "OPERATIONS", "corr-inactive");

        assertThat(view.getStatus()).isEqualTo(CustomerStatus.INACTIVE);
        assertThat(view.getFullName()).isEqualTo("H*** S*** N*** H***");
        assertThat(view.getPhoneMasked()).isNull();
        assertThat(view.getEmailMasked()).isNull();
    }

    @Test
    void staffViewNeverCarriesUnmaskedPiiThroughToStringOrValue() {
        long customerId = seed("Nguyễn Thị Minh", "+84900000789", "ntm.synthetic@example.com",
                CustomerStatus.ACTIVE);

        StaffCustomerPiiView view = service.getCustomerDetail(customerId, 501L,
                Set.of("CUSTOMER_READ", "ROLE_ADMIN"), "ADMIN", "corr-leak");

        assertThat(view.toString()).isEqualTo("StaffCustomerPiiView[redacted]");
        assertThat(view.getFullName()).doesNotContain("Nguyễn", "Thị", "Minh");
        assertThat(view.getPhoneMasked()).doesNotContain("84900000789");
        assertThat(view.getEmailMasked()).doesNotContain("ntm.synthetic");
        // Audit event không chứa PII: chỉ mã enum/ID/reasonCode.
        assertThatThrownBy(() -> service.getCustomerDetail(customerId, 901L,
                Set.of("ROLE_CUSTOMER"), "CUSTOMER", "corr-pii-audit"));
        assertThat(audit.events.get(0).toString()).doesNotContain("Nguyễn", "84900000789");
    }

    private long seed(String fullName, String phone, String email, CustomerStatus status) {
        CustomerPiiService created = new CustomerPiiService(store, protection);
        return created.create(601L + store.rows.size() + 1, fullName, phone, email, status).getCustomerId();
    }

    /** Ghi nhớ số lần lookup; ghi là lỗi chết (chỉ đọc). */
    private static final class RecordingStore implements CustomerProfileStore {
        private final java.util.Map<Long, StoredCustomer> rows = new java.util.LinkedHashMap<>();
        private int findByIdCalls;

        @Override
        public StoredCustomer create(long userId, byte[] fullNameEncrypted, byte[] phoneEncrypted,
                byte[] emailEncrypted, CustomerStatus status) {
            long customerId = rows.size() + 1;
            StoredCustomer stored = new StoredCustomer(customerId, userId, fullNameEncrypted,
                    phoneEncrypted, emailEncrypted, status, java.time.Instant.parse("2026-11-17T00:00:00Z"),
                    java.time.Instant.parse("2026-11-17T00:00:00Z"));
            rows.put(customerId, stored);
            return stored;
        }

        @Override
        public java.util.Optional<StoredCustomer> findById(long customerId) {
            findByIdCalls++;
            return java.util.Optional.ofNullable(rows.get(customerId));
        }

        @Override
        public StoredCustomer replaceEncryptedPii(long customerId, byte[] fullNameEncrypted,
                byte[] phoneEncrypted, byte[] emailEncrypted) {
            throw new AssertionError("Staff detail is read-only; never writes");
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

    /** Thu thập sự kiện audit để khẳng định metadata + correlation; không nuốt. */
    private static final class RecordingAuditPort implements CustomerAuditPort {
        private final List<CustomerAuditEvent> events = new java.util.ArrayList<>();
        private final List<String> correlationIds = new java.util.ArrayList<>();

        @Override
        public void recordStandalone(CustomerAuditEvent event, String correlationId) {
            events.add(event);
            correlationIds.add(correlationId);
        }
    }
}
