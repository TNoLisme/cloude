package com.simulatedbank.banking.admin.application;

import com.simulatedbank.banking.admin.application.ops.AdminApprovalAttemptRecorder;
import com.simulatedbank.banking.admin.application.ops.AdminApprovalMetrics;
import com.simulatedbank.banking.admin.application.ops.AdminApprovalRecoveryService;
import com.simulatedbank.banking.admin.application.ops.AdminWorkflowLog;
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
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort.ApprovedLimitChange;
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
 *   <li>Authenticated checker resolved from the trusted session ({@code CHECKER} privilege required) and the
 *       permission for the approval type re-checked from the same snapshot ({@code ROLE_MANAGE} for
 *       {@code ROLE_CHANGE}; {@code LIMIT_MAKER}/{@code LIMIT_MANAGE} for {@code LIMIT_CHANGE}).</li>
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
 *
 * <p>The canonical approval schema has no version column (04 §4.5.3), so there is no expected approval version to
 * compare here. {@code ROLE_CHANGE.expectedVersion} is captured from Identity at proposal time, bound into the
 * step-up hash and validated by {@link IdentityRoleCommand#applyApprovedRoleChange} under a user-row lock before
 * any mutation; a stale value is a {@code ROLE_CONFLICT}. A future Limit command owns the same validation rule for
 * its config version (ADM-OI-05).</p>
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
            ErrorCode.ROLE_CONFLICT,
            ErrorCode.IDEMPOTENCY_CONFLICT,
            // STEP_UP_REQUIRED is the normal first-call 428, not a denial: it must not emit C3.
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
    private final LimitConfigMutationPort limitChanges;
    private final AdminAuditPort audit;
    private final AdminApprovalMetrics metrics;
    private final AdminApprovalAttemptRecorder attempts;
    private final AdminApprovalRecoveryService recovery;
    private final AdminWorkflowLog workflow;
    private final AdminIdempotencyService idempotency;
    private final AdminRequestHasher hasher;
    private final AdminApprovalView view;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ApprovalDecisionService(ApprovalRequestStore approvals, ApprovalStepUpService stepUp,
            AdminAuthorization authorization, IdentityRoleCommand roleChanges,
            LimitConfigMutationPort limitChanges, AdminAuditPort audit,
            AdminApprovalMetrics metrics, AdminApprovalAttemptRecorder attempts,
            AdminApprovalRecoveryService recovery, AdminWorkflowLog workflow,
            AdminIdempotencyService idempotency, AdminRequestHasher hasher, AdminApprovalView view, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.approvals = approvals;
        this.stepUp = stepUp;
        this.authorization = authorization;
        this.roleChanges = roleChanges;
        this.limitChanges = limitChanges;
        this.audit = audit;
        this.metrics = metrics;
        this.attempts = attempts;
        this.recovery = recovery;
        this.workflow = workflow;
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
        long startedAt = System.nanoTime();
        long checkerId = authorization.requireChecker();
        // Defense-in-depth: the checker must hold the permission for this approval type (ROLE_MANAGE for
        // ROLE_CHANGE, LIMIT_MAKER/LIMIT_MANAGE for LIMIT_CHANGE), mirroring the chain rule with the trusted
        // principal instead of the URL. A mismatch is a 403, never a provider/state call.
        authorization.assertCanReview(command.expectedType());
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
                DecisionResult replayed = replay(replay.record(), command, checkerId);
                recordDecision(command, "REPLAYED", startedAt, correlation, requestId, null);
                return replayed;
            }
            if (command.challengeRef() == null || command.challengeRef().isBlank()) {
                // 428 before any approval/provider/idempotency state changes; challenge bound to approval+decision.
                requireExpectedType(command);
                // A Maker/target user asking for a challenge surfaces as ROLE_CONFLICT: the outer catch audits
                // exactly one C3 fact, outside any transaction and therefore without holding user locks.
                throw stepUp.stepUpRequired(command.approvalId(), command.decision(), note, correlation);
            }
            DecisionResult result = transaction.execute(status -> decideInTransaction(
                    command, checkerId, note, correlation, requestId, command.challengeRef().strip(), scope, key,
                    requestHash));
            if (result == null) {
                throw new IllegalStateException("Decision transaction returned no result");
            }
            recordDecision(command, "SUCCESS", startedAt, correlation, requestId, null);
            if (!result.replayed()) {
                // Best-effort: a retry after recorded failures is visible as a recovery operation.
                recovery.recordSuccessfulDecision(command.approvalId(), command.expectedType());
            }
            return result;
        } catch (BankingBusinessException rejection) {
            auditRejection(command.approvalId(), checkerId, correlation, requestId, rejection.code());
            recordDecisionFailure(command, rejection, checkerId, startedAt, correlation, requestId);
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
        // Denials are audited once by the outer catch after this transaction has rolled back (no user locks held).
        approval.assertCheckerEligible(checkerId);
        stepUp.consumeForDecision(approval, checkerId, command.decision(), note, challengeRef);

        AppliedChange applied = null;
        if (command.decision() == ApprovalDecision.APPROVE) {
            // A provider rejection rolls this transaction back; its C3 fact is written by the outer catch.
            applied = applyApprovedChange(approval, checkerId, correlation, requestId);
            approval.approve(checkerId, note, clock.instant());
        } else {
            // The rejected pending Limit configuration must not stay approvable after the approval is terminal.
            if (approval.payload() instanceof com.simulatedbank.banking.admin.domain.LimitChangePayload) {
                limitChanges.rejectPendingChange(approval.approvalId(), checkerId, correlation);
            }
            approval.reject(checkerId, note, clock.instant());
        }
        ApprovalRequest persisted = approvals.update(approval);
        AdminAuditPort.AdminAction action = AdminAuditPort.AdminAction.decision(persisted, command.decision());
        if (action == null) {
            throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }

        Map<String, Object> body = idempotency.complete(recordId, 200,
                view.detail(persisted, applied == null ? null : applied.responseResult()),
                persisted.approvalId());
        // C2 is the last catalog write: failure rolls back approval, provider change and idempotency completion.
        audit.recordInTransaction(AdminAuditPort.AdminAuditEvent.decided(persisted, command.decision(), checkerId,
                authorization.actorRole(), correlation, requestId,
                applied == null ? null : applied.auditBeforeState(),
                applied == null ? null : applied.auditAfterState()));
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
     * Provider mutation for an approved change, joined to this transaction ({@code MANDATORY}):
     * <ul>
     *   <li>{@code ROLE_CHANGE} — Identity applies the role/privilege, checks {@code expectedVersion} under a user
     *       lock and revokes the target's active sessions; a stale version or revoked-session failure rolls
     *       everything back as {@code ROLE_CONFLICT}/{@code SERVICE_UNAVAILABLE}.</li>
     *   <li>{@code LIMIT_CHANGE} — Limit supersedes the ACTIVE configuration and activates the pending version
     *       after re-validating {@code expectedVersion} and policy; a duplicate apply is impossible.</li>
     * </ul>
     */
    private AppliedChange applyApprovedChange(ApprovalRequest approval, long checkerId, String correlation,
            String requestId) {
        if (approval.payload() instanceof com.simulatedbank.banking.admin.domain.LimitChangePayload limit) {
            long started = System.nanoTime();
            try {
                var applied = limitChanges.applyApprovedLimitChange(new ApprovedLimitChange(
                        approval.approvalId(), checkerId, limit.expectedVersion(), correlation));
                metrics.provider("LIMIT", "APPLY_LIMIT_CHANGE", "SUCCESS", System.nanoTime() - started);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("limitConfigId", Long.toString(applied.limitConfigId()));
                body.put("effectiveFrom", applied.effectiveFrom().toString());
                body.put("version", applied.version());
                body.put("perTransactionLimit", applied.perTransactionLimit());
                body.put("dailyLimit", applied.dailyLimit());
                body.put("previousPerTransactionLimit", applied.previousPerTransactionLimit());
                body.put("previousDailyLimit", applied.previousDailyLimit());
                Map<String, Object> after = new LinkedHashMap<>();
                after.put("perTransactionLimit", applied.perTransactionLimit());
                after.put("dailyLimit", applied.dailyLimit());
                after.put("version", Long.toString(applied.version()));
                Map<String, Object> before = new LinkedHashMap<>();
                if (applied.previousPerTransactionLimit() != null) {
                    before.put("perTransactionLimit", applied.previousPerTransactionLimit());
                    before.put("dailyLimit", applied.previousDailyLimit());
                }
                return new AppliedChange(Collections.unmodifiableMap(body), before.isEmpty() ? null : before,
                        after);
            } catch (BankingBusinessException failure) {
                metrics.provider("LIMIT", "APPLY_LIMIT_CHANGE", "FAILURE", System.nanoTime() - started);
                if (failure.code() == ErrorCode.ROLE_CONFLICT) {
                    metrics.versionConflict("LIMIT");
                }
                throw failure;
            }
        }
        if (!(approval.payload() instanceof RoleChangePayload payload)) {
            throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        long started = System.nanoTime();
        IdentityRoleCommand.RoleChangeResult result;
        try {
            result = roleChanges.applyApprovedRoleChange(new IdentityRoleCommand.ApprovedRoleChange(
                    approval.approvalId(), approval.makerId(), checkerId,
                    IdentityRoleCommand.ApprovalState.APPROVED, payload.targetUserId(),
                    operation(payload.operation()), payload.roleCode(), payload.privilegeCode(),
                    payload.expectedVersion(), correlation, requestId));
            metrics.provider("IDENTITY", "APPLY_ROLE_CHANGE", "SUCCESS", System.nanoTime() - started);
        } catch (BankingBusinessException failure) {
            metrics.provider("IDENTITY", "APPLY_ROLE_CHANGE", "FAILURE", System.nanoTime() - started);
            if (failure.code() == ErrorCode.ROLE_CONFLICT) {
                metrics.versionConflict("IDENTITY");
            }
            throw failure;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetUserId", Long.toString(result.targetUserId()));
        body.put("operation", result.operation().name());
        body.put("roleCode", result.roleCode());
        body.put("privilegeCode", result.privilegeCode());
        body.put("previousVersion", result.previousVersion());
        body.put("resultingVersion", result.resultingVersion());
        body.put("revokedSessionCount", result.revokedSessionCount());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("operation", result.operation().name());
        if (result.roleCode() != null) {
            after.put("roleCode", result.roleCode());
        }
        if (result.privilegeCode() != null) {
            after.put("privilegeCode", result.privilegeCode());
        }
        after.put("previousVersion", Long.toString(result.previousVersion()));
        after.put("resultingVersion", Long.toString(result.resultingVersion()));
        return new AppliedChange(Collections.unmodifiableMap(body), null, after);
    }

    /** Provider response body plus allowlisted audit state (design/01 §5.5/§5.6). */
    private record AppliedChange(Map<String, Object> responseResult, Map<String, Object> auditBeforeState,
            Map<String, Object> auditAfterState) {
    }

    /** Bounded decision metric + structured workflow fact; telemetry never changes the response. */
    private void recordDecision(DecisionCommand command, String outcome, long startedAt, String correlation,
            String requestId, String errorCode) {
        metrics.decision(command.expectedType().name(), command.decision().name(), outcome,
                System.nanoTime() - startedAt);
        workflow.transition("DECISION", command.expectedType(), command.decision().name(), outcome, errorCode,
                command.approvalId() > 0 ? Long.toString(command.approvalId()) : null, correlation, requestId);
    }

    /**
     * Failure evidence: bounded metric, one durable attempt row for operational/retryable categories and a
     * structured workflow fact. Client mistakes (validation, unknown approval, terminal state, 403) are metrics
     * only — they are not retry evidence and must not mutate the attempt table.
     */
    private void recordDecisionFailure(DecisionCommand command, BankingBusinessException rejection,
            long checkerId, long startedAt, String correlation, String requestId) {
        if (rejection.code() == ErrorCode.STEP_UP_REQUIRED) {
            // The 428 leg issues the decision challenge; it is not a failed decision attempt.
            return;
        }
        String outcome = rejection.code() == ErrorCode.ROLE_CONFLICT ? "CONFLICT"
                : rejection.code().httpStatus() >= 500 ? "FAILURE" : "REJECTED";
        recordDecision(command, outcome, startedAt, correlation, requestId, rejection.code().name());
        AdminApprovalAttemptRecorder.Outcome category = attemptCategory(rejection.code());
        if (category != null && command.approvalId() > 0) {
            attempts.record(command.approvalId(), command.expectedType(), checkerId, category,
                    rejection.code(), correlation, requestId);
        }
    }

    private static AdminApprovalAttemptRecorder.Outcome attemptCategory(ErrorCode code) {
        return switch (code) {
            case ROLE_CONFLICT -> AdminApprovalAttemptRecorder.Outcome.VERSION_CONFLICT;
            case AUDIT_WRITE_FAILED -> AdminApprovalAttemptRecorder.Outcome.AUDIT_FAILURE;
            case STEP_UP_CHALLENGE_MISMATCH, AUTH_CHALLENGE_NOT_FOUND, AUTH_CHALLENGE_EXPIRED,
                    AUTH_CHALLENGE_LOCKED, AUTH_OTP_INVALID, AUTH_OTP_EXPIRED ->
                    AdminApprovalAttemptRecorder.Outcome.STEP_UP_FAILURE;
            case SERVICE_UNAVAILABLE, INTERNAL_ERROR, GATEWAY_TIMEOUT ->
                    AdminApprovalAttemptRecorder.Outcome.TECHNICAL_FAILURE;
            case INVALID_STATE_TRANSITION, LIMIT_OUT_OF_POLICY, ACCOUNT_NOT_FOUND, CUSTOMER_NOT_FOUND ->
                    AdminApprovalAttemptRecorder.Outcome.PROVIDER_FAILURE;
            default -> null;
        };
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
        audit.recordStandalone(AdminAuditPort.AdminAuditEvent.denied(checkerId, authorization.actorRole(),
                approvalId, null, correlation, requestId, code));
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
