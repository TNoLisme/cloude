package com.simulatedbank.banking.identity.adapter.out;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "password_history", schema = "banking")
public class PasswordHistoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private long userId;

    @Column(name = "password_hash", nullable = false, length = 255, updatable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PasswordHistoryEntity() {
    }

    PasswordHistoryEntity(long userId, String passwordHash, Instant createdAt) {
        this.userId = userId;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    @Override
    public String toString() { return "PasswordHistoryEntity[redacted]"; }
}
