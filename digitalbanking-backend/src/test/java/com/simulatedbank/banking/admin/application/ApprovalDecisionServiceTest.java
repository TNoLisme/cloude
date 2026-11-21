package com.simulatedbank.banking.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simulatedbank.banking.admin.application.AdminAuditPort.AdminAction;
import com.simulatedbank.banking.admin.application.AdminAuditPort.AdminAuditEvent;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionCommand;
import com.simulatedbank.banking.admin.application.ops.AdminApprovalAttemptRecorder;
import com.simulatedbank.banking.admin.application.ops.AdminApprovalMetrics;
import com.simulatedbank.banking.admin.application.ops.AdminApprovalRecoveryService;
import com.simulatedbank.banking.admin.application.ops.AdminWorkflowLog;
import com.simulatedbank.banking.admin.application.port.ApprovalRequestStore;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyRecord;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyScope;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyStatus;
import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.admin.domain.RoleChangePayload;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.IdentityRoleCommand;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort;
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
    private final LimitConfigMutationPort limitChanges = mock(LimitConfigMutationPort.class);
    private final AdminAuditPort audit = mock(AdminAuditPort.class);
    private final AdminApprovalMetrics metrics = mock(AdminApprovalMetrics.class);
    private final AdminApprovalAttemptRecorder attempts = mock(AdminApprovalAttemptRecorder.class);
    private final AdminApprovalRecoveryService recovery = mock(AdminApprovalRecoveryService.class);
    private final AdminWorkflowLog workflow = mock(AdminWorkflowLog.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AdminRequestHasher hasher = mock(AdminRequestHasher.class);
    private final AdminApprovalView view = mock(AdminApprovalView.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private ApprovalDecisionService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(authorization.requireChecker()).thenReturn(CHECKER);
        when(authorization.actorRole()).thenReturn("OPERATIONS");
        when(hasher.decisionHash(anyLong(), anyLong(), any(), any())).thenReturn(HASH);
        when(idempotency.precheck(any(), anyString(), anyString()))
                .thenReturn(new AdminIdempotencyService.Precheck.Absent());
        when(idempotency.claim(any(), anyString(), anyString()))
                .thenReturn(new AdminIdempotencyService.Claim.Claimed(77L));
        when(store.findById(APPROVAL)).thenReturn(Optional.of(pendingApproval()));
        service = new ApprovalDecisionService(store, stepUp, authorization, roleChanges, limitChanges, audit,
                metrics, attempts, recovery, workflow, idempotency, hasher, view,
                Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
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

    @Test
    void approvedDecisionWritesOnlyTheCatalogC2FactWithTheTrustedChecker() {
        ApprovalRequest pending = pendingApproval();
        when(store.lockById(APPROVAL)).thenReturn(Optional.of(pending));
        when(roleChanges.applyApprovedRoleChange(any())).thenReturn(new IdentityRoleCommand.RoleChangeResult(
                APPROVAL, 1105L, IdentityRoleCommand.Operation.GRANT_PRIVILEGE, null, "CHECKER", 0L, 1L, 0,
                "corr-1", "req-1"));
        when(store.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(view.detail(any(), any())).thenReturn(Map.of("approvalId", "5"));
        when(idempotency.complete(anyLong(), anyInt(), any(), anyLong())).thenReturn(Map.of("approvalId", "5"));

        service.decide(command(APPROVAL, ApprovalDecision.APPROVE, "note", "challenge", "corr-1", "req-1"));

        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(audit).recordInTransaction(event.capture());
        verify(audit, never()).recordStandalone(any());
        assertThat(event.getValue().action()).isEqualTo(AdminAction.ROLE_CHANGE_APPROVED);
        assertThat(event.getValue().auditClass()).isEqualTo(AdminAuditPort.AuditClass.C2);
        assertThat(event.getValue().actorUserId()).isEqualTo(CHECKER);
        assertThat(event.getValue().actorRole()).isEqualTo("OPERATIONS");
        assertThat(event.getValue().entityType()).isEqualTo("APPROVAL_REQUEST");
        assertThat(event.getValue().entityId()).isEqualTo(Long.toString(APPROVAL));
        assertThat(event.getValue().outcome()).isEqualTo("SUCCESS");
        assertThat(event.getValue().reasonCode()).isNull();
        assertThat(event.getValue().afterState()).containsEntry("previousVersion", "0")
                .containsEntry("resultingVersion", "1");
    }

    @Test
    void rejectedDecisionWritesTheC2RejectedFactWithoutCallingTheProvider() {
        ApprovalRequest pending = pendingApproval();
        when(store.lockById(APPROVAL)).thenReturn(Optional.of(pending));
        when(store.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(view.detail(any(), any())).thenReturn(Map.of("approvalId", "5"));
        when(idempotency.complete(anyLong(), anyInt(), any(), anyLong())).thenReturn(Map.of("approvalId", "5"));

        service.decide(command(APPROVAL, ApprovalDecision.REJECT, "not allowed", "challenge", "corr-1", "req-1"));

        verify(roleChanges, never()).applyApprovedRoleChange(any());
        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(audit).recordInTransaction(event.capture());
        verify(audit, never()).recordStandalone(any());
        assertThat(event.getValue().action()).isEqualTo(AdminAction.ROLE_CHANGE_REJECTED);
        assertThat(event.getValue().auditClass()).isEqualTo(AdminAuditPort.AuditClass.C2);
        assertThat(event.getValue().actorUserId()).isEqualTo(CHECKER);
        assertThat(event.getValue().entityId()).isEqualTo(Long.toString(APPROVAL));
        assertThat(event.getValue().outcome()).isEqualTo("SUCCESS");
        assertThat(event.getValue().reasonCode()).isNull();
    }

    @Test
    void staleProviderVersionIsAConflictAndNeverPersistsApplied() {
        ApprovalRequest pending = pendingApproval();
        when(store.lockById(APPROVAL)).thenReturn(Optional.of(pending));
        when(roleChanges.applyApprovedRoleChange(any()))
                .thenThrow(new BankingBusinessException(ErrorCode.ROLE_CONFLICT));

        assertCode(() -> service.decide(command(APPROVAL, ApprovalDecision.APPROVE, null, "challenge",
                "corr-1", "req-1")), ErrorCode.ROLE_CONFLICT);

        assertThat(pending.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(pending.appliedAt()).isNull();
        verify(store, never()).update(any());
        verify(audit, never()).recordInTransaction(any());
        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(audit).recordStandalone(event.capture());
        assertThat(event.getValue().action()).isEqualTo(AdminAction.SECURITY_ACCESS_DENIED);
        assertThat(event.getValue().reasonCode()).isEqualTo("ROLE_CONFLICT");
    }

    @Test
    void providerTechnicalFailureKeepsTheApprovalPendingWithoutAuditOrProviderRetry() {
        ApprovalRequest pending = pendingApproval();
        when(store.lockById(APPROVAL)).thenReturn(Optional.of(pending));
        when(roleChanges.applyApprovedRoleChange(any()))
                .thenThrow(new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE));

        assertCode(() -> service.decide(command(APPROVAL, ApprovalDecision.APPROVE, null, "challenge",
                "corr-1", "req-1")), ErrorCode.SERVICE_UNAVAILABLE);

        assertThat(pending.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(pending.checkerId()).isNull();
        assertThat(pending.appliedAt()).isNull();
        verify(store, never()).update(any());
        verify(audit, never()).recordInTransaction(any());
        verify(audit, never()).recordStandalone(any());
    }

    @Test
    void replayedDecisionDoesNotWriteAnotherAuditFact() {
        ApprovalRequest applied = ApprovalRequest.restore(APPROVAL, rolePayload(), MAKER, "corr-1", NOW,
                ApprovalStatus.APPLIED, CHECKER, null, NOW, NOW);
        when(idempotency.precheck(any(), anyString(), anyString())).thenReturn(
                new AdminIdempotencyService.Precheck.Replay(new AdminIdempotencyRecord(77L,
                        AdminIdempotencyScope.decision(CHECKER, APPROVAL), HASH,
                        AdminIdempotencyStatus.COMPLETED, 200, Map.of("approvalId", "5"), APPROVAL, NOW, NOW,
                        NOW.plusSeconds(86_400))));
        when(store.findById(APPROVAL)).thenReturn(Optional.of(applied));

        var result = service.decide(command(APPROVAL, ApprovalDecision.APPROVE, "note", "challenge", "corr-1",
                "req-1"));

        assertThat(result.replayed()).isTrue();
        verify(audit, never()).recordInTransaction(any());
        verify(audit, never()).recordStandalone(any());
    }

    @Test
    void checkerWithoutTheTypePermissionIsDeniedBeforePrecheckOrLock() {
        doThrow(new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN))
                .when(authorization).assertCanReview(ApprovalRequestType.ROLE_CHANGE);

        assertCode(() -> service.decide(command(APPROVAL, ApprovalDecision.APPROVE, null, "challenge",
                "corr-1", null)), ErrorCode.AUTH_FORBIDDEN);

        verify(authorization).requireChecker();
        verify(authorization).assertCanReview(ApprovalRequestType.ROLE_CHANGE);
        verify(idempotency, never()).precheck(any(), anyString(), anyString());
        verify(store, never()).lockById(anyLong());
        verify(roleChanges, never()).applyApprovedRoleChange(any());
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
