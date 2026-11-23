package com.simulatedbank.banking.identity.application;

import com.simulatedbank.banking.common.config.SessionAuthenticator.Session;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.IdentityRoleCommand;
import com.simulatedbank.banking.identity.application.port.RoleChangeStore;
import com.simulatedbank.banking.identity.application.port.RoleChangeStore.User;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** No approval/audit writes: Admin must commit its state transition and final C2 audit with this command. */
@Service
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class IdentityRoleCommandService implements IdentityRoleCommand {
    private static final Set<String> ROLE_CODES = Set.of("CUSTOMER", "OPERATIONS", "ADMIN", "AUDITOR");

    private final RoleChangeStore store;
    private final AuthorityReader authorities;
    private final Clock clock;

    public IdentityRoleCommandService(RoleChangeStore store, AuthorityReader authorities, Clock clock) {
        this.store = store;
        this.authorities = authorities;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public long getRoleAssignmentVersion(long targetUserId) {
        Session session = requireSession();
        if (targetUserId <= 0) {
            throw failure(ErrorCode.VALIDATION_ERROR);
        }
        long requesterId;
        try {
            requesterId = Long.parseLong(session.userId());
        } catch (NumberFormatException exception) {
            throw failure(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        User requester = store.findUser(requesterId)
                .orElseThrow(() -> failure(ErrorCode.AUTH_FORBIDDEN));
        requireAdministrator(requester, false);
        User target = store.findUser(targetUserId).orElseThrow(() -> failure(ErrorCode.USER_NOT_FOUND));
        if (!"ACTIVE".equals(target.status())) {
            throw failure(ErrorCode.ROLE_CONFLICT);
        }
        return target.roleAssignmentVersion();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RoleChangeResult applyApprovedRoleChange(ApprovedRoleChange command) {
        Session session = requireSession();
        validate(command);
        if (!session.userId().equals(Long.toString(command.checkerUserId()))) {
            throw failure(ErrorCode.AUTH_FORBIDDEN);
        }
        switch (command.approvalState()) {
            case APPLIED, REJECTED, CANCELLED -> throw failure(ErrorCode.APPROVAL_ALREADY_DECIDED);
            case PENDING -> throw failure(ErrorCode.ROLE_CONFLICT);
            case APPROVED -> { }
        }
        if (command.makerUserId() == command.checkerUserId()
                || command.targetUserId() == command.makerUserId()
                || command.targetUserId() == command.checkerUserId()) {
            throw failure(ErrorCode.ROLE_CONFLICT);
        }

        long[] ids = {command.makerUserId(), command.checkerUserId(), command.targetUserId()};
        Arrays.sort(ids);
        User maker = null;
        User checker = null;
        User target = null;
        for (long id : ids) {
            User user = store.lockUser(id).orElseThrow(() -> failure(ErrorCode.USER_NOT_FOUND));
            if (id == command.makerUserId()) {
                maker = user;
            } else if (id == command.checkerUserId()) {
                checker = user;
            } else {
                target = user;
            }
        }
        requireAdministrator(maker, false);
        requireAdministrator(checker, true);
        if (!"ACTIVE".equals(target.status()) || target.roleAssignmentVersion() != command.expectedVersion()
                || command.expectedVersion() == Long.MAX_VALUE) {
            throw failure(ErrorCode.ROLE_CONFLICT);
        }

        var changedAt = clock.instant();
        boolean changed;
        switch (command.operation()) {
            case ASSIGN_ROLE, REVOKE_ROLE -> {
                if (!ROLE_CODES.contains(command.roleCode())) {
                    throw failure(ErrorCode.ROLE_NOT_FOUND);
                }
                var role = store.lockRole(command.roleCode())
                        .filter(value -> "ACTIVE".equals(value.status()))
                        .orElseThrow(() -> failure(ErrorCode.ROLE_NOT_FOUND));
                String requiredType = "CUSTOMER".equals(role.roleCode()) ? "CUSTOMER" : "STAFF";
                if (!requiredType.equals(target.userType())) {
                    throw failure(ErrorCode.ROLE_CONFLICT);
                }
                changed = command.operation() == Operation.ASSIGN_ROLE
                        ? store.assignRole(target.userId(), role.roleId(), command.checkerUserId(), changedAt)
                        : store.revokeRole(target.userId(), role.roleId());
            }
            case GRANT_PRIVILEGE, REVOKE_PRIVILEGE -> {
                if (!"STAFF".equals(target.userType())) {
                    throw failure(ErrorCode.ROLE_CONFLICT);
                }
                changed = command.operation() == Operation.GRANT_PRIVILEGE
                        ? store.grantPrivilege(target.userId(), command.privilegeCode(),
                                command.checkerUserId(), changedAt)
                        : store.revokePrivilege(target.userId(), command.privilegeCode());
            }
            default -> throw new IllegalStateException("Unsupported approved operation");
        }
        if (!changed) {
            throw failure(ErrorCode.ROLE_CONFLICT);
        }
        store.advanceVersion(target.userId(), command.expectedVersion(), changedAt);
        int revoked = store.revokeActiveSessions(target.userId(), changedAt);
        return new RoleChangeResult(command.approvalId(), target.userId(), command.operation(),
                command.roleCode(), command.privilegeCode(), command.expectedVersion(),
                command.expectedVersion() + 1, revoked, command.correlationId(), command.requestId());
    }

    private void requireAdministrator(User user, boolean checker) {
        if (!"STAFF".equals(user.userType()) || !"ACTIVE".equals(user.status())) {
            throw failure(ErrorCode.AUTH_FORBIDDEN);
        }
        // Never authorize from session authorities: database permissions may have changed since login.
        Set<String> current = authorities.loadAuthorities(user.userId());
        if (!current.contains("ROLE_ADMIN") || !current.contains("ROLE_MANAGE")
                || (checker && !current.contains("CHECKER"))) {
            throw failure(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static Session requireSession() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Session session)) {
            throw failure(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return session;
    }

    private static void validate(ApprovedRoleChange command) {
        if (command == null || command.approvalId() <= 0 || command.makerUserId() <= 0
                || command.checkerUserId() <= 0 || command.targetUserId() <= 0
                || command.approvalState() == null || command.operation() == null
                || command.expectedVersion() < 0 || !boundedText(command.correlationId(), 64)
                || (command.requestId() != null && !boundedText(command.requestId(), 64))) {
            throw failure(ErrorCode.VALIDATION_ERROR);
        }
        switch (command.operation()) {
            case ASSIGN_ROLE, REVOKE_ROLE -> {
                if (!boundedText(command.roleCode(), 32) || command.privilegeCode() != null) {
                    throw failure(ErrorCode.VALIDATION_ERROR);
                }
            }
            case GRANT_PRIVILEGE, REVOKE_PRIVILEGE -> {
                if (command.roleCode() != null || !"CHECKER".equals(command.privilegeCode())) {
                    throw failure(ErrorCode.VALIDATION_ERROR);
                }
            }
        }
    }

    private static boolean boundedText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength;
    }

    private static BankingBusinessException failure(ErrorCode code) {
        return new BankingBusinessException(code);
    }
}
