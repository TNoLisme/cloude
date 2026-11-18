package com.simulatedbank.banking.customer.adapter.out;

import com.simulatedbank.banking.customer.domain.CustomerStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Internal persistence mapping for {@code banking.customer}; never expose this entity through module APIs. */
@Entity
@Table(name = "customer", schema = "banking")
public class CustomerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private Long userId;

    @Column(name = "full_name_enc", nullable = false)
    private byte[] fullNameEncrypted;

    @Column(name = "phone_enc")
    private byte[] phoneEncrypted;

    @Column(name = "email_enc")
    private byte[] emailEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CustomerStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerEntity() {
    }

    CustomerEntity(
            long userId,
            byte[] fullNameEncrypted,
            byte[] phoneEncrypted,
            byte[] emailEncrypted,
            CustomerStatus status
    ) {
        this.userId = userId;
        replaceEncryptedPii(fullNameEncrypted, phoneEncrypted, emailEncrypted);
        this.status = Objects.requireNonNull(status, "status is required");
    }

    void replaceEncryptedPii(byte[] fullNameEncrypted, byte[] phoneEncrypted, byte[] emailEncrypted) {
        this.fullNameEncrypted = copyNullable(fullNameEncrypted);
        this.phoneEncrypted = copyNullable(phoneEncrypted);
        this.emailEncrypted = copyNullable(emailEncrypted);
    }

    Long getCustomerId() {
        return customerId;
    }

    Long getUserId() {
        return userId;
    }

    byte[] getFullNameEncrypted() {
        return fullNameEncrypted.clone();
    }

    byte[] getPhoneEncrypted() {
        return copyNullable(phoneEncrypted);
    }

    byte[] getEmailEncrypted() {
        return copyNullable(emailEncrypted);
    }

    CustomerStatus getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    private static byte[] copyNullable(byte[] value) {
        return value == null ? null : value.clone();
    }

    @Override
    public String toString() {
        return "CustomerEntity[redacted]";
    }
}
