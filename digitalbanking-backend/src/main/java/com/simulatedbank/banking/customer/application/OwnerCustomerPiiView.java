package com.simulatedbank.banking.customer.application;

import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.time.Instant;
import java.util.Objects;

/** Owner-authorized profile projection: full name is allowed; contacts are always masked. */
public final class OwnerCustomerPiiView {
    private final long customerId;
    private final long userId;
    private final String fullName;
    private final String phoneMasked;
    private final String emailMasked;
    private final CustomerStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    public OwnerCustomerPiiView(
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
        return "OwnerCustomerPiiView[redacted]";
    }
}
