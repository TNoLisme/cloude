package com.simulatedbank.banking.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ApprovalRequestTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final long MAKER = 1100L;
    private static final long CHECKER = 1101L;
    private static final long TARGET = 1102L;

    @Test
    void proposesCustomerLimitAsPending() {
        ApprovalRequest request = ApprovalRequest.propose(limit(null), MAKER, "corr-1", NOW);

        assertThat(request.approvalId()).isZero();
        assertThat(request.requestType()).isEqualTo(ApprovalRequestType.LIMIT_CHANGE);
        assertThat(request.targetType()).isEqualTo(ApprovalTargetType.CUSTOMER);
        assertThat(request.targetId()).isEqualTo("2001");
        assertThat(request.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(request.makerId()).isEqualTo(MAKER);
        assertThat(request.checkerId()).isNull();
        assertThat(request.decidedAt()).isNull();
        assertThat(request.appliedAt()).isNull();
        assertThat(request.createdAt()).isEqualTo(NOW);
    }

    @Test
    void accountScopedLimitTargetsTheAccount() {
        ApprovalRequest request = ApprovalRequest.propose(limit(3001L), MAKER, "corr-1", NOW);

        assertThat(request.targetType()).isEqualTo(ApprovalTargetType.ACCOUNT);
        assertThat(request.targetId()).isEqualTo("3001");
    }

    @Test
    void moneyIsNormalizedToTwoDecimals() {
        LimitChangePayload payload = new LimitChangePayload(2001L, null,
                new BigDecimal("5000000.000"), new BigDecimal("20000000.00"), null, null, "  reason  ");

        assertThat(payload.perTransactionLimit()).isEqualByComparingTo("5000000.00");
        assertThat(payload.perTransactionLimit().scale()).isEqualTo(2);
        assertThat(payload.currency()).isEqualTo("VND");
        assertThat(payload.reason()).isEqualTo("reason");
    }

    @Test
    void perTransactionLimitCannotExceedDailyLimit() {
        assertInvalid(() -> new LimitChangePayload(2001L, null, new BigDecimal("20000000.00"),
                new BigDecimal("5000000.00"), "VND", null, null));
    }

    @Test
    void limitMoneyMustBePositiveWithAtMostTwoNonZeroDecimals() {
        assertInvalid(() -> new LimitChangePayload(2001L, null, BigDecimal.ZERO,
                new BigDecimal("5000000.00"), "VND", null, null));
        assertInvalid(() -> new LimitChangePayload(2001L, null, new BigDecimal("1000.001"),
                new BigDecimal("5000000.00"), "VND", null, null));
        assertInvalid(() -> new LimitChangePayload(2001L, null, new BigDecimal("100000000000000000000.00"),
                new BigDecimal("100000000000000000000.00"), "VND", null, null));
    }

    @Test
    void unsupportedCurrencyAndInvalidIdentifierAreRejected() {
        assertInvalid(() -> new LimitChangePayload(2001L, null, new BigDecimal("1000.00"),
                new BigDecimal("5000000.00"), "USD", null, null));
        assertInvalid(() -> new LimitChangePayload(0L, null, new BigDecimal("1000.00"),
                new BigDecimal("5000000.00"), "VND", null, null));
        assertInvalid(() -> new LimitChangePayload(2001L, 0L, new BigDecimal("1000.00"),
                new BigDecimal("5000000.00"), "VND", null, null));
    }

    @Test
    void proposesRoleChangeCapturedWithProviderVersion() {
        ApprovalRequest request = ApprovalRequest.propose(role(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", null, 3L),
                MAKER, "corr-2", NOW);

        assertThat(request.requestType()).isEqualTo(ApprovalRequestType.ROLE_CHANGE);
        assertThat(request.targetType()).isEqualTo(ApprovalTargetType.APP_USER);
        assertThat(request.targetId()).isEqualTo(Long.toString(TARGET));
        assertThat(((RoleChangePayload) request.payload()).expectedVersion()).isEqualTo(3L);
    }

    @Test
    void roleOperationControlsConditionalFields() {
        assertInvalid(() -> new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, TARGET, null, null, 0L, null));
        assertInvalid(() -> new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, TARGET, "AUDITOR", "CHECKER",
                0L, null));
        assertInvalid(() -> new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, TARGET, "SUPERUSER", null,
                0L, null));
        assertInvalid(() -> new RoleChangePayload(RoleChangeOperation.GRANT_PRIVILEGE, TARGET, "AUDITOR",
                "CHECKER", 0L, null));
        assertInvalid(() -> new RoleChangePayload(RoleChangeOperation.GRANT_PRIVILEGE, TARGET, null, "ADMIN",
                0L, null));
        assertInvalid(() -> new RoleChangePayload(RoleChangeOperation.GRANT_PRIVILEGE, TARGET, null, "CHECKER",
                -1L, null));
        assertInvalid(() -> new RoleChangePayload(RoleChangeOperation.GRANT_PRIVILEGE, TARGET, null, "CHECKER",
                Long.MAX_VALUE, null));
        assertThat(new RoleChangePayload(RoleChangeOperation.GRANT_PRIVILEGE, TARGET, null, "CHECKER", 0L, null)
                .privilegeCode()).isEqualTo("CHECKER");
    }

    @Test
    void makerCannotTargetSelfForRoleEscalation() {
        RoleChangePayload payload = role(RoleChangeOperation.ASSIGN_ROLE, "ADMIN", null, 0L);
        assertCode(() -> ApprovalRequest.propose(payload, TARGET, "corr-2", NOW), ErrorCode.ROLE_CONFLICT);
    }

    @Test
    void correlationAndReasonAreBounded() {
        assertCode(() -> ApprovalRequest.propose(limit(null), MAKER, " ", NOW), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> ApprovalRequest.propose(limit(null), MAKER, "x".repeat(65), NOW),
                ErrorCode.VALIDATION_ERROR);
        assertCode(() -> ApprovalRequest.propose(limit(null), 0L, "corr", NOW), ErrorCode.VALIDATION_ERROR);
        assertInvalid(() -> new LimitChangePayload(2001L, null, new BigDecimal("1000.00"),
                new BigDecimal("5000000.00"), "VND", null, "x".repeat(501)));
        assertInvalid(() -> new LimitChangePayload(2001L, null, new BigDecimal("1000.00"),
                new BigDecimal("5000000.00"), "VND", null, "bad\u0000reason"));
    }

    @Test
    void approveAppliesTheChangeAndRecordsChecker() {
        ApprovalRequest request = ApprovalRequest.propose(limit(null), MAKER, "corr-1", NOW);

        request.approve(CHECKER, "ok", NOW.plusSeconds(60));

        assertThat(request.status()).isEqualTo(ApprovalStatus.APPLIED);
        assertThat(request.checkerId()).isEqualTo(CHECKER);
        assertThat(request.decisionNote()).isEqualTo("ok");
        assertThat(request.decidedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(request.appliedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(request.isPending()).isFalse();
    }

    @Test
    void rejectDoesNotApplyAndCannotBeRepeated() {
        ApprovalRequest request = ApprovalRequest.propose(limit(null), MAKER, "corr-1", NOW);

        request.reject(CHECKER, "policy", NOW.plusSeconds(60));

        assertThat(request.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(request.appliedAt()).isNull();
        assertThat(request.decidedAt()).isEqualTo(NOW.plusSeconds(60));
        assertCode(() -> request.approve(CHECKER, null, NOW.plusSeconds(120)), ErrorCode.APPROVAL_ALREADY_DECIDED);
        assertCode(() -> request.reject(CHECKER, null, NOW.plusSeconds(120)), ErrorCode.APPROVAL_ALREADY_DECIDED);
        assertCode(() -> request.cancel(NOW.plusSeconds(120)), ErrorCode.APPROVAL_ALREADY_DECIDED);
    }

    @Test
    void checkerMustDifferFromMakerForAllRequestTypes() {
        ApprovalRequest limit = ApprovalRequest.propose(limit(null), MAKER, "corr-1", NOW);
        ApprovalRequest role = ApprovalRequest.propose(
                role(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", null, 0L), MAKER, "corr-2", NOW);

        assertCode(() -> limit.approve(MAKER, null, NOW), ErrorCode.ROLE_CONFLICT);
        assertCode(() -> limit.reject(MAKER, null, NOW), ErrorCode.ROLE_CONFLICT);
        assertCode(() -> role.approve(MAKER, null, NOW), ErrorCode.ROLE_CONFLICT);
    }

    @Test
    void roleTargetCannotBeMakerOrChecker() {
        ApprovalRequest request = ApprovalRequest.propose(
                role(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", null, 0L), MAKER, "corr-2", NOW);

        assertCode(() -> request.approve(TARGET, null, NOW), ErrorCode.ROLE_CONFLICT);
    }

    @Test
    void cancelIsOnlyAllowedWhilePending() {
        ApprovalRequest request = ApprovalRequest.propose(limit(null), MAKER, "corr-1", NOW);

        request.cancel(NOW.plusSeconds(30));

        assertThat(request.status()).isEqualTo(ApprovalStatus.CANCELLED);
        assertThat(request.checkerId()).isNull();
        assertThat(request.appliedAt()).isNull();
        assertCode(() -> request.approve(CHECKER, null, NOW), ErrorCode.APPROVAL_ALREADY_DECIDED);
    }

    @Test
    void restoreRejectsRowsThatViolateTimestampAndActorInvariants() {
        ChangePayload payload = limit(null);
        assertThatThrownBy(() -> ApprovalRequest.restore(1L, payload, MAKER, "corr", NOW,
                ApprovalStatus.PENDING, CHECKER, null, NOW, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ApprovalRequest.restore(1L, payload, MAKER, "corr", NOW,
                ApprovalStatus.APPLIED, CHECKER, null, NOW, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ApprovalRequest.restore(1L, payload, MAKER, "corr", NOW,
                ApprovalStatus.APPLIED, MAKER, null, NOW, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> ApprovalRequest.restore(1L, payload, MAKER, "corr", NOW,
                ApprovalStatus.CANCELLED, null, null, NOW, null)).doesNotThrowAnyException();
    }

    @Test
    void retentionTypesRemainSchemaReservedAndUnsupported() {
        assertThat(ApprovalRequestType.LIMIT_CHANGE.openInMvp()).isTrue();
        assertThat(ApprovalRequestType.ROLE_CHANGE.openInMvp()).isTrue();
        assertThat(ApprovalRequestType.RETENTION_HOLD.openInMvp()).isFalse();
        assertThat(ApprovalRequestType.RETENTION_POLICY_CHANGE.openInMvp()).isFalse();
    }

    private static LimitChangePayload limit(Long accountId) {
        return new LimitChangePayload(2001L, accountId, new BigDecimal("5000000.00"),
                new BigDecimal("20000000.00"), "VND", null, "customer request");
    }

    private static RoleChangePayload role(RoleChangeOperation operation, String roleCode, String privilegeCode,
            long expectedVersion) {
        return new RoleChangePayload(operation, TARGET, roleCode, privilegeCode, expectedVersion, "assignment");
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BankingBusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BankingBusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
