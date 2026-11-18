package com.simulatedbank.banking.customer.application;

import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Persistence port carrying ciphertext only; plaintext PII must never cross this boundary. */
public interface CustomerProfileStore {

    StoredCustomer create(
            long userId,
            byte[] fullNameEncrypted,
            byte[] phoneEncrypted,
            byte[] emailEncrypted,
            CustomerStatus status);

    Optional<StoredCustomer> findById(long customerId);

    StoredCustomer replaceEncryptedPii(
            long customerId,
            byte[] fullNameEncrypted,
            byte[] phoneEncrypted,
            byte[] emailEncrypted);

    final class StoredCustomer {
        private final long customerId;
        private final long userId;
        private final byte[] fullNameEncrypted;
        private final byte[] phoneEncrypted;
        private final byte[] emailEncrypted;
        private final CustomerStatus status;
        private final Instant createdAt;
        private final Instant updatedAt;

        public StoredCustomer(
                long customerId,
                long userId,
                byte[] fullNameEncrypted,
                byte[] phoneEncrypted,
                byte[] emailEncrypted,
                CustomerStatus status,
                Instant createdAt,
                Instant updatedAt) {
            this.customerId = customerId;
            this.userId = userId;
            this.fullNameEncrypted = copyRequired(fullNameEncrypted);
            this.phoneEncrypted = copyNullable(phoneEncrypted);
            this.emailEncrypted = copyNullable(emailEncrypted);
            this.status = Objects.requireNonNull(status, "status is required");
            this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
            this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
        }

        public long customerId() {
            return customerId;
        }

        public long userId() {
            return userId;
        }

        public byte[] fullNameEncrypted() {
            return fullNameEncrypted.clone();
        }

        public byte[] phoneEncrypted() {
            return copyNullable(phoneEncrypted);
        }

        public byte[] emailEncrypted() {
            return copyNullable(emailEncrypted);
        }

        public CustomerStatus status() {
            return status;
        }

        public Instant createdAt() {
            return createdAt;
        }

        public Instant updatedAt() {
            return updatedAt;
        }

        @Override
        public String toString() {
            return "StoredCustomer[redacted]";
        }

        private static byte[] copyRequired(byte[] value) {
            return Objects.requireNonNull(value, "fullNameEncrypted is required").clone();
        }

        private static byte[] copyNullable(byte[] value) {
            return value == null ? null : value.clone();
        }
    }
}
