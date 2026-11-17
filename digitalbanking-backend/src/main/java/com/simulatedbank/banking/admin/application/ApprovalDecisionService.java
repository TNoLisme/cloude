package com.simulatedbank.banking.admin.application;

import com.simulatedbank.banking.admin.application.port.ApprovalRequestStore;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyOperation;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyRecord;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyScope;
import com.simulatedbank.banking.admin.domain.AdminText;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BE-ADM-CON-02/CON-03: the only production entry point that may transition an approval.
 *
 * <p>Ordering of the invariant (07 §6.5/§8.5.3/§8.8.2, design/05 §12.1):</p>
 * <ol>
 *   <li>Authenticated checker resolved from the trusted session ({@code CHECKER} privilege required).</li>
 *   <li>Idempotency precheck: a completed record with the same canonical decision replays its stored response
 *       before any challenge is required; a different payload is a hard 409 conflict; a durable RECEIVED record is
 *       {@code 409 IDEMPOTENCY_IN_PROGRESS}.</li>
 *   <li>Approval row locked ({@code SELECT … FOR UPDATE}); unknown or wrong request type →
 *       {@code APPROVAL_NOT_FOUND} (anti-enumeration).</li>
 *   <li>Terminal state → {@code APPROVAL_ALREADY_DECIDED}; Maker–Checker separation and role-target rules →
 *       {@code ROLE_CONFLICT} (also enforced by the aggregate and DB check).</li>
 *   <li>{@code SENSITIVE_ACTION} challenge consumed through Identity in this same transaction, with the request
 *       hash recomputed from the locked row, the checker and the decision.</li>
 *   <li>Provider change applied (Identity role command JOIN; Limit provider absent → fail closed), then
 *       {@code APPLIED}/{@code REJECTED} persisted, C2 audit written last and the idempotency record completed
 *       with the exact public response — all in one commit.</li>
 * </ol>
 *
 * <p>Any rejection or provider failure rolls the transaction back: the approval stays {@code PENDING}, the
 * idempotency claim disappears and Identity restores the challenge to {@code VERIFIED} (design/02 §5.2), so a
 * failed attempt can be retried with the same proof but a consumed proof can never be reused for another
 * decision. A client timeout after commit is safe: the retry replays the stored response.</p>
 */
@Service
@ConditionalOnProperty(name = {"app.admin.enabled", "app.identity.enabled"}, havingValue = "true",
        matchIfMissing = true)
public class ApprovalDecisionService {

    /** Rejections that must produce a C3 {@code SECURITY_ACCESS_DENIED} audit fact. */
    private static final Set<ErrorCode> AUDITED_REJECTIONS = Set.of(
            ErrorCode.AUTH_FORBIDDEN,
            ErrorCode.APPROVAL_ALREADY_DECIDED,
            ErrorCode.INVALID_STATE_TRANSITION,
            ErrorCode.IDEMPOTENCY_CONFLICT,
            ErrorCode.STEP_UP_REQUIRED,
            ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
            ErrorCode.AUTH_CHALLENGE_NOT_FOUND,
            ErrorCode.AUTH_CHALLENGE_EXPIRED,
            ErrorCode.AUTH_CHALLENGE_LOCKED,
            ErrorCode.AUTH_OTP_INVALID,
            ErrorCode.AUTH_OTP_EXPIRED);

    private final ApprovalRequestStore approvals;
    private final ApprovalStepUpService stepUp;
    private final AdminAuthorization authorization;
    private final IdentityRoleCommand roleChanges;
    private final AdminAuditPort audit;
    private final AdminIdempotencyService idempotency;
    private final AdminRequestHasher hasher;
    private final AdminApprovalView view;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ApprovalDecisionService(ApprovalRequestStore approvals, ApprovalStepUpService stepUp,
            AdminAuthorization authorization, IdentityRoleCommand roleChanges, AdminAuditPort audit,
            AdminIdempotencyService idempotency, AdminRequestHasher hasher, AdminApprovalView view, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.approvals = approvals;
        this.stepUp = stepUp;
        this.authorization = authorization;
        this.roleChanges = roleChanges;
        this.audit = audit;
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.view = view;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Checker decision request; {@code checkerId} is always derived from the authenticated session. */
    public record DecisionCommand(long approvalId, ApprovalRequestType expectedType, ApprovalDecision decision,
            String decisionNote, String challengeRef, String correlationId, String requestId,
            String idempotencyKey) {

        public DecisionCommand {
            Objects.requireNonNull(decision, "decision is required");
            Objects.requireNonNull(expectedType, "expectedType is required");
        }
    }

    /** Decision outcome with the exact public body stored for idempotent replay. */
    public record DecisionResult(ApprovalRequest approval, int responseStatus, Map<String, Object> responseBody,
            boolean replayed, ApprovalDecision decision) {
        public DecisionResult {
            Objects.requireNonNull(approval, "approval is required");
            responseBody = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(responseBody, "responseBody is required")));
        }
    }

    /**
     * Executes one checker decision. A missing challenge never creates an unbound authorization: the server
     * issues the approval-bound challenge and raises {@code STEP_UP_REQUIRED} (428) with its public view.
     */
    public DecisionResult decide(DecisionCommand command) {
        Objects.requireNonNull(command, "command");
        long checkerId = authorization.requireChecker();
        requireApprovalId(command.approvalId());
        String note = AdminText.optional(command.decisionNote(), 500);
        String correlation = AdminText.required(command.correlationId(), 64);
        String requestId = AdminText.optional(command.requestId(), 64);
        String key = AdminIdempotencyService.requireKey(command.idempotencyKey());
        AdminIdempotencyScope scope = AdminIdempotencyScope.decision(checkerId, command.approvalId());
        String requestHash = hasher.decisionHash(checkerId, command.approvalId(), command.decision(), note);
        try {
            var precheck = idempotency.precheck(scope, key, requestHash);
            if (precheck instanceof AdminIdempotencyService.Precheck.Replay replay) {
                return replay(replay.record(), command, checkerId);
            }
            if (command.challengeRef() == null || command.challengeRef().isBlank()) {
                // 428 before any approval/provider/idempotency state changes; challenge bound to approval+decision.
                requireExpectedType(command);
                throw stepUp.stepUpRequired(command.approvalId(), command.decision(), note, correlation);
            }
            DecisionResult result = transaction.execute(status -> decideInTransaction(
                    command, checkerId, note, correlation, requestId, command.challengeRef().strip(), scope, key,
                    requestHash));
            if (result == null) {
                throw new IllegalStateException("Decision transaction returned no result");
            }
            return result;
        } catch (BankingBusinessException rejection) {
            auditRejection(command.approvalId(), checkerId, correlation, requestId, rejection.code());
            throw rejection;
        }
    }

    private DecisionResult decideInTransaction(DecisionCommand command, long checkerId, String note,
            String correlation, String requestId, String challengeRef, AdminIdempotencyScope scope, String key,
            String requestHash) {
        var claim = idempotency.claim(scope, key, requestHash);
        if (claim instanceof AdminIdempotencyService.Claim.Existing existing) {
            return replay(existing.record(), command, checkerId);
        }
        long recordId = ((AdminIdempotencyService.Claim.Claimed) claim).recordId();

        ApprovalRequest approval = approvals.lockById(command.approvalId())
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.APPROVAL_NOT_FOUND));
        verifyType(approval, command.expectedType());
        // Terminal state and Maker–Checker/self-target rules are checked before consuming the proof.
        try {
            approval.assertCheckerEligible(checkerId);
        } catch (BankingBusinessException segregationViolation) {
            // Maker self-approval / role-target conflict is a security denial, not a provider conflict.
            auditDenial(approval.approvalId(), checkerId, correlation, requestId, segregationViolation.code());
            throw segregationViolation;
        }
        stepUp.consumeForDecision(approval, checkerId, command.decision(), note, challengeRef);

        Map<String, Object> result = null;
        if (command.decision() == ApprovalDecision.APPROVE) {
            result = applyApprovedChange(approval, checkerId, correlation, requestId);
            approval.approve(checkerId, note, clock.instant());
        } else {
            approval.reject(checkerId, note, clock.instant());
        }
        ApprovalRequest persisted = approvals.update(approval);
        AdminAuditPort.AdminAction action = AdminAuditPort.AdminAction.decision(persisted, command.decision());
        if (action == null) {
            throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }

        Map<String, Object> body = idempotency.complete(recordId, 200, view.detail(persisted, result),
                persisted.approvalId());
        // C2 is the last catalog write: failure rolls back approval, provider change and idempotency completion.
        audit.recordInTransaction(new AdminAuditPort.AdminAuditEvent(action, AdminAuditPort.AuditClass.C2,
                checkerId, persisted.approvalId(), persisted.requestType().name(), correlation, requestId,
                "SUCCESS", null));
        return new DecisionResult(persisted, 200, body, false, command.decision());
    }

    private DecisionResult replay(AdminIdempotencyRecord record, DecisionCommand command, long checkerId) {
        Long approvalId = record.approvalId();
        if (approvalId == null) {
            throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        ApprovalRequest approval = approvals.findById(approvalId)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.APPROVAL_NOT_FOUND));
        verifyType(approval, command.expectedType());
        if (approval.checkerId() == null || approval.checkerId() != checkerId) {
            // A stored record is scoped to the actor; never replay another checker's response.
            throw new BankingBusinessException(ErrorCode.APPROVAL_ALREADY_DECIDED);
        }
        if (command.decision() == ApprovalDecision.APPROVE && approval.appliedAt() == null) {
            throw new BankingBusinessException(ErrorCode.APPROVAL_ALREADY_DECIDED);
        }
        if (command.decision() == ApprovalDecision.REJECT && approval.status() != ApprovalStatus.REJECTED) {
            throw new BankingBusinessException(ErrorCode.APPROVAL_ALREADY_DECIDED);
        }
        return new DecisionResult(approval, record.requiredResponseStatus(), record.requiredResponseBody(), true,
                command.decision());
    }

    /**
     * Provider mutation for an approved change. ROLE_CHANGE is applied by Identity in this transaction
     * ({@code MANDATORY}); the Limit provider contract does not exist yet (ADM-OI-05), so approving a limit
     * change fails closed instead of persisting an {@code APPLIED} approval without the effect.
     */
    private Map<String, Object> applyApprovedChange(ApprovalRequest approval, long checkerId, String correlation,
            String requestId) {
        if (!(approval.payload() instanceof RoleChangePayload payload)) {
            throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        IdentityRoleCommand.RoleChangeResult result = roleChanges.applyApprovedRoleChange(
                new IdentityRoleCommand.ApprovedRoleChange(
                        approval.approvalId(), approval.makerId(), checkerId,
                        IdentityRoleCommand.ApprovalState.APPROVED, payload.targetUserId(),
                        operation(payload.operation()), payload.roleCode(), payload.privilegeCode(),
                        payload.expectedVersion(), correlation, requestId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetUserId", Long.toString(result.targetUserId()));
        body.put("operation", result.operation().name());
        body.put("roleCode", result.roleCode());
        body.put("privilegeCode", result.privilegeCode());
        body.put("previousVersion", result.previousVersion());
        body.put("resultingVersion", result.resultingVersion());
        body.put("revokedSessionCount", result.revokedSessionCount());
        return Collections.unmodifiableMap(body);
    }

    private void requireExpectedType(DecisionCommand command) {
        ApprovalRequest approval = approvals.findById(command.approvalId())
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.APPROVAL_NOT_FOUND));
        verifyType(approval, command.expectedType());
    }

    private static void verifyType(ApprovalRequest approval, ApprovalRequestType expectedType) {
        if (approval.requestType() != expectedType) {
            // A limit approval id on the role endpoint (or vice versa) must not reveal the other resource.
            throw new BankingBusinessException(ErrorCode.APPROVAL_NOT_FOUND);
        }
    }

    private void auditRejection(long approvalId, long checkerId, String correlation, String requestId,
            ErrorCode code) {
        if (AUDITED_REJECTIONS.contains(code)) {
            auditDenial(approvalId, checkerId, correlation, requestId, code);
        }
    }

    /** C3 {@code SECURITY_ACCESS_DENIED}: bounded reason code, no payload/note/challenge material. */
    private void auditDenial(long approvalId, long checkerId, String correlation, String requestId,
            ErrorCode code) {
        audit.recordStandalone(new AdminAuditPort.AdminAuditEvent(AdminAuditPort.AdminAction.SECURITY_ACCESS_DENIED,
                AdminAuditPort.AuditClass.C3, checkerId, approvalId, null, correlation, requestId, "REJECTED",
                code.name()));
    }

    private static IdentityRoleCommand.Operation operation(RoleChangeOperation operation) {
        return IdentityRoleCommand.Operation.valueOf(operation.name());
    }

    private static void requireApprovalId(long approvalId) {
        if (approvalId <= 0) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR, "approvalId là bắt buộc.");
        }
    }
}
