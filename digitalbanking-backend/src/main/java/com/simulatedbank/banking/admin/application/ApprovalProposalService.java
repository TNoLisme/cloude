package com.simulatedbank.banking.admin.application;

import com.simulatedbank.banking.admin.application.port.ApprovalRequestStore;
import com.simulatedbank.banking.admin.application.stepup.AdminProposalStepUpBinding;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyOperation;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyRecord;
import com.simulatedbank.banking.admin.domain.AdminIdempotencyScope;
import com.simulatedbank.banking.admin.domain.AdminText;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.admin.domain.LimitChangePayload;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.admin.domain.RoleChangePayload;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.IdentityRoleCommand;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengePurpose;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengeRequest;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengeView;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ConsumeCommand;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BE-ADM-CON-03: Maker proposal application service with idempotency and Maker step-up.
 *
 * <p>Order of operations (07 §8.5.2/§8.8.1, design/02 §6.4):</p>
 * <ol>
 *   <li>Authorize the Maker from the trusted session and validate the idempotency key.</li>
 *   <li>Replay/conflict precheck using the canonical hash of the <b>client payload</b> (no server-captured
 *       {@code expectedVersion}). A completed request returns its stored 201 body without a new challenge.</li>
 *   <li>Capture the Identity role version, issue a {@code SENSITIVE_ACTION} challenge bound to the idempotency key
 *       and the exact proposal hash, and return 428 when no challenge was supplied.</li>
 *   <li>Retry with challenge: insert-first claim, consume the challenge, insert the approval, write C2 audit and
 *       complete the idempotency record in one transaction.</li>
 * </ol>
 *
 * <p>Any validation/provider/step-up/audit failure rolls the claim back, so the same key can be retried cleanly and
 * a failed operation is never marked {@code COMPLETED}. LIMIT_CHANGE proposals stay fail-closed because the Limit
 * provider has no runtime (ADM-OI-05); no partial approval is created.</p>
 */
@Service
@ConditionalOnProperty(name = {"app.admin.enabled", "app.identity.enabled"}, havingValue = "true",
        matchIfMissing = true)
public class ApprovalProposalService {

    private final ApprovalRequestStore approvals;
    private final AdminAuthorization authorization;
    private final IdentityRoleCommand roleChanges;
    private final StepUpChallengePort challenges;
    private final AdminProposalStepUpBinding stepUpBinding;
    private final AdminIdempotencyService idempotency;
    private final AdminRequestHasher hasher;
    private final AdminApprovalView view;
    private final AdminAuditPort audit;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ApprovalProposalService(ApprovalRequestStore approvals, AdminAuthorization authorization,
            IdentityRoleCommand roleChanges, StepUpChallengePort challenges,
            AdminProposalStepUpBinding stepUpBinding, AdminIdempotencyService idempotency,
            AdminRequestHasher hasher, AdminApprovalView view, AdminAuditPort audit, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.approvals = approvals;
        this.authorization = authorization;
        this.roleChanges = roleChanges;
        this.challenges = challenges;
        this.stepUpBinding = stepUpBinding;
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.view = view;
        this.audit = audit;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Role proposal request; actor identity is never a field. */
    public record RoleProposalCommand(RoleChangeOperation operation, long targetUserId, String roleCode,
            String privilegeCode, String reason, String idempotencyKey, String challengeRef,
            String correlationId, String requestId) {
    }

    /** Limit proposal request; currently fail-closed before any state is written. */
    public record LimitProposalCommand(long customerId, Long accountId, BigDecimal perTransactionLimit,
            BigDecimal dailyLimit, String currency, Instant effectiveFrom, String reason,
            String idempotencyKey, String challengeRef, String correlationId, String requestId) {
    }

    /** Created or replayed proposal response; the body is the exact map stored in the idempotency record. */
    public record ProposalResult(int responseStatus, Map<String, Object> responseBody, boolean replayed,
            long approvalId) {
        public ProposalResult {
            responseBody = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(responseBody, "responseBody is required")));
            if (approvalId <= 0) {
                throw new IllegalArgumentException("approvalId must be positive");
            }
        }
    }

    public ProposalResult proposeRole(RoleProposalCommand command) {
        Objects.requireNonNull(command, "command");
        long makerId = authorization.requireAuthority("ROLE_MANAGE");
        String correlation = AdminText.required(command.correlationId(), 64);
        String requestId = AdminText.optional(command.requestId(), 64);
        String key = AdminIdempotencyService.requireKey(command.idempotencyKey());
        String reason = AdminText.optional(command.reason(), 500);

        // Validates operation/roleCode/privilegeCode and maker != role target before any challenge or persistence.
        RoleChangePayload clientPayload = new RoleChangePayload(command.operation(), command.targetUserId(),
                command.roleCode(), command.privilegeCode(), 0L, reason);
        ApprovalRequest.propose(clientPayload, makerId, correlation, clock.instant());

        AdminIdempotencyScope scope = AdminIdempotencyScope.proposal(makerId,
                AdminIdempotencyOperation.CREATE_ROLE_PROPOSAL);
        String requestHash = hasher.roleProposalHash(makerId, clientPayload);
        try {
            var precheck = idempotency.precheck(scope, key, requestHash);
            if (precheck instanceof AdminIdempotencyService.Precheck.Replay replay) {
                return replay(replay.record());
            }

            long expectedVersion = roleChanges.getRoleAssignmentVersion(command.targetUserId());
            RoleChangePayload payload = new RoleChangePayload(command.operation(), command.targetUserId(),
                    command.roleCode(), command.privilegeCode(), expectedVersion, reason);
            String proposalHash = stepUpBinding.proposalRequestHash(payload, makerId);

            if (command.challengeRef() == null || command.challengeRef().isBlank()) {
                ChallengeView challenge = challenges.requestChallenge(new ChallengeRequest(
                        ChallengePurpose.SENSITIVE_ACTION, makerId, proposalHash,
                        stepUpBinding.bindingKey(key), correlation));
                throw new BankingBusinessException(ErrorCode.STEP_UP_REQUIRED, ErrorCode.STEP_UP_REQUIRED.detail(),
                        Map.of("challenge", ApprovalStepUpService.publicChallenge(challenge)));
            }

            ProposalResult result = transaction.execute(status -> proposeRoleInTransaction(
                    payload, makerId, correlation, requestId, key, requestHash, scope,
                    command.challengeRef().strip(), proposalHash));
            if (result == null) {
                throw new IllegalStateException("Proposal transaction returned no result");
            }
            return result;
        } catch (BankingBusinessException rejection) {
            if (rejection.code() == ErrorCode.IDEMPOTENCY_CONFLICT) {
                auditConflict(makerId, correlation, requestId);
            }
            throw rejection;
        }
    }

    /**
     * LIMIT_CHANGE proposal remains fail-closed: 07 §8.5.2 requires a {@code limit_config PENDING_APPROVAL} row in
     * the same transaction, and the Limit module has no runtime/contract (ADM-OI-05). No approval/idempotency row
     * is created, so a retry cannot duplicate a partial proposal.
     */
    public ProposalResult proposeLimit(LimitProposalCommand command) {
        Objects.requireNonNull(command, "command");
        authorization.requireAnyAuthority("LIMIT_MAKER", "LIMIT_MANAGE");
        AdminIdempotencyService.requireKey(command.idempotencyKey());
        // Validate the documented request shape first so malformed bodies stay 400, then fail closed because
        // the required Limit provider/pending limit_config row has no runtime (ADM-OI-05).
        new LimitChangePayload(command.customerId(), command.accountId(), command.perTransactionLimit(),
                command.dailyLimit(), command.currency(), command.effectiveFrom(), command.reason());
        throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "Limit provider chưa có runtime; không tạo approval LIMIT_CHANGE.");
    }

    private ProposalResult proposeRoleInTransaction(RoleChangePayload payload, long makerId, String correlation,
            String requestId, String key, String requestHash, AdminIdempotencyScope scope, String challengeRef,
            String proposalHash) {
        var claim = idempotency.claim(scope, key, requestHash);
        if (claim instanceof AdminIdempotencyService.Claim.Existing existing) {
            return replay(existing.record());
        }
        long recordId = ((AdminIdempotencyService.Claim.Claimed) claim).recordId();

        challenges.consumeVerified(new ConsumeCommand(challengeRef, makerId, ChallengePurpose.SENSITIVE_ACTION,
                proposalHash, stepUpBinding.bindingKey(key)));

        ApprovalRequest persisted = approvals.insert(
                ApprovalRequest.propose(payload, makerId, correlation, clock.instant()));
        Map<String, Object> body = idempotency.complete(recordId, 201, view.summary(persisted),
                persisted.approvalId());
        // C2 remains the last catalog write: any failure rolls back the approval and idempotency completion.
        audit.recordInTransaction(new AdminAuditPort.AdminAuditEvent(
                AdminAuditPort.AdminAction.ROLE_CHANGE_REQUESTED, AdminAuditPort.AuditClass.C2, makerId,
                persisted.approvalId(), persisted.requestType().name(), correlation, requestId, "SUCCESS", null));
        return new ProposalResult(201, body, false, persisted.approvalId());
    }

    private static ProposalResult replay(AdminIdempotencyRecord record) {
        if (record.approvalId() == null) {
            throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return new ProposalResult(record.requiredResponseStatus(), record.requiredResponseBody(), true,
                record.approvalId());
    }

    private void auditConflict(long makerId, String correlation, String requestId) {
        audit.recordStandalone(new AdminAuditPort.AdminAuditEvent(
                AdminAuditPort.AdminAction.SECURITY_ACCESS_DENIED, AdminAuditPort.AuditClass.C3, makerId, 0L,
                "ROLE_CHANGE", correlation, requestId, "REJECTED", ErrorCode.IDEMPOTENCY_CONFLICT.name()));
    }
}
