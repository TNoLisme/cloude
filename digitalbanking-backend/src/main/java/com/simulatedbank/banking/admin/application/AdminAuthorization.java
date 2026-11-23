package com.simulatedbank.banking.admin.application;

import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Service-level authorization re-check for Admin approval operations (CON-01 §8, BE-ADM-SEC-01 dependency).
 *
 * <p>The actor is always the trusted {@link SessionAuthenticator.Session} principal installed by
 * {@code SessionAuthenticationFilter}; a {@code checkerId}/{@code makerId} in a request body/header is never
 * trusted. The {@code CHECKER} privilege is required for decisions and for issuing a decision challenge; a
 * missing/expired session fails closed with {@code AUTH_SESSION_EXPIRED}, a missing permission with
 * {@code AUTH_FORBIDDEN} (both catalog codes of 07 §4.3). HTTP-level denial auditing stays with the shared
 * security chain ({@code SecurityAccessDeniedAuditor}); Identity re-checks the privilege from the database when
 * it applies a role change (design/05 §5.3, DEC-ID-011).</p>
 */
@Component
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
public class AdminAuthorization {

    static final String CHECKER_AUTHORITY = "CHECKER";
    static final String ROLE_MANAGE_AUTHORITY = "ROLE_MANAGE";
    static final String LIMIT_MAKER_AUTHORITY = "LIMIT_MAKER";
    static final String LIMIT_MANAGE_AUTHORITY = "LIMIT_MANAGE";
    static final String OPERATIONS_ROLE = "ROLE_OPERATIONS";
    static final String ADMIN_ROLE = "ROLE_ADMIN";

    /** Authenticated principal snapshot; never null and never a raw token. */
    public SessionAuthenticator.Session requireSession() {
        return session(requireAuthentication());
    }

    /**
     * Reads the security context once per check so the principal and the authorities always come from the
     * same authenticated snapshot; anything else (anonymous, non-session principal, unauthenticated token) is
     * an authentication failure, never a permission decision.
     */
    private static Authentication requireAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionAuthenticator.Session)) {
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return authentication;
    }

    private static SessionAuthenticator.Session session(Authentication authentication) {
        return (SessionAuthenticator.Session) authentication.getPrincipal();
    }

    /** Authenticated user id of the caller; the only accepted source of {@code checkerId}. */
    public long requireChecker() {
        return requireAuthority(CHECKER_AUTHORITY);
    }

    /**
     * Role snapshot for the audit trail (design/01 §5.6): the granted top-level role, never a permission code
     * and never a display name/PII. {@code STAFF} is the bounded fallback for a staff principal without one of
     * the catalogued roles; the value is validated against the audit allowlist before it is persisted.
     */
    public String actorRole() {
        Set<String> authorities = authoritiesOf(requireAuthentication());
        if (authorities.contains(ADMIN_ROLE)) {
            return "ADMIN";
        }
        if (authorities.contains(OPERATIONS_ROLE)) {
            return "OPERATIONS";
        }
        if (authorities.contains("ROLE_AUDITOR")) {
            return "AUDITOR";
        }
        if (authorities.contains("ROLE_CUSTOMER")) {
            return "CUSTOMER";
        }
        return "STAFF";
    }

    /**
     * {@code API-APR-01/02}: the endpoint gate is the {@code OPERATIONS} or {@code ADMIN} role (07 §8.8.3,
     * design/04 §5.1). Re-checked from the trusted snapshot so the controller cannot rely on URL matching only.
     */
    public long requireApprovalReader() {
        return requireAnyAuthority(OPERATIONS_ROLE, ADMIN_ROLE);
    }

    /**
     * Types the authenticated checker may review: {@code ROLE_CHANGE} needs {@code ROLE_MANAGE};
     * {@code LIMIT_CHANGE} needs {@code LIMIT_MAKER} or {@code LIMIT_MANAGE}. A non-checker (or a checker without
     * the matching permission) gets an empty set: they may only see/decide their own requests.
     */
    public Set<ApprovalRequestType> reviewableRequestTypes() {
        Set<String> authorities = authoritiesOf(requireAuthentication());
        if (!authorities.contains(CHECKER_AUTHORITY)) {
            return Set.of();
        }
        Set<ApprovalRequestType> types = EnumSet.noneOf(ApprovalRequestType.class);
        if (authorities.contains(ROLE_MANAGE_AUTHORITY)) {
            types.add(ApprovalRequestType.ROLE_CHANGE);
        }
        if (authorities.contains(LIMIT_MAKER_AUTHORITY) || authorities.contains(LIMIT_MANAGE_AUTHORITY)) {
            types.add(ApprovalRequestType.LIMIT_CHANGE);
        }
        return Collections.unmodifiableSet(types);
    }

    /**
     * Defense-in-depth re-check of the decision permission for the approval type handled by the endpoint.
     * Mirrors the chain rule ({@code CHECKER} + type permission) using the trusted principal only.
     */
    public void assertCanReview(ApprovalRequestType requestType) {
        Objects.requireNonNull(requestType, "requestType is required");
        if (!reviewableRequestTypes().contains(requestType)) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    /** Requires one exact authority from the trusted snapshot and returns the authenticated actor id. */
    public long requireAuthority(String authority) {
        return requireAnyAuthority(authority);
    }

    /**
     * Requires at least one of the supplied authorities (for example {@code LIMIT_MAKER} or
     * {@code LIMIT_MANAGE}) and returns the authenticated actor id.
     */
    public long requireAnyAuthority(String... authorities) {
        if (authorities == null || authorities.length == 0) {
            throw new IllegalArgumentException("At least one authority is required");
        }
        Authentication authentication = requireAuthentication();
        SessionAuthenticator.Session session = session(authentication);
        Set<String> granted = authoritiesOf(authentication);
        boolean permitted = false;
        for (String authority : authorities) {
            if (granted.contains(authority)) {
                permitted = true;
                break;
            }
        }
        if (!permitted) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        return userId(session);
    }

    private static Set<String> authoritiesOf(Authentication authentication) {
        Set<String> granted = new HashSet<>();
        authentication.getAuthorities().forEach(authority -> granted.add(authority.getAuthority()));
        return granted;
    }

    private static long userId(SessionAuthenticator.Session session) {
        try {
            long userId = Long.parseLong(session.userId());
            if (userId <= 0) {
                throw new NumberFormatException();
            }
            return userId;
        } catch (NumberFormatException invalidSession) {
            // A malformed trusted snapshot is an authentication failure, not a client validation error.
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
    }
}
