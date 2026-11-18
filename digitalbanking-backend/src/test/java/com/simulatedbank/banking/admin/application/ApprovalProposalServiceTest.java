package com.simulatedbank.banking.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simulatedbank.banking.admin.application.AdminAuditPort.AdminAction;
import com.simulatedbank.banking.admin.application.AdminAuditPort.AdminAuditEvent;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.ProposalResult;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.RoleProposalCommand;
import com.simulatedbank.banking.admin.application.ops.AdminApprovalMetrics;
import com.simulatedbank.banking.admin.application.ops.AdminWorkflowLog;
import com.simulatedbank.banking.admin.application.port.ApprovalRequestStore;
import com.simulatedbank.banking.admin.application.stepup.AdminProposalStepUpBinding;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyOperation;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyRecord;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyScope;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyStatus;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.BankingValidationException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.IdentityRoleCommand;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengePurpose;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengeRequest;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengeView;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ConsumedChallenge;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** BE-ADM-APP-01: proposal service authorization, step-up, idempotency and audit ordering. */
class ApprovalProposalServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final long MAKER = 1100L;
    private static final long TARGET = 1102L;
    private static final String KEY = "proposal-key-1";
    private static final String CORRELATION = "corr-proposal";
    private static final String REQUEST_ID = "req-proposal";
    private static final String HASH = "a".repeat(64);

    private final ApprovalRequestStore approvals = mock(ApprovalRequestStore.class);
    private final AdminAuthorization authorization = mock(AdminAuthorization.class);
    private final IdentityRoleCommand roleChanges = mock(IdentityRoleCommand.class);
    private final LimitConfigMutationPort limitChanges = mock(LimitConfigMutationPort.class);
    private final StepUpChallengePort challenges = mock(StepUpChallengePort.class);
    private final AdminProposalStepUpBinding stepUpBinding = mock(AdminProposalStepUpBinding.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AdminRequestHasher hasher = mock(AdminRequestHasher.class);
    private final AdminApprovalView view = mock(AdminApprovalView.class);
    private final AdminAuditPort audit = mock(AdminAuditPort.class);
    private final AdminApprovalMetrics metrics = mock(AdminApprovalMetrics.class);
    private final AdminWorkflowLog workflow = mock(AdminWorkflowLog.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private ApprovalProposalService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(authorization.requireAuthority("ROLE_MANAGE")).thenReturn(MAKER);
        when(authorization.actorRole()).thenReturn("OPERATIONS");
        when(hasher.roleProposalHash(anyLong(), any())).thenReturn(HASH);
        when(stepUpBinding.proposalRequestHash(any(), anyLong())).thenReturn(HASH);
        when(stepUpBinding.bindingKey(KEY)).thenReturn(KEY);
        when(idempotency.precheck(any(), anyString(), anyString()))
                .thenReturn(new AdminIdempotencyService.Precheck.Absent());
        when(roleChanges.getRoleAssignmentVersion(TARGET)).thenReturn(3L);
        service = new ApprovalProposalService(approvals, authorization, roleChanges, challenges, stepUpBinding,
                idempotency, hasher, view, audit, limitChanges, metrics, workflow,
                Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
    }

    @Test
    void unauthenticatedOrForbiddenActorIsRejectedBeforeAnyLookup() {
        when(authorization.requireAuthority("ROLE_MANAGE"))
                .thenThrow(new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN));

        assertCode(() -> service.proposeRole(command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", KEY, null)),
                ErrorCode.AUTH_FORBIDDEN);

        verifyNoInteractions(idempotency, challenges, roleChanges, approvals, audit);
    }

    @Test
    void missingIdempotencyKeyIsRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.proposeRole(command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", null,
                null))).isInstanceOfSatisfying(BankingValidationException.class,
                failure -> {
                    assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(failure.field()).isEqualTo("Idempotency-Key");
                });

        verifyNoInteractions(idempotency, challenges, roleChanges, approvals, audit);
    }

    @Test
    void invalidRolePayloadFailsBeforeIdempotencyOrChallenge() {
        assertCode(() -> service.proposeRole(new RoleProposalCommand(null, TARGET, "AUDITOR", null, null,
                KEY, null, CORRELATION, REQUEST_ID)), ErrorCode.VALIDATION_ERROR);

        verifyNoInteractions(idempotency, challenges, roleChanges, approvals, audit);
    }

    @Test
    void makerCannotTargetSelfBeforeAnyChallenge() {
        assertCode(() -> service.proposeRole(new RoleProposalCommand(RoleChangeOperation.ASSIGN_ROLE, MAKER,
                "ADMIN", null, null, KEY, null, CORRELATION, REQUEST_ID)), ErrorCode.ROLE_CONFLICT);

        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(audit).recordStandalone(event.capture());
        assertThat(event.getValue().action()).isEqualTo(AdminAction.SECURITY_ACCESS_DENIED);
        assertThat(event.getValue().auditClass()).isEqualTo(AdminAuditPort.AuditClass.C3);
        assertThat(event.getValue().reasonCode()).isEqualTo(ErrorCode.ROLE_CONFLICT.name());
        assertThat(event.getValue().entityType()).isEqualTo("APP_USER");
        verifyNoInteractions(idempotency, challenges, roleChanges, approvals);
    }

    @Test
    void providerVersionLookupFailureDoesNotIssueAChallengeOrWriteState() {
        when(roleChanges.getRoleAssignmentVersion(TARGET))
                .thenThrow(new BankingBusinessException(ErrorCode.USER_NOT_FOUND));

        assertCode(() -> service.proposeRole(command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", KEY, null)),
                ErrorCode.USER_NOT_FOUND);

        verify(challenges, never()).requestChallenge(any());
        verify(idempotency, never()).claim(any(), anyString(), anyString());
        verifyNoInteractions(approvals, audit);
    }

    @Test
    void stepUpProviderFailureDoesNotIssueAProposalOrClaimTheKey() {
        when(challenges.requestChallenge(any()))
                .thenThrow(new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE));

        assertCode(() -> service.proposeRole(command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", KEY, null)),
                ErrorCode.SERVICE_UNAVAILABLE);

        verify(idempotency, never()).claim(any(), anyString(), anyString());
        verifyNoInteractions(approvals, audit);
    }

    @Test
    void missingChallengeReturns428WithoutClaimApprovalOrAudit() {
        when(challenges.requestChallenge(any(ChallengeRequest.class))).thenReturn(challenge());

        BankingBusinessException required = expectCode(() -> service.proposeRole(
                command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", KEY, null)), ErrorCode.STEP_UP_REQUIRED);

        assertThat(required.extensions()).containsKey("challenge");
        verify(idempotency, never()).claim(any(), anyString(), anyString());
        verifyNoInteractions(approvals, audit);
    }

    @Test
    void replayReturnsTheStoredResponseWithoutChallengeConsumeOrProviderApply() {
        AdminIdempotencyScope scope = AdminIdempotencyScope.proposal(MAKER,
                AdminIdempotencyOperation.CREATE_ROLE_PROPOSAL);
        AdminIdempotencyRecord stored = new AdminIdempotencyRecord(7L, scope, HASH,
                AdminIdempotencyStatus.COMPLETED, 201, Map.of("approvalId", "900", "status", "PENDING"),
                900L, NOW, NOW, NOW.plusSeconds(3600));
        when(idempotency.precheck(any(), anyString(), anyString()))
                .thenReturn(new AdminIdempotencyService.Precheck.Replay(stored));

        ProposalResult result = service.proposeRole(command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", KEY,
                null));

        assertThat(result.replayed()).isTrue();
        assertThat(result.responseStatus()).isEqualTo(201);
        assertThat(result.approvalId()).isEqualTo(900L);
        assertThat(result.responseBody()).containsEntry("status", "PENDING");
        verify(challenges, never()).requestChallenge(any());
        verify(challenges, never()).consumeVerified(any());
        verify(idempotency, never()).claim(any(), anyString(), anyString());
        verifyNoInteractions(approvals, audit);
    }

    @Test
    void idempotencyConflictIsAuditedAsSecurityAccessDenied() {
        when(idempotency.precheck(any(), anyString(), anyString()))
                .thenThrow(new BankingBusinessException(ErrorCode.IDEMPOTENCY_CONFLICT));

        assertCode(() -> service.proposeRole(command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", KEY, null)),
                ErrorCode.IDEMPOTENCY_CONFLICT);

        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(audit).recordStandalone(event.capture());
        assertThat(event.getValue().action()).isEqualTo(AdminAction.SECURITY_ACCESS_DENIED);
        assertThat(event.getValue().auditClass()).isEqualTo(AdminAuditPort.AuditClass.C3);
        assertThat(event.getValue().reasonCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT.name());
        verifyNoInteractions(approvals);
    }

    @Test
    void successfulProposalPersistsPendingApprovalWithTrustedMakerAndC2Audit() {
        when(idempotency.claim(any(), anyString(), anyString()))
                .thenReturn(new AdminIdempotencyService.Claim.Claimed(77L));
        when(challenges.consumeVerified(any())).thenReturn(new ConsumedChallenge(5L,
                UUID.randomUUID().toString(), ChallengePurpose.SENSITIVE_ACTION, NOW));
        when(approvals.insert(any())).thenAnswer(invocation -> {
            ApprovalRequest pending = invocation.getArgument(0);
            return ApprovalRequest.restore(900L, pending.payload(), pending.makerId(),
                    pending.correlationId(), pending.createdAt(), pending.status(), null, null, null, null);
        });
        when(view.summary(any(ApprovalRequest.class))).thenReturn(Map.of("approvalId", "900", "status", "PENDING",
                "requestType", "ROLE_CHANGE"));
        when(idempotency.complete(anyLong(), anyInt(), anyMap(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(2));

        ProposalResult result = service.proposeRole(command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", KEY,
                "challenge-ref"));

        assertThat(result.responseStatus()).isEqualTo(201);
        assertThat(result.replayed()).isFalse();
        assertThat(result.approvalId()).isEqualTo(900L);
        assertThat(result.responseBody()).containsEntry("status", "PENDING");

        ArgumentCaptor<ApprovalRequest> inserted = ArgumentCaptor.forClass(ApprovalRequest.class);
        verify(approvals).insert(inserted.capture());
        assertThat(inserted.getValue().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(inserted.getValue().makerId()).isEqualTo(MAKER);
        assertThat(inserted.getValue().checkerId()).isNull();
        assertThat(inserted.getValue().appliedAt()).isNull();

        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(audit).recordInTransaction(event.capture());
        assertThat(event.getValue().action()).isEqualTo(AdminAction.ROLE_CHANGE_REQUESTED);
        assertThat(event.getValue().auditClass()).isEqualTo(AdminAuditPort.AuditClass.C2);
        assertThat(event.getValue().actorUserId()).isEqualTo(MAKER);
        assertThat(event.getValue().entityId()).isEqualTo("900");
        assertThat(event.getValue().outcome()).isEqualTo("SUCCESS");
    }

    @Test
    void challengeConsumptionFailureNeverPersistsOrCompletesTheIdempotencyRecord() {
        when(idempotency.claim(any(), anyString(), anyString()))
                .thenReturn(new AdminIdempotencyService.Claim.Claimed(77L));
        when(challenges.consumeVerified(any()))
                .thenThrow(new BankingBusinessException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));

        assertCode(() -> service.proposeRole(command(RoleChangeOperation.ASSIGN_ROLE, "AUDITOR", KEY,
                "challenge-ref")), ErrorCode.STEP_UP_CHALLENGE_MISMATCH);

        verify(approvals, never()).insert(any());
        verify(idempotency, never()).complete(anyLong(), anyInt(), anyMap(), anyLong());
        verifyNoInteractions(audit);
    }

    private static RoleProposalCommand command(RoleChangeOperation operation, String roleCode, String key,
            String challengeRef) {
        return new RoleProposalCommand(operation, TARGET, roleCode, null, "reason", key, challengeRef,
                CORRELATION, REQUEST_ID);
    }

    private static ChallengeView challenge() {
        return new ChallengeView(UUID.randomUUID().toString(), ChallengePurpose.SENSITIVE_ACTION, "OTP", null,
                NOW.plusSeconds(300), 5, 5);
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BankingBusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static BankingBusinessException expectCode(Runnable action, ErrorCode code) {
        BankingBusinessException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                action::run, BankingBusinessException.class);
        assertThat(failure).as("expected " + code).isNotNull();
        assertThat(failure.code()).isEqualTo(code);
        return failure;
    }
}
