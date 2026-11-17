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
@Table(name = "user_privilege", schema = "banking")
public class RoleChangePrivilegeEntity {
    @EmbeddedId
    private Key id;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    protected RoleChangePrivilegeEntity() {
    }

    RoleChangePrivilegeEntity(Key id, long grantedBy, Instant grantedAt) {
        this.id = id;
        grant(grantedBy, grantedAt);
    }

    boolean isActive() {
        return "ACTIVE".equals(status);
    }

    void grant(long actor, Instant at) {
        status = "ACTIVE";
        grantedBy = actor;
        grantedAt = at;
    }

    void revoke() {
        // Schema retains the last grant metadata; Admin's final audit records the revocation actor/time.
        status = "REVOKED";
    }

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "user_id")
        private Long userId;

        @Column(name = "privilege_code", length = 32)
        private String privilegeCode;

        protected Key() {
        }

        Key(long userId, String privilegeCode) {
            this.userId = userId;
            this.privilegeCode = privilegeCode;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || (other instanceof Key key
                    && Objects.equals(userId, key.userId) && Objects.equals(privilegeCode, key.privilegeCode));
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(userId) + Objects.hashCode(privilegeCode);
        }
    }
}
