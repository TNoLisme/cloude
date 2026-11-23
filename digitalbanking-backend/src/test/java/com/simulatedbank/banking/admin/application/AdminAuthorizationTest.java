package com.simulatedbank.banking.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * BE-ADM-SEC-01: service-layer authorization re-check of {@link AdminAuthorization} without any HTTP layer.
 *
 * <p>The service is invoked directly, exactly like an internal caller or a future job would call it. Only a
 * {@link SessionAuthenticator.Session} installed by the shared session filter may authorize; client-shaped
 * principals (a raw user id, a forged role claim) and inconsistent identity snapshots must fail closed with the
 * catalog authentication/authorization codes, never with a permissive default.</p>
 */
class AdminAuthorizationTest {

    private static final String CSRF_HASH = "a".repeat(64);

    private final AdminAuthorization authorization = new AdminAuthorization();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingAuthenticationIsAlwaysAnAuthenticationFailure() {
        assertCode(() -> authorization.requireSession(), ErrorCode.AUTH_SESSION_EXPIRED);
        assertCode(() -> authorization.requireChecker(), ErrorCode.AUTH_SESSION_EXPIRED);
        assertCode(() -> authorization.requireApprovalReader(), ErrorCode.AUTH_SESSION_EXPIRED);
        assertCode(() -> authorization.requireAuthority("ROLE_MANAGE"), ErrorCode.AUTH_SESSION_EXPIRED);
        assertCode(() -> authorization.reviewableRequestTypes(), ErrorCode.AUTH_SESSION_EXPIRED);
        assertCode(() -> authorization.assertCanReview(ApprovalRequestType.ROLE_CHANGE),
                ErrorCode.AUTH_SESSION_EXPIRED);
    }

    @Test
    void forgedRolesOnANonSessionPrincipalNeverAuthorize() {
        // A client cannot install this, but an internal caller could: the principal must be the trusted
        // session snapshot, otherwise every authority is meaningless.
        authenticate(new UsernamePasswordAuthenticationToken("forged-admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_MANAGE"),
                        new SimpleGrantedAuthority("CHECKER"))));

        assertCode(() -> authorization.requireChecker(), ErrorCode.AUTH_SESSION_EXPIRED);
        assertCode(() -> authorization.requireApprovalReader(), ErrorCode.AUTH_SESSION_EXPIRED);
        assertCode(() -> authorization.requireAuthority("ROLE_MANAGE"), ErrorCode.AUTH_SESSION_EXPIRED);
    }

    @Test
    void unauthenticatedSessionPrincipalFailsClosed() {
        var token = new UsernamePasswordAuthenticationToken(session("7", "ADMIN", "CHECKER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("CHECKER")));
        token.setAuthenticated(false);
        authenticate(token);

        assertCode(() -> authorization.requireChecker(), ErrorCode.AUTH_SESSION_EXPIRED);
    }

    @Test
    void customerAuthoritiesNeverReachAnAdminOperation() {
        authenticate(sessionAuthentication(session("11", "CUSTOMER", "ACCOUNT_READ_SELF", "TRANSFER_CREATE")));

        assertCode(() -> authorization.requireChecker(), ErrorCode.AUTH_FORBIDDEN);
        assertCode(() -> authorization.requireApprovalReader(), ErrorCode.AUTH_FORBIDDEN);
        assertCode(() -> authorization.requireAnyAuthority("ROLE_MANAGE", "LIMIT_MAKER"),
                ErrorCode.AUTH_FORBIDDEN);
        assertCode(() -> authorization.assertCanReview(ApprovalRequestType.ROLE_CHANGE),
                ErrorCode.AUTH_FORBIDDEN);
        assertThat(authorization.reviewableRequestTypes()).isEmpty();
    }

    @Test
    void aCheckerOnlyReviewsTheRequestTypesTheyHoldThePermissionFor() {
        authenticate(sessionAuthentication(session("21", "OPERATIONS", "CHECKER", "LIMIT_MAKER")));

        assertThat(authorization.reviewableRequestTypes())
                .containsExactly(ApprovalRequestType.LIMIT_CHANGE);
        assertThat(authorization.requireChecker()).isEqualTo(21L);
        authorization.assertCanReview(ApprovalRequestType.LIMIT_CHANGE);
        assertCode(() -> authorization.assertCanReview(ApprovalRequestType.ROLE_CHANGE),
                ErrorCode.AUTH_FORBIDDEN);

        SecurityContextHolder.clearContext();
        authenticate(sessionAuthentication(session("22", "ADMIN", "CHECKER", "ROLE_MANAGE", "LIMIT_MANAGE")));

        assertThat(authorization.reviewableRequestTypes()).containsExactlyInAnyOrder(
                ApprovalRequestType.ROLE_CHANGE, ApprovalRequestType.LIMIT_CHANGE);
    }

    @Test
    void aCheckerPrivilegeWithoutAPermissionIsNotAReviewer() {
        authenticate(sessionAuthentication(session("23", "OPERATIONS", "CHECKER")));

        assertThat(authorization.reviewableRequestTypes()).isEmpty();
        assertCode(() -> authorization.assertCanReview(ApprovalRequestType.ROLE_CHANGE),
                ErrorCode.AUTH_FORBIDDEN);
        assertThat(authorization.requireChecker()).isEqualTo(23L);
    }

    @Test
    void anOverflowingTrustedUserIdIsRejectedAsAnAuthenticationFailure() {
        // The snapshot pattern allows 19 digits, but only values within Long range are valid actor ids.
        authenticate(sessionAuthentication(session("9999999999999999999", "ROLE_ADMIN")));

        assertCode(() -> authorization.requireApprovalReader(), ErrorCode.AUTH_SESSION_EXPIRED);
    }

    @Test
    void anEmptyAuthorityRequestIsAProgrammingError() {
        authenticate(sessionAuthentication(session("31", "ROLE_ADMIN")));

        assertThatThrownBy(() -> authorization.requireAnyAuthority())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authorization.requireAnyAuthority((String[]) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void authenticate(org.springframework.security.core.Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static UsernamePasswordAuthenticationToken sessionAuthentication(SessionAuthenticator.Session session) {
        return UsernamePasswordAuthenticationToken.authenticated(session, null,
                session.authorities().stream().map(SimpleGrantedAuthority::new).toList());
    }

    private static SessionAuthenticator.Session session(String userId, String... authorities) {
        return new SessionAuthenticator.Session(userId, null, Set.copyOf(List.of(authorities)), CSRF_HASH);
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
