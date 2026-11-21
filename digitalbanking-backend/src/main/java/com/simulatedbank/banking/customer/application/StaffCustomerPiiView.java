package com.simulatedbank.banking.customer.application;

import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.time.Instant;
import java.util.Objects;

/**
 * Staff-authorized profile projection of `API-ADM-01` (BE-CUS-IMP-06): TẤT CẢ field PII đã mask —
 * {@code fullName} masked theo DEC-CUS-005 (CUS-OI-02 chờ owner sign-off), phone/email masked
 * như owner projection (07 §8.2.4: "Không trả full PII"). Không chứa bất kỳ plaintext chưa mask
 * nào; {@code toString()} bị redact để PII không đi vào log qua ánh xạ vô tình.
 */
public final class StaffCustomerPiiView {
    private final long customerId;
    private final long userId;
    private final String fullName;
    private final String phoneMasked;
    private final String emailMasked;
    private final CustomerStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    public StaffCustomerPiiView(
            long customerId,
            long userId,
            String fullName,
            String phoneMasked,
            String emailMasked,
            CustomerStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this.customerId = customerId;
        this.userId = userId;
        this.fullName = Objects.requireNonNull(fullName, "fullName is required");
        this.phoneMasked = phoneMasked;
        this.emailMasked = emailMasked;
        this.status = Objects.requireNonNull(status, "status is required");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
    }

    public long getCustomerId() {
        return customerId;
    }

    public long getUserId() {
        return userId;
    }

    /** Giá trị ĐÃ MASK (chỉ ký tự đầu mỗi token); không bao giờ là fullName nguyên văn. */
    public String getFullName() {
        return fullName;
    }

    public String getPhoneMasked() {
        return phoneMasked;
    }

    public String getEmailMasked() {
        return emailMasked;
    }

    public CustomerStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "StaffCustomerPiiView[redacted]";
    }
}
