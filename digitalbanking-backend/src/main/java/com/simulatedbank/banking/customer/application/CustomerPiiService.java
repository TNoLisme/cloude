package com.simulatedbank.banking.customer.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.domain.CustomerStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Encrypts Customer PII before persistence and releases only owner-safe projections. */
@Service
@ConditionalOnProperty(name = "app.customer.enabled", havingValue = "true", matchIfMissing = true)
public class CustomerPiiService {
    private final CustomerProfileStore store;
    private final CustomerPiiProtection protection;

    public CustomerPiiService(CustomerProfileStore store, CustomerPiiProtection protection) {
        this.store = store;
        this.protection = protection;
    }

    @Transactional
    public OwnerCustomerPiiView create(
            long userId,
            String fullName,
            String phone,
            String email,
            CustomerStatus status) {
        requirePositive(userId);
        requireFullName(fullName);
        if (status == null) {
            throw validationFailure();
        }

        String phoneMasked = validatePhone(phone);
        String emailMasked = validateEmail(email);
        byte[] fullNameEncrypted = encrypt(fullName);
        byte[] phoneEncrypted = encryptNullable(phone);
        byte[] emailEncrypted = encryptNullable(email);

        CustomerProfileStore.StoredCustomer stored = store.create(
                userId, fullNameEncrypted, phoneEncrypted, emailEncrypted, status);
        return view(stored, fullName, phoneMasked, emailMasked);
    }

    @Transactional(readOnly = true)
    public OwnerCustomerPiiView getForOwner(long customerId, long actorUserId) {
        CustomerProfileStore.StoredCustomer stored = requireOwned(customerId, actorUserId);
        DecryptedPii pii = decrypt(stored);
        return view(stored, pii.fullName(), pii.phoneMasked(), pii.emailMasked());
    }

    /**
     * Staff projection của `API-ADM-01` (BE-CUS-IMP-06): decrypt trong scope Customer rồi mask
     * TẤT CẢ PII (fullName theo DEC-CUS-005/CUS-OI-02; phone/email như owner). Không kiểm
     * ownership theo {@code actorUserId} — caller ({@code StaffCustomerDetailService}) chịu trách
     * nhiệm xác thực staff permission/scope trước khi gọi. Row `INACTIVE` vẫn trả hồ sơ kèm
     * status (07 §8.2.4; eligibility CUS-OI-06 chưa được duyệt, không tự thêm điều kiện).
     */
    @Transactional(readOnly = true)
    public StaffCustomerPiiView getForStaff(long customerId) {
        requirePositive(customerId);
        CustomerProfileStore.StoredCustomer stored = store.findById(customerId)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        DecryptedPii pii = decrypt(stored);
        String fullNameMasked;
        try {
            fullNameMasked = protection.maskFullName(pii.fullName());
        } catch (RuntimeException failure) {
            // Fail-closed: masking sai phải chặn response, không bao giờ trả tên nguyên văn.
            throw cryptoFailure();
        }
        return new StaffCustomerPiiView(stored.customerId(), stored.userId(), fullNameMasked,
                pii.phoneMasked(), pii.emailMasked(), stored.status(), stored.createdAt(),
                stored.updatedAt());
    }

    @Transactional
    public OwnerCustomerPiiView replaceFullNameForOwner(long customerId, long actorUserId, String fullName) {
        requireFullName(fullName);
        CustomerProfileStore.StoredCustomer current = requireOwned(customerId, actorUserId);
        DecryptedPii pii = decrypt(current);
        byte[] replacement = encrypt(fullName);
        CustomerProfileStore.StoredCustomer updated = store.replaceEncryptedPii(
                current.customerId(), replacement, current.phoneEncrypted(), current.emailEncrypted());
        return view(updated, fullName, pii.phoneMasked(), pii.emailMasked());
    }

    @Transactional
    public OwnerCustomerPiiView replacePhoneForOwner(long customerId, long actorUserId, String phone) {
        String phoneMasked = validatePhone(phone);
        CustomerProfileStore.StoredCustomer current = requireOwned(customerId, actorUserId);
        DecryptedPii pii = decrypt(current);
        byte[] replacement = encryptNullable(phone);
        CustomerProfileStore.StoredCustomer updated = store.replaceEncryptedPii(
                current.customerId(), current.fullNameEncrypted(), replacement, current.emailEncrypted());
        return view(updated, pii.fullName(), phoneMasked, pii.emailMasked());
    }

    @Transactional
    public OwnerCustomerPiiView replaceEmailForOwner(long customerId, long actorUserId, String email) {
        String emailMasked = validateEmail(email);
        CustomerProfileStore.StoredCustomer current = requireOwned(customerId, actorUserId);
        DecryptedPii pii = decrypt(current);
        byte[] replacement = encryptNullable(email);
        CustomerProfileStore.StoredCustomer updated = store.replaceEncryptedPii(
                current.customerId(), current.fullNameEncrypted(), current.phoneEncrypted(), replacement);
        return view(updated, pii.fullName(), pii.phoneMasked(), emailMasked);
    }

    private CustomerProfileStore.StoredCustomer requireOwned(long customerId, long actorUserId) {
        requirePositive(customerId);
        requirePositive(actorUserId);
        CustomerProfileStore.StoredCustomer stored = store.findById(customerId)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        if (stored.userId() != actorUserId) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        return stored;
    }

    private DecryptedPii decrypt(CustomerProfileStore.StoredCustomer stored) {
        try {
            String fullName = protection.decrypt(stored.fullNameEncrypted());
            String phone = decryptNullable(stored.phoneEncrypted());
            String email = decryptNullable(stored.emailEncrypted());
            return new DecryptedPii(
                    fullName,
                    phone == null ? null : protection.maskPhone(phone),
                    email == null ? null : protection.maskEmail(email));
        } catch (RuntimeException failure) {
            throw cryptoFailure();
        }
    }

    private String decryptNullable(byte[] ciphertext) {
        return ciphertext == null ? null : protection.decrypt(ciphertext);
    }

    private byte[] encrypt(String plaintext) {
        try {
            return protection.encrypt(plaintext);
        } catch (RuntimeException failure) {
            throw cryptoFailure();
        }
    }

    private byte[] encryptNullable(String plaintext) {
        return plaintext == null ? null : encrypt(plaintext);
    }

    private String validatePhone(String phone) {
        if (phone == null) {
            return null;
        }
        try {
            return protection.maskPhone(phone);
        } catch (RuntimeException invalid) {
            throw validationFailure();
        }
    }

    private String validateEmail(String email) {
        if (email == null) {
            return null;
        }
        try {
            return protection.maskEmail(email);
        } catch (RuntimeException invalid) {
            throw validationFailure();
        }
    }

    private static void requireFullName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            throw validationFailure();
        }
    }

    private static void requirePositive(long id) {
        if (id <= 0) {
            throw validationFailure();
        }
    }

    private static BankingBusinessException validationFailure() {
        return new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
    }

    private static BankingBusinessException cryptoFailure() {
        return new BankingBusinessException(ErrorCode.INTERNAL_ERROR);
    }

    private static OwnerCustomerPiiView view(
            CustomerProfileStore.StoredCustomer stored,
            String fullName,
            String phoneMasked,
            String emailMasked) {
        return new OwnerCustomerPiiView(
                stored.customerId(),
                stored.userId(),
                fullName,
                phoneMasked,
                emailMasked,
                stored.status(),
                stored.createdAt(),
                stored.updatedAt());
    }

    private record DecryptedPii(String fullName, String phoneMasked, String emailMasked) {
    }
}
