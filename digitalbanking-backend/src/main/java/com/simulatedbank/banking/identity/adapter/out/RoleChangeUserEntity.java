package com.simulatedbank.banking.identity.adapter.out;

import com.simulatedbank.banking.identity.application.port.RoleChangeStore.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Minimal identity-owned projection; only role version and update time are writable. */
@Entity
@Table(name = "app_user", schema = "banking")
public class RoleChangeUserEntity {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "user_type", nullable = false, length = 16, updatable = false)
    private String userType;

    @Column(name = "status", nullable = false, length = 16, updatable = false)
    private String status;

    @Column(name = "role_assignment_version", nullable = false)
    private long roleAssignmentVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RoleChangeUserEntity() {
    }

    User snapshot() {
        return new User(userId, userType, status, roleAssignmentVersion);
    }

    void advanceVersion(long expectedVersion, Instant changedAt) {
        if (roleAssignmentVersion != expectedVersion || expectedVersion == Long.MAX_VALUE) {
            throw new IllegalStateException("Locked role assignment version changed");
        }
        roleAssignmentVersion++;
        updatedAt = changedAt;
    }
}
