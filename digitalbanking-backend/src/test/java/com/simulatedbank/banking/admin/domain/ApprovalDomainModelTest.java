package com.simulatedbank.banking.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** BE-ADM-DOM-01: enums, transition matrix, terminal states and typed payload boundaries. */
class ApprovalDomainModelTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final long MAKER = 1100L;
    private static final long CHECKER = 1101L;
    private static final long TARGET = 1102L;

    @Test
    void pendingAllowsOnlyTheApprovedMvpTransitions() {
        assertThat(ApprovalStatus.PENDING.canTransitionTo(ApprovalStatus.APPLIED)).isTrue();
        assertThat(ApprovalStatus.PENDING.canTransitionTo(ApprovalStatus.REJECTED)).isTrue();
        assertThat(ApprovalStatus.PENDING.canTransitionTo(ApprovalStatus.CANCELLED)).isTrue();
        assertThat(ApprovalStatus.PENDING.canTransitionTo(ApprovalStatus.APPROVED))
                .as("APPROVED is schema-reserved, not an MVP transition")
                .isFalse();
        assertThat(ApprovalStatus.PENDING.canTransitionTo(ApprovalStatus.PENDING)).isFalse();
    }

    @Test
    void everyTerminalStatusRejectsEveryFurtherTransition() {
        for (ApprovalStatus terminal : Arrays.stream(ApprovalStatus.values())
                .filter(ApprovalStatus::isTerminal).toList()) {
            for (ApprovalStatus target : ApprovalStatus.values()) {
                assertThat(terminal.canTransitionTo(target)).isFalse();
                assertThatThrownBy(() -> terminal.requireTransitionTo(target))
                        .isInstanceOfSatisfying(BankingBusinessException.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED));
            }
        }
    }

    @Test
    void unsupportedNonTerminalTransitionUsesInvalidStateCode() {
        assertThatThrownBy(() -> ApprovalStatus.PENDING.requireTransitionTo(ApprovalStatus.APPROVED))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        assertThatThrownBy(() -> ApprovalStatus.PENDING.requireTransitionTo(ApprovalStatus.PENDING))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void terminalApprovalRejectsApproveRejectAndCancel() {
        ApprovalRequest applied = ApprovalRequest.restore(1L, limitPayload(), MAKER, "corr-1", NOW,
                ApprovalStatus.APPLIED, CHECKER, null, NOW, NOW);

        assertThatThrownBy(() -> applied.approve(CHECKER, null, NOW))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED));
        assertThatThrownBy(() -> applied.reject(CHECKER, null, NOW))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED));
        assertThatThrownBy(() -> applied.cancel(NOW))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED));
    }

    @Test
    void enumValuesAreExactAndUnknownValuesFail() {
        assertThat(ApprovalStatus.valueOf("PENDING")).isEqualTo(ApprovalStatus.PENDING);
        assertThat(ApprovalRequestType.valueOf("LIMIT_CHANGE")).isEqualTo(ApprovalRequestType.LIMIT_CHANGE);
        assertThat(ApprovalTargetType.valueOf("APP_USER")).isEqualTo(ApprovalTargetType.APP_USER);
        assertThat(ApprovalDecision.valueOf("REJECT")).isEqualTo(ApprovalDecision.REJECT);
        assertThat(RoleChangeOperation.valueOf("GRANT_PRIVILEGE"))
                .isEqualTo(RoleChangeOperation.GRANT_PRIVILEGE);

        assertThatThrownBy(() -> ApprovalStatus.valueOf("EXPIRED")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalRequestType.valueOf("USER_CHANGE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalTargetType.valueOf("ROLE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalDecision.valueOf("SKIP")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RoleChangeOperation.valueOf("ASSIGN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyLimitAndRoleChangesAreOpenInTheMvp() {
        assertThat(ApprovalRequestType.LIMIT_CHANGE.openInMvp()).isTrue();
        assertThat(ApprovalRequestType.ROLE_CHANGE.openInMvp()).isTrue();
        assertThat(ApprovalRequestType.RETENTION_HOLD.openInMvp()).isFalse();
        assertThat(ApprovalRequestType.RETENTION_POLICY_CHANGE.openInMvp()).isFalse();
    }

    @Test
    void limitMoneyAcceptsTheNumericBoundaryAndRejectsOverflow() {
        LimitChangePayload boundary = new LimitChangePayload(2001L, null,
                new BigDecimal("99999999999999999.99"), new BigDecimal("99999999999999999.99"),
                "VND", null, null);
        assertThat(boundary.perTransactionLimit().scale()).isEqualTo(2);

        assertInvalid(() -> new LimitChangePayload(2001L, null,
                new BigDecimal("100000000000000000.00"), new BigDecimal("100000000000000000.00"),
                "VND", null, null));
        assertInvalid(() -> new LimitChangePayload(2001L, null,
                new BigDecimal("-1.00"), new BigDecimal("5000000.00"), "VND", null, null));
        assertInvalid(() -> new LimitChangePayload(2001L, null,
                new BigDecimal("0.00"), new BigDecimal("5000000.00"), "VND", null, null));
        assertInvalid(() -> new LimitChangePayload(2001L, null,
                new BigDecimal("1000.001"), new BigDecimal("5000000.00"), "VND", null, null));
    }

    @Test
    void limitCurrencyDefaultsToVndAndRejectsAnythingElse() {
        assertThat(limitPayload().currency()).isEqualTo("VND");
        assertInvalid(() -> new LimitChangePayload(2001L, null, new BigDecimal("1000.00"),
                new BigDecimal("5000000.00"), "USD", null, null));
        assertInvalid(() -> new LimitChangePayload(2001L, null, new BigDecimal("1000.00"),
                new BigDecimal("5000000.00"), "vnd", null, null));
    }

    @Test
    void roleOperationsRequireTheMatchingConditionalField() {
        assertThatCode(() -> new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, TARGET,
                "AUDITOR", null, 0L, null)).doesNotThrowAnyException();
        assertThatCode(() -> new RoleChangePayload(RoleChangeOperation.REVOKE_ROLE, TARGET,
                "CUSTOMER", null, 0L, null)).doesNotThrowAnyException();
        assertThatCode(() -> new RoleChangePayload(RoleChangeOperation.GRANT_PRIVILEGE, TARGET,
                null, "CHECKER", 0L, null)).doesNotThrowAnyException();
        assertThatCode(() -> new RoleChangePayload(RoleChangeOperation.REVOKE_PRIVILEGE, TARGET,
                null, "CHECKER", 0L, null)).doesNotThrowAnyException();

        assertInvalid(() -> new RoleChangePayload(null, TARGET, "AUDITOR", null, 0L, null));
        assertInvalid(() -> new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, 0L,
                "AUDITOR", null, 0L, null));
        assertInvalid(() -> new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, TARGET,
                null, null, 0L, null));
        assertInvalid(() -> new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, TARGET,
                "SUPERUSER", null, 0L, null));
        assertInvalid(() -> new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, TARGET,
                "AUDITOR", "CHECKER", 0L, null));
        assertInvalid(() -> new RoleChangePayload(RoleChangeOperation.GRANT_PRIVILEGE, TARGET,
                null, "ADMIN", 0L, null));
        assertInvalid(() -> new RoleChangePayload(RoleChangeOperation.GRANT_PRIVILEGE, TARGET,
                "AUDITOR", "CHECKER", 0L, null));
        assertInvalid(() -> new RoleChangePayload(RoleChangeOperation.GRANT_PRIVILEGE, TARGET,
                null, "CHECKER", -1L, null));
    }

    @Test
    void roleTargetAndLimitScopeAreDerivedFromTheTypedPayload() {
        ApprovalRequest role = ApprovalRequest.propose(rolePayload(), MAKER, "corr-2", NOW);
        ApprovalRequest customerLimit = ApprovalRequest.propose(limitPayload(), MAKER, "corr-1", NOW);
        ApprovalRequest accountLimit = ApprovalRequest.propose(new LimitChangePayload(2001L, 3001L,
                new BigDecimal("5000000.00"), new BigDecimal("20000000.00"), "VND", null, null),
                MAKER, "corr-1", NOW);

        assertThat(role.requestType()).isEqualTo(ApprovalRequestType.ROLE_CHANGE);
        assertThat(role.targetType()).isEqualTo(ApprovalTargetType.APP_USER);
        assertThat(role.targetId()).isEqualTo(Long.toString(TARGET));
        assertThat(customerLimit.targetType()).isEqualTo(ApprovalTargetType.CUSTOMER);
        assertThat(customerLimit.targetId()).isEqualTo("2001");
        assertThat(accountLimit.targetType()).isEqualTo(ApprovalTargetType.ACCOUNT);
        assertThat(accountLimit.targetId()).isEqualTo("3001");
    }

    @Test
    void makerCannotTargetSelfAndCheckerCannotBeTheRoleTarget() {
        assertThatThrownBy(() -> ApprovalRequest.propose(rolePayload(), TARGET, "corr-2", NOW))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.ROLE_CONFLICT));

        ApprovalRequest role = ApprovalRequest.propose(rolePayload(), MAKER, "corr-2", NOW);
        assertThatThrownBy(() -> role.approve(TARGET, null, NOW))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.ROLE_CONFLICT));
    }

    @Test
    void reasonAndDecisionNoteAreOptionalButBoundedAndControlCharacterFree() {
        assertThatCode(() -> new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, TARGET,
                "AUDITOR", null, 0L, null)).doesNotThrowAnyException();
        assertThatCode(() -> new LimitChangePayload(2001L, null, new BigDecimal("1000.00"),
                new BigDecimal("5000000.00"), "VND", null, "x".repeat(500))).doesNotThrowAnyException();
        assertInvalid(() -> new LimitChangePayload(2001L, null, new BigDecimal("1000.00"),
                new BigDecimal("5000000.00"), "VND", null, "x".repeat(501)));
        assertInvalid(() -> new LimitChangePayload(2001L, null, new BigDecimal("1000.00"),
                new BigDecimal("5000000.00"), "VND", null, "line\u0000break"));
    }

    private static LimitChangePayload limitPayload() {
        return new LimitChangePayload(2001L, null, new BigDecimal("5000000.00"),
                new BigDecimal("20000000.00"), "VND", null, null);
    }

    private static RoleChangePayload rolePayload() {
        return new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, TARGET, "AUDITOR", null, 0L, "reason");
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BankingBusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
