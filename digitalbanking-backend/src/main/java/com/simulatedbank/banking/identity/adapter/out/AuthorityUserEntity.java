package com.simulatedbank.banking.identity.adapter.out;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.Set;
import org.hibernate.annotations.Immutable;

/** Read-only RBAC mappings; assignment writes belong to the approved role-change command. */
@Entity
@Immutable
@Table(name = "app_user", schema = "banking")
public class AuthorityUserEntity {
    @Id
    @Column(name = "user_id")
    private Long id;
    @Column(name = "user_type")
    private String userType;
    @Column(name = "status")
    private String status;
    @ManyToMany
    @JoinTable(name = "user_role", schema = "banking", joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<AuthorityRoleEntity> roles;
    @ElementCollection
    @CollectionTable(name = "user_privilege", schema = "banking", joinColumns = @JoinColumn(name = "user_id"))
    private Set<AuthorityPrivilege> privileges;

    protected AuthorityUserEntity() { }
}

@Entity
@Immutable
@Table(name = "role", schema = "banking")
class AuthorityRoleEntity {
    @Id
    @Column(name = "role_id")
    private Long id;
    @Column(name = "role_code")
    private String code;
    @Column(name = "status")
    private String status;
    @ManyToMany
    @JoinTable(name = "role_permission", schema = "banking", joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<AuthorityPermissionEntity> permissions;

    protected AuthorityRoleEntity() { }
}

@Entity
@Immutable
@Table(name = "permission", schema = "banking")
class AuthorityPermissionEntity {
    @Id
    @Column(name = "permission_id")
    private Long id;
    @Column(name = "permission_code")
    private String code;
    @Column(name = "status")
    private String status;

    protected AuthorityPermissionEntity() { }
}

@Embeddable
class AuthorityPrivilege {
    @Column(name = "privilege_code")
    private String code;
    @Column(name = "status")
    private String status;

    protected AuthorityPrivilege() { }
}
