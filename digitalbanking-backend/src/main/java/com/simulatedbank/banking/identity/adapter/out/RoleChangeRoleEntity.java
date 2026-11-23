package com.simulatedbank.banking.identity.adapter.out;

import com.simulatedbank.banking.identity.application.port.RoleChangeStore.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "role", schema = "banking")
public class RoleChangeRoleEntity {
    @Id
    @Column(name = "role_id")
    private Long roleId;

    @Column(name = "role_code", nullable = false, length = 32, updatable = false)
    private String roleCode;

    @Column(name = "status", nullable = false, length = 16, updatable = false)
    private String status;

    protected RoleChangeRoleEntity() {
    }

    Role snapshot() {
        return new Role(roleId, roleCode, status);
    }
}
