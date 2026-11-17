package com.simulatedbank.banking.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simulatedbank.banking.admin.application.AdminAuditPort.AdminAction;
import com.simulatedbank.banking.admin.application.AdminAuditPort.AdminAuditEvent;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionCommand;
import com.simulatedbank.banking.admin.application.port.ApprovalRequestStore;
import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.admin.domain.RoleChangePayload;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.IdentityRoleCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** BE-ADM-CON-02/03: decision gate ordering and fail-closed behavior without touching persistence. */
class ApprovalDecisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final long APPROVAL = 5L;
    private static final long MAKER = 1100L;
    private static final long CHECKER = 1101L;
    private static final String KEY = "idem-key-1";
    private static final String HASH = "a".repeat(64);

    private final ApprovalRequestStore store = mock(ApprovalRequestStore.class);
    private final ApprovalStepUpService stepUp = mock(ApprovalStepUpService.class);
    private final AdminAuthorization authorization = mock(AdminAuthorization.class);
    private final IdentityRoleCommand roleChanges = mock(IdentityRoleCommand.class);
    private final AdminAuditPort audit = mock(AdminAuditPort.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AdminRequestHasher hasher = mock(AdminRequestHasher.class);
    private final AdminApprovalView view = mock(AdminApprovalView.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private ApprovalDecisionService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(authorization.requireChecker()).thenReturn(CHECKER);
        when(hasher.decisionHash(anyLong(), anyLong(), any(), any())).thenReturn(HASH);
        when(idempotency.precheck(any(), anyString(), anyString()))
                .thenReturn(new AdminIdempotencyService.Precheck.Absent());
        when(idempotency.claim(any(), anyString(), anyString()))
                .thenReturn(new AdminIdempotencyService.Claim.Claimed(77L));
        when(store.findById(APPROVAL)).thenReturn(Optional.of(pendingApproval()));
        service = new ApprovalDecisionService(store, stepUp, authorization, roleChanges, audit, idempotency,
                hasher, view, Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
    }

    @Test
    void missingChallengeRaises428WithoutLockingOrTransitioning() {
        BankingBusinessException required = new BankingBusinessException(ErrorCode.STEP_UP_REQUIRED,
                ErrorCode.STEP_UP_REQUIRED.detail(), Map.of("challenge", Map.of(
                        "challengeId", java.util.UUID.randomUUID(), "purpose", "SENSITIVE_ACTION",
                        "methods", java.util.List.of("OTP"), "expiresAt", NOW.plusSeconds(300),
                        "maxAttempts", 5, "remainingAttempts", 5)));
        when(stepUp.stepUpRequired(APPROVAL, ApprovalDecision.APPROVE, "note", "corr-1")).thenReturn(required);

        assertCode(() -> service.decide(command(APPROVAL, ApprovalDecision.APPROVE, "note", null, "corr-1",
                null)), ErrorCode.STEP_UP_REQUIRED);

        verify(store, never()).lockById(anyLong());
        verify(store, never()).update(any());
        verify(stepUp, never()).consumeForDecision(any(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    void missingApprovalIdIsRejectedBeforeAnyChallengeOrLookup() {
        assertCode(() -> service.decide(command(0L, ApprovalDecision.APPROVE, null, "challenge", "corr-1",
                null)), ErrorCode.VALIDATION_ERROR);

        verify(stepUp, never()).stepUpRequired(anyLong(), any(), any(), anyString());
        verify(store, never()).lockById(anyLong());
    }

    @Test
    void stepUpOutageFailsClosedAndKeepsTheApprovalPending() {
        ApprovalRequest pending = pendingApproval();
        when(store.lockById(APPROVAL)).thenReturn(Optional.of(pending));
        when(stepUp.consumeForDecision(pending, CHECKER, ApprovalDecision.APPROVE, "note", "challenge"))
                .thenThrow(new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE));

        assertCode(() -> service.decide(command(APPROVAL, ApprovalDecision.APPROVE, "note", "challenge",
                "corr-1", null)), ErrorCode.SERVICE_UNAVAILABLE);

        assertThat(pending.status()).isEqualTo(ApprovalStatus.PENDING);
        verify(store, never()).update(any());
        verify(roleChanges, never()).applyApprovedRoleChange(any());
        verify(audit, never()).recordInTransaction(any());
    }

    @Test
    void bindingMismatchRejectsAndAuditsWithoutProviderOrTransition() {
        ApprovalRequest pending = pendingApproval();
        when(store.lockById(APPROVAL)).thenReturn(Optional.of(pending));
        when(stepUp.consumeForDecision(pending, CHECKER, ApprovalDecision.APPROVE, "note", "challenge"))
                .thenThrow(new BankingBusinessException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));

        assertCode(() -> service.decide(command(APPROVAL, ApprovalDecision.APPROVE, "note", "challenge",
                "corr-1", "req-1")), ErrorCode.STEP_UP_CHALLENGE_MISMATCH);

        assertThat(pending.status()).isEqualTo(ApprovalStatus.PENDING);
        verify(store, never()).update(any());
        verify(roleChanges, never()).applyApprovedRoleChange(any());
        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(audit).recordStandalone(event.capture());
        assertThat(event.getValue().action()).isEqualTo(AdminAction.SECURITY_ACCESS_DENIED);
        assertThat(event.getValue().auditClass()).isEqualTo(AdminAuditPort.AuditClass.C3);
        assertThat(event.getValue().reasonCode()).isEqualTo("STEP_UP_CHALLENGE_MISMATCH");
    }

    @Test
    void terminalApprovalIsRejectedBeforeTheChallengeIsConsumed() {
        ApprovalRequest applied = ApprovalRequest.restore(APPROVAL, rolePayload(), MAKER, "corr-1", NOW,
                ApprovalStatus.APPLIED, CHECKER, null, NOW, NOW);
        when(store.lockById(APPROVAL)).thenReturn(Optional.of(applied));

        assertCode(() -> service.decide(command(APPROVAL, ApprovalDecision.APPROVE, null, "challenge",
                "corr-1", null)), ErrorCode.APPROVAL_ALREADY_DECIDED);

        verify(stepUp, never()).consumeForDecision(any(), anyLong(), any(), any(), anyString());
        verify(store, never()).update(any());
    }

    @Test
    void makerCannotDecideOwnRequestEvenWithACheckedChallenge() {
        ApprovalRequest pending = pendingApproval();
        when(store.lockById(APPROVAL)).thenReturn(Optional.of(pending));
        when(authorization.requireChecker()).thenReturn(MAKER);

        assertCode(() -> service.decide(command(APPROVAL, ApprovalDecision.APPROVE, null, "challenge",
                "corr-1", null)), ErrorCode.ROLE_CONFLICT);

        verify(stepUp, never()).consumeForDecision(any(), anyLong(), any(), any(), anyString());
        verify(store, never()).update(any());
    }

    @Test
    void missingChallengePermissionFailsClosed() {
        when(authorization.requireChecker()).thenThrow(new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN));

        assertCode(() -> service.decide(command(APPROVAL, ApprovalDecision.APPROVE, null, "challenge",
                "corr-1", null)), ErrorCode.AUTH_FORBIDDEN);

        verify(store, never()).lockById(anyLong());
    }

    @Test
    void wrongEndpointTypeIsNotFoundBeforeAChallengeIsIssued() {
        when(store.findById(APPROVAL)).thenReturn(Optional.of(pendingApproval()));

        assertCode(() -> service.decide(new DecisionCommand(APPROVAL, ApprovalRequestType.LIMIT_CHANGE,
                ApprovalDecision.APPROVE, null, null, "corr-1", null, KEY)), ErrorCode.APPROVAL_NOT_FOUND);

        verify(stepUp, never()).stepUpRequired(anyLong(), any(), any(), anyString());
        verify(store, never()).lockById(anyLong());
    }

    private static DecisionCommand command(long approvalId, ApprovalDecision decision, String note,
            String challenge, String correlation, String requestId) {
        return new DecisionCommand(approvalId, ApprovalRequestType.ROLE_CHANGE, decision, note, challenge,
                correlation, requestId, KEY);
    }

    private static ApprovalRequest pendingApproval() {
        return ApprovalRequest.restore(APPROVAL, rolePayload(), MAKER, "corr-1", NOW, ApprovalStatus.PENDING,
                null, null, null, null);
    }

    private static RoleChangePayload rolePayload() {
        return new RoleChangePayload(RoleChangeOperation.GRANT_PRIVILEGE, 1105L, null, "CHECKER", 0L, "reason");
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BankingBusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
