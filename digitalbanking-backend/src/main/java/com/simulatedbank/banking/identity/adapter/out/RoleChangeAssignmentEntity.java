package com.simulatedbank.banking.identity.adapter.out;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "user_role", schema = "banking")
public class RoleChangeAssignmentEntity {
    @EmbeddedId
    private Key id;

    @Column(name = "assigned_by")
    private Long assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    protected RoleChangeAssignmentEntity() {
    }

    RoleChangeAssignmentEntity(Key id, long assignedBy, Instant assignedAt) {
        this.id = id;
        this.assignedBy = assignedBy;
        this.assignedAt = assignedAt;
    }

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "user_id")
        private Long userId;

        @Column(name = "role_id")
        private Long roleId;

        protected Key() {
        }

        Key(long userId, long roleId) {
            this.userId = userId;
            this.roleId = roleId;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || (other instanceof Key key
                    && Objects.equals(userId, key.userId) && Objects.equals(roleId, key.roleId));
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(userId) + Objects.hashCode(roleId);
        }
    }
}
