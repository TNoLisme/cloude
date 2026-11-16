package com.simulatedbank.banking.identity.adapter.out;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Credential projection of app_user; no user provisioning or ownership of another module's data. */
@Entity
@Table(name = "app_user", schema = "banking")
public class UserCredentialEntity {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "status", nullable = false, length = 16, updatable = false)
    private String status;

    @Column(name = "locked_until", updatable = false)
    private Instant lockedUntil;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserCredentialEntity() {
    }

    long getUserId() { return userId; }
    String getPasswordHash() { return passwordHash; }
    String getStatus() { return status; }
    Instant getLockedUntil() { return lockedUntil; }

    void replacePassword(String hash, Instant changedAt) {
        passwordHash = hash;
        passwordChangedAt = changedAt;
        updatedAt = changedAt;
    }

    @Override
    public String toString() { return "UserCredentialEntity[redacted]"; }
}
