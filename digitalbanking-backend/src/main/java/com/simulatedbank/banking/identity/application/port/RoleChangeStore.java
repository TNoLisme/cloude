package com.simulatedbank.banking.identity.application.port;

import java.time.Instant;
import java.util.Optional;

/** Identity persistence only. Mutations require participating user locks in ascending ID order. */
public interface RoleChangeStore {
    record User(long userId, String userType, String status, long roleAssignmentVersion) {
    }

    record Role(long roleId, String roleCode, String status) {
    }

    /** Fresh scalar projection for proposal-time reads; never obtains a write lock. */
    Optional<User> findUser(long userId);

    /** Acquire PESSIMISTIC_WRITE and refresh, including objects already managed by the caller. */
    Optional<User> lockUser(long userId);

    /** Hold the catalog row's read lock until the approval transaction completes. */
    Optional<Role> lockRole(String roleCode);

    boolean assignRole(long userId, long roleId, long assignedBy, Instant assignedAt);

    boolean revokeRole(long userId, long roleId);

    boolean grantPrivilege(long userId, String privilegeCode, long grantedBy, Instant grantedAt);

    boolean revokePrivilege(long userId, String privilegeCode);

    void advanceVersion(long userId, long expectedVersion, Instant changedAt);

    int revokeActiveSessions(long userId, Instant loggedOutAt);
}
