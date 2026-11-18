package com.simulatedbank.banking.admin.domain;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.time.Instant;
import java.util.Objects;

/**
 * Admin-owned Maker/Checker approval aggregate for {@code banking.approval_request}.
 *
 * <p>State machine (MVP, synchronous provider apply):</p>
 * <pre>
 * PENDING --approve--> APPLIED   (checker != maker; role target != maker/checker)
 * PENDING --reject---> REJECTED
 * PENDING --cancel---> CANCELLED
 * </pre>
 *
 * <p>{@link ApprovalStatus#APPROVED} exists only because the canonical DB check allows it; no MVP transition
 * persists it. Provider commands (Identity/Limit) receive the checked fact {@code APPROVED} while Admin persists
 * the final {@code APPLIED} in the same transaction (07 §8.5.3/§8.8.2).</p>
 *
 * <p>Concurrency: callers must load the row with {@code SELECT ... FOR UPDATE} before invoking a transition;
 * this aggregate only enforces transition legality. Maker/checker rule is enforced both here and by DB check.</p>
 */
public final class ApprovalRequest {

    private final long approvalId;
    private final ApprovalRequestType requestType;
    private final ApprovalTargetType targetType;
    private final String targetId;
    private final ChangePayload payload;
    private final long makerId;
    private final String correlationId;
    private final Instant createdAt;
    private ApprovalStatus status;
    private Long checkerId;
    private String decisionNote;
    private Instant decidedAt;
    private Instant appliedAt;

    private ApprovalRequest(long approvalId, ApprovalRequestType requestType, ApprovalTargetType targetType,
            String targetId, ChangePayload payload, long makerId, String correlationId, Instant createdAt,
            ApprovalStatus status, Long checkerId, String decisionNote, Instant decidedAt, Instant appliedAt) {
        this.approvalId = approvalId;
        this.requestType = requestType;
        this.targetType = targetType;
        this.targetId = targetId;
        this.payload = payload;
        this.makerId = makerId;
        this.correlationId = correlationId;
        this.createdAt = createdAt;
        this.status = status;
        this.checkerId = checkerId;
        this.decisionNote = decisionNote;
        this.decidedAt = decidedAt;
        this.appliedAt = appliedAt;
    }

    /** Creates a new PENDING request. Persisted by the store, which assigns {@code approvalId}. */
    public static ApprovalRequest propose(ChangePayload payload, long makerId, String correlationId, Instant createdAt) {
        Objects.requireNonNull(payload, "payload is required");
        if (!payload.requestType().openInMvp()) {
            throw AdminText.invalid();
        }
        AdminText.positiveId(makerId);
        String safeCorrelation = AdminText.required(correlationId, 64);
        Objects.requireNonNull(createdAt, "createdAt is required");
        if (payload instanceof RoleChangePayload role && role.targetUserId() == makerId) {
            throw conflict();
        }
        return new ApprovalRequest(0L, payload.requestType(), payload.targetType(), payload.targetId(), payload,
                makerId, safeCorrelation, createdAt, ApprovalStatus.PENDING, null, null, null, null);
    }

    /** Rebuilds a persisted row. Used by the persistence adapter only. */
    public static ApprovalRequest restore(long approvalId, ChangePayload payload, long makerId, String correlationId,
            Instant createdAt, ApprovalStatus status, Long checkerId, String decisionNote, Instant decidedAt,
            Instant appliedAt) {
        Objects.requireNonNull(payload, "payload is required");
        if (approvalId <= 0 || !payload.requestType().openInMvp()) {
            throw new IllegalStateException("Stored approval payload is not supported");
        }
        AdminText.positiveId(makerId);
        String safeCorrelation = AdminText.required(correlationId, 64);
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(status, "status is required");
        if ((status == ApprovalStatus.APPLIED || status == ApprovalStatus.APPROVED
                || status == ApprovalStatus.REJECTED) && checkerId == null) {
            throw new IllegalStateException("Stored decided approval has no checker");
        }
        if ((status == ApprovalStatus.APPLIED || status == ApprovalStatus.APPROVED
                || status == ApprovalStatus.REJECTED) && decidedAt == null) {
            throw new IllegalStateException("Stored decided approval has no decision time");
        }
        if (status == ApprovalStatus.APPLIED && appliedAt == null) {
            throw new IllegalStateException("Stored applied approval has no apply time");
        }
        if (status == ApprovalStatus.PENDING
                && (checkerId != null || decisionNote != null || decidedAt != null || appliedAt != null)) {
            throw new IllegalStateException("Stored pending approval has decision fields");
        }
        if (checkerId != null && checkerId == makerId) {
            throw new IllegalStateException("Stored approval violates maker/checker separation");
        }
        return new ApprovalRequest(approvalId, payload.requestType(), payload.targetType(), payload.targetId(),
                payload, makerId, safeCorrelation, createdAt, status, checkerId,
                AdminText.optional(decisionNote, 500), decidedAt, appliedAt);
    }

    /**
     * Approves and applies the change. The caller performs the provider mutation and C2 audit in the same
     * transaction, so APPLIED is the only successful persisted outcome.
     */
    public void approve(long checkerId, String decisionNote, Instant decidedAt) {
        status.requireTransitionTo(ApprovalStatus.APPLIED);
        requireDistinctActor(checkerId);
        Objects.requireNonNull(decidedAt, "decidedAt is required");
        this.status = ApprovalStatus.APPLIED;
        this.checkerId = checkerId;
        this.decisionNote = AdminText.optional(decisionNote, 500);
        this.decidedAt = decidedAt;
        this.appliedAt = decidedAt;
    }

    /** Rejects without any provider mutation. */
    public void reject(long checkerId, String decisionNote, Instant decidedAt) {
        status.requireTransitionTo(ApprovalStatus.REJECTED);
        requireDistinctActor(checkerId);
        Objects.requireNonNull(decidedAt, "decidedAt is required");
        this.status = ApprovalStatus.REJECTED;
        this.checkerId = checkerId;
        this.decisionNote = AdminText.optional(decisionNote, 500);
        this.decidedAt = decidedAt;
        this.appliedAt = null;
    }

    /** Cancels a pending request. No approval API is opened for this transition in the MVP. */
    public void cancel(Instant cancelledAt) {
        status.requireTransitionTo(ApprovalStatus.CANCELLED);
        Objects.requireNonNull(cancelledAt, "cancelledAt is required");
        this.status = ApprovalStatus.CANCELLED;
        this.checkerId = null;
        this.decisionNote = null;
        this.decidedAt = cancelledAt;
        this.appliedAt = null;
    }

    public boolean isPending() {
        return status.isPending();
    }

    /**
     * Pre-flight Maker–Checker gate reused by the step-up challenge issuance path and by both transitions:
     * the request must still be PENDING, the checker must differ from the maker, and for {@code ROLE_CHANGE}
     * neither maker nor checker may be the target user (07 §8.8, 04 §4.5.3).
     */
    public void assertCheckerEligible(long checkerId) {
        requirePending();
        requireDistinctActor(checkerId);
    }

    private void requirePending() {
        if (!isPending()) {
            throw new BankingBusinessException(ErrorCode.APPROVAL_ALREADY_DECIDED);
        }
    }

    private void requireDistinctActor(long checkerId) {
        AdminText.positiveId(checkerId);
        if (checkerId == makerId) {
            throw conflict();
        }
        if (payload instanceof RoleChangePayload role
                && (checkerId == role.targetUserId() || makerId == role.targetUserId())) {
            throw conflict();
        }
    }

    private static BankingBusinessException conflict() {
        return new BankingBusinessException(ErrorCode.ROLE_CONFLICT);
    }

    public long approvalId() {
        return approvalId;
    }

    public ApprovalRequestType requestType() {
        return requestType;
    }

    public ApprovalTargetType targetType() {
        return targetType;
    }

    public String targetId() {
        return targetId;
    }

    public ChangePayload payload() {
        return payload;
    }

    public long makerId() {
        return makerId;
    }

    public String correlationId() {
        return correlationId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public ApprovalStatus status() {
        return status;
    }

    public Long checkerId() {
        return checkerId;
    }

    public String decisionNote() {
        return decisionNote;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    public Instant appliedAt() {
        return appliedAt;
    }

    @Override
    public String toString() {
        return "ApprovalRequest[approvalId=" + approvalId + ", requestType=" + requestType
                + ", targetType=" + targetType + ", status=" + status + "]";
    }
}
