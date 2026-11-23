package com.simulatedbank.banking.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.admin.application.query.ApprovalVisibility;
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

/** BE-ADM-API-03: trusted-session scope derivation for approval reads (DEC-ADM-009). */
class ApprovalReadAuthorizationTest {

    private static final String HASH = "a".repeat(64);

    private final AdminAuthorization adminAuthorization = new AdminAuthorization();
    private final ApprovalReadAuthorization authorization =
            new ApprovalReadAuthorization(adminAuthorization);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingSessionIsAnAuthenticationFailure() {
        assertThatThrownBy(authorization::requireVisibility).isInstanceOfSatisfying(
                BankingBusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(ErrorCode.AUTH_SESSION_EXPIRED));
    }

    @Test
    void operationsMakerSeesOnlyTheirOwnRequests() {
        authenticate(41L, "ROLE_OPERATIONS", "LIMIT_MAKER");

        ApprovalVisibility visibility = authorization.requireVisibility();

        assertThat(visibility.actorUserId()).isEqualTo(41L);
        assertThat(visibility.reviewableTypes()).isEmpty();
        assertThat(visibility.isMakerOnly()).isTrue();
        assertThat(visibility.canReview(ApprovalRequestType.LIMIT_CHANGE)).isFalse();
    }

    @Test
    void customerRoleCannotReadApprovals() {
        authenticate(42L, "ROLE_CUSTOMER", "ACCOUNT_READ_SELF");

        assertThatThrownBy(authorization::requireVisibility).isInstanceOfSatisfying(
                BankingBusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    }

    @Test
    void adminWithoutCheckerPrivilegeIsNotAReviewer() {
        authenticate(43L, "ROLE_ADMIN", "ROLE_MANAGE", "LIMIT_MANAGE");

        ApprovalVisibility visibility = authorization.requireVisibility();

        assertThat(visibility.reviewableTypes()).isEmpty();
    }

    @Test
    void checkerSeesOnlyTheRequestTypesTheyMayDecide() {
        authenticate(44L, "ROLE_ADMIN", "CHECKER", "ROLE_MANAGE");

        ApprovalVisibility roleReviewer = authorization.requireVisibility();

        assertThat(roleReviewer.reviewableTypes()).containsExactly(ApprovalRequestType.ROLE_CHANGE);
        assertThat(roleReviewer.canReview(ApprovalRequestType.LIMIT_CHANGE)).isFalse();

        authenticate(45L, "ROLE_OPERATIONS", "CHECKER", "LIMIT_MANAGE");
        ApprovalVisibility limitReviewer = authorization.requireVisibility();
        assertThat(limitReviewer.reviewableTypes()).containsExactly(ApprovalRequestType.LIMIT_CHANGE);

        authenticate(46L, "ROLE_ADMIN", "CHECKER", "ROLE_MANAGE", "LIMIT_MAKER");
        ApprovalVisibility both = authorization.requireVisibility();
        assertThat(both.reviewableTypes()).containsExactlyInAnyOrder(
                ApprovalRequestType.ROLE_CHANGE, ApprovalRequestType.LIMIT_CHANGE);
    }

    @Test
    void decisionTypeRecheckMirrorsTheReviewScope() {
        authenticate(47L, "ROLE_OPERATIONS", "CHECKER", "LIMIT_MANAGE");

        adminAuthorization.assertCanReview(ApprovalRequestType.LIMIT_CHANGE);
        assertThatThrownBy(() -> adminAuthorization.assertCanReview(ApprovalRequestType.ROLE_CHANGE))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    }

    @Test
    void nonSessionPrincipalFailsClosed() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("opaque-principal", null, List.of()));

        assertThatThrownBy(authorization::requireVisibility).isInstanceOfSatisfying(
                BankingBusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(ErrorCode.AUTH_SESSION_EXPIRED));
    }

    private static void authenticate(long userId, String... authorities) {
        authenticateWithUserId(Long.toString(userId), authorities);
    }

    private static void authenticateWithUserId(String userId, String... authorities) {
        var claims = Set.of(authorities);
        var session = new SessionAuthenticator.Session(userId, null, claims, HASH);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                session, null, List.copyOf(claims).stream().map(SimpleGrantedAuthority::new).toList()));
    }
}
