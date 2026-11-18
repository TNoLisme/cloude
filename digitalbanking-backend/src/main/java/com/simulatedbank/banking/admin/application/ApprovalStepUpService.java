package com.simulatedbank.banking.admin.application;

import com.simulatedbank.banking.admin.application.port.ApprovalRequestStore;
import com.simulatedbank.banking.admin.application.stepup.ApprovalStepUpBinding;
import com.simulatedbank.banking.admin.domain.AdminText;
import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengePurpose;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengeRequest;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengeView;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ConsumeCommand;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ConsumedChallenge;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * BE-ADM-CON-02: Admin-side step-up binding for Maker–Checker decisions.
 *
 * <p>Issuance runs on the checker's first call for an existing approval: the server derives the checker from the
 * authenticated session, validates that the approval exists, is {@code PENDING} and that Maker–Checker
 * separation holds, then asks Identity for a {@code SENSITIVE_ACTION} challenge bound to
 * {@code bindingKey = approvalId} and {@code requestHash = ApprovalStepUpBinding.decisionRequestHash}. No
 * challenge is ever issued for a missing/placeholder approval id (MOD-OI-10 requires a persisted approval
 * before binding).</p>
 *
 * <p>Consumption happens inside the decision transaction through
 * {@link StepUpChallengePort#consumeVerified(ConsumeCommand)} which is {@code MANDATORY} in Identity: wrong user,
 * wrong purpose, different approval, changed payload/decision/note, expired, already consumed or concurrent
 * reuse are all rejected by Identity from trusted row state. This service never decides success from a client
 * supplied challenge id or flag.</p>
 */
@Service
@ConditionalOnProperty(name = {"app.admin.enabled", "app.identity.enabled"}, havingValue = "true",
        matchIfMissing = true)
public class ApprovalStepUpService {

    private final ApprovalRequestStore approvals;
    private final StepUpChallengePort challenges;
    private final ApprovalStepUpBinding binding;
    private final AdminAuthorization authorization;

    public ApprovalStepUpService(ApprovalRequestStore approvals, StepUpChallengePort challenges,
            ApprovalStepUpBinding binding, AdminAuthorization authorization) {
        this.approvals = approvals;
        this.challenges = challenges;
        this.binding = binding;
        this.authorization = authorization;
    }

    /**
     * 428 initiation path (07 §6.5): create or reuse the checker's challenge bound to this approval and decision.
     * The caller returns the public challenge with {@code STEP_UP_REQUIRED}; no approval state changes here.
     */
    public ChallengeView requestDecisionChallenge(long approvalId, ApprovalDecision decision, String decisionNote,
            String correlationId) {
        requireApprovalId(approvalId);
        long checkerId = authorization.requireChecker();
        requireDecision(decision);
        String note = AdminText.optional(decisionNote, 500);
        String correlation = requireCorrelation(correlationId);
        ApprovalRequest approval = approvals.findById(approvalId)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.APPROVAL_NOT_FOUND));
        // Defense-in-depth for @apiNote service callers: the CHECKER privilege alone never authorizes a
        // challenge for an approval type the checker has no permission for (ROLE_MANAGE / LIMIT_*).
        authorization.assertCanReview(approval.requestType());
        approval.assertCheckerEligible(checkerId);
        return issue(approval, checkerId, decision, note, correlation);
    }

    /**
     * Issues the bound challenge and returns the 428 problem raised when the caller supplied no challenge id.
     * The challenge is created in Identity's own short transaction, so it is never rolled back by the caller's
     * decision transaction (which has not started yet).
     */
    public BankingBusinessException stepUpRequired(long approvalId, ApprovalDecision decision, String decisionNote,
            String correlationId) {
        ChallengeView view = requestDecisionChallenge(approvalId, decision, decisionNote, correlationId);
        return new BankingBusinessException(ErrorCode.STEP_UP_REQUIRED, ErrorCode.STEP_UP_REQUIRED.detail(),
                Map.of("challenge", publicChallenge(view)));
    }

    /**
     * Consumes the checker's challenge inside the caller transaction (Identity {@code MANDATORY}/{@code JOIN}).
     * The hash is recomputed from the locked approval and the decision being executed, so any change between
     * issuance and execution is rejected as a binding mismatch.
     */
    public ConsumedChallenge consumeForDecision(ApprovalRequest lockedApproval, long checkerId,
            ApprovalDecision decision, String decisionNote, String challengeRef) {
        if (lockedApproval == null || checkerId <= 0 || decision == null) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (challengeRef == null || challengeRef.isBlank()) {
            // The 428 path issues the challenge before opening the decision transaction; reaching this point
            // without a challenge id is a caller defect and must never silently authorize the transition.
            throw new IllegalStateException("A decision challenge reference is required before consumption");
        }
        String hash = binding.decisionRequestHash(lockedApproval, checkerId, decision,
                AdminText.optional(decisionNote, 500));
        return challenges.consumeVerified(new ConsumeCommand(challengeRef.strip(), checkerId,
                ChallengePurpose.SENSITIVE_ACTION, hash, binding.bindingKey(lockedApproval.approvalId())));
    }

    /** Public 428 challenge extension shape (07 §6.3): no OTP, hash or binding material. */
    public static Map<String, Object> publicChallenge(ChallengeView view) {
        Map<String, Object> challenge = new LinkedHashMap<>();
        challenge.put("challengeId", java.util.UUID.fromString(view.challengeRef()));
        challenge.put("purpose", view.purpose().name());
        challenge.put("methods", List.of(view.method()));
        challenge.put("expiresAt", view.expiresAt());
        challenge.put("maxAttempts", view.maxAttempts());
        challenge.put("remainingAttempts", Math.max(0,
                Math.min(view.maxAttempts(), view.remainingAttempts())));
        if (view.channel() != null) {
            challenge.put("channel", view.channel());
        }
        return Map.copyOf(challenge);
    }

    private ChallengeView issue(ApprovalRequest approval, long checkerId, ApprovalDecision decision, String note,
            String correlationId) {
        String hash = binding.decisionRequestHash(approval, checkerId, decision, note);
        return challenges.requestChallenge(new ChallengeRequest(ChallengePurpose.SENSITIVE_ACTION, checkerId, hash,
                binding.bindingKey(approval.approvalId()), correlationId));
    }

    private static void requireApprovalId(long approvalId) {
        if (approvalId <= 0) {
            // MOD-OI-10: no binding to a null/placeholder id is ever created.
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR, "approvalId là bắt buộc.");
        }
    }

    private static void requireDecision(ApprovalDecision decision) {
        if (decision == null) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private static String requireCorrelation(String correlationId) {
        return AdminText.required(correlationId, 64);
    }
}
