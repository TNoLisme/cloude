package com.simulatedbank.banking.identity.adapter.out;

import com.simulatedbank.banking.identity.application.port.RoleChangeStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JPA-only writes. The app_user locks serialize all approved changes, including absent assignments. */
@Repository
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
@Transactional(propagation = Propagation.MANDATORY)
public class JpaRoleChangeStore implements RoleChangeStore {
    private final RoleChangeUserJpaRepository users;
    private final RoleChangeRoleJpaRepository roles;
    private final EntityManager entityManager;

    public JpaRoleChangeStore(RoleChangeUserJpaRepository users, RoleChangeRoleJpaRepository roles,
            EntityManager entityManager) {
        this.users = users;
        this.roles = roles;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<User> findUser(long userId) {
        return entityManager.createQuery("""
                select u.userId, u.userType, u.status, u.roleAssignmentVersion
                  from RoleChangeUserEntity u where u.userId = :userId
                """, Object[].class)
                .setParameter("userId", userId)
                .getResultList().stream()
                .findFirst()
                .map(row -> new User((Long) row[0], (String) row[1], (String) row[2], (Long) row[3]));
    }

    @Override
    public Optional<User> lockUser(long userId) {
        return users.lockById(userId).map(user -> {
            // A lock query may return a stale object already in the caller's persistence context.
            entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
            return user.snapshot();
        });
    }

    @Override
    public Optional<Role> lockRole(String roleCode) {
        return roles.lockByCode(roleCode).map(role -> {
            entityManager.refresh(role, LockModeType.PESSIMISTIC_READ);
            return role.snapshot();
        });
    }

    @Override
    public boolean assignRole(long userId, long roleId, long assignedBy, Instant assignedAt) {
        var key = new RoleChangeAssignmentEntity.Key(userId, roleId);
        if (findAssignment(key).isPresent()) {
            return false;
        }
        entityManager.persist(new RoleChangeAssignmentEntity(key, assignedBy, assignedAt));
        return true;
    }

    @Override
    public boolean revokeRole(long userId, long roleId) {
        var assignment = findAssignment(new RoleChangeAssignmentEntity.Key(userId, roleId));
        if (assignment.isEmpty()) {
            return false;
        }
        entityManager.remove(assignment.get());
        return true;
    }

    @Override
    public boolean grantPrivilege(long userId, String privilegeCode, long grantedBy, Instant grantedAt) {
        var key = new RoleChangePrivilegeEntity.Key(userId, privilegeCode);
        var privilege = findPrivilege(key);
        if (privilege.isEmpty()) {
            entityManager.persist(new RoleChangePrivilegeEntity(key, grantedBy, grantedAt));
        } else {
            if (privilege.get().isActive()) {
                return false;
            }
            privilege.get().grant(grantedBy, grantedAt);
        }
        return true;
    }

    @Override
    public boolean revokePrivilege(long userId, String privilegeCode) {
        var privilege = findPrivilege(new RoleChangePrivilegeEntity.Key(userId, privilegeCode));
        if (privilege.isEmpty() || !privilege.get().isActive()) {
            return false;
        }
        privilege.get().revoke();
        return true;
    }

    @Override
    public void advanceVersion(long userId, long expectedVersion, Instant changedAt) {
        // This exact entity was locked and refreshed before any mutation, so no second read is needed.
        var user = entityManager.find(RoleChangeUserEntity.class, userId);
        user.advanceVersion(expectedVersion, changedAt);
    }

    @Override
    public int revokeActiveSessions(long userId, Instant loggedOutAt) {
        // Flush identity writes before Admin takes Audit's final chain lock; commit-time flush is too late.
        entityManager.flush();
        return entityManager.createQuery("""
                update RoleChangeSessionEntity s
                   set s.status = 'LOGGED_OUT', s.loggedOutAt = :loggedOutAt
                 where s.userId = :userId and s.status = 'ACTIVE'
                """)
                .setParameter("loggedOutAt", loggedOutAt)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    private Optional<RoleChangeAssignmentEntity> findAssignment(RoleChangeAssignmentEntity.Key key) {
        // Query the database even if a stale entity with this key is already managed by the caller.
        return entityManager.createQuery("select a from RoleChangeAssignmentEntity a where a.id = :id",
                        RoleChangeAssignmentEntity.class)
                .setParameter("id", key)
                .getResultList().stream()
                .findFirst();
    }

    private Optional<RoleChangePrivilegeEntity> findPrivilege(RoleChangePrivilegeEntity.Key key) {
        var privilege = entityManager.createQuery("select p from RoleChangePrivilegeEntity p where p.id = :id",
                        RoleChangePrivilegeEntity.class)
                .setParameter("id", key)
                .getResultList().stream()
                .findFirst();
        privilege.ifPresent(entityManager::refresh);
        return privilege;
    }
}
