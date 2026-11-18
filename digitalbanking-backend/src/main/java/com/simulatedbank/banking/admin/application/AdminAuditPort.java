package com.simulatedbank.banking.admin.application;

import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.util.Objects;
import org.springframework.lang.Nullable;

/**
 * Audit boundary of the Admin module, following {@code design/01} §5.2/§5.3.
 *
 * <p>Only catalog action names are emitted — no invented per-reason actions (design/01 §5.4):</p>
 * <ul>
 *   <li>C2 ({@code recordInTransaction}, join the business transaction, fail-closed): the approval decision
 *       action of the owning group — {@code ROLE_CHANGE_APPROVED/REJECTED} or
 *       {@code LIMIT_CHANGE_APPROVED/REJECTED}. A failed write must roll back the approval transition.</li>
 *   <li>C3 ({@code recordStandalone}, best-effort): {@code SECURITY_ACCESS_DENIED} after an authorization,
 *       binding, replay, expiry or provider rejection. The business call already failed; audit failure must
 *       never turn a 403/409/428 into a 500, so it is swallowed by the adapter.</li>
 * </ul>
 *
 * <p>Payloads, decision notes, OTPs, hashes and challenge internals never reach this port. The durable
 * {@code audit_log}/{@code AuditModuleApi} of the Audit module does not exist yet (ADM-OI-07/BE-ADM-CON-01
 * §13), so the production adapter emits the structured security log + bounded metrics under the same contract;
 * replacing it with the Audit provider is a separate integration task.</p>
 */
public interface AdminAuditPort {

    /** Audit catalog actions owned by the approval flow (design/01 §5.3). */
    enum AdminAction {
        ROLE_CHANGE_REQUESTED,
        ROLE_CHANGE_APPROVED,
        ROLE_CHANGE_REJECTED,
        LIMIT_CHANGE_REQUESTED,
        LIMIT_CHANGE_APPROVED,
        LIMIT_CHANGE_REJECTED,
        SECURITY_ACCESS_DENIED;

        /** C2 action of a successful decision; {@code null} when the request type has no catalog action. */
        @Nullable
        public static AdminAction decision(ApprovalRequest approval, ApprovalDecision decision) {
            Objects.requireNonNull(approval, "approval is required");
            return switch (approval.requestType()) {
                case ROLE_CHANGE -> decision == ApprovalDecision.APPROVE
                        ? ROLE_CHANGE_APPROVED : ROLE_CHANGE_REJECTED;
                case LIMIT_CHANGE -> decision == ApprovalDecision.APPROVE
                        ? LIMIT_CHANGE_APPROVED : LIMIT_CHANGE_REJECTED;
                default -> null;
            };
        }
    }

    enum AuditClass {
        /** C2 — mandatory, same transaction; failure must roll back the business change. */
        C2,
        /** C3 — best-effort, independent of the business outcome. */
        C3
    }

    /**
     * Non-sensitive audit fact. {@code reasonCode} is an {@link ErrorCode} name (bounded, catalog-defined), never
     * an exception message; {@code entityId} is the approval identifier.
     */
    record AdminAuditEvent(AdminAction action, AuditClass auditClass, long actorUserId, long entityId,
            String requestType, String correlationId, String requestId, String outcome,
            @Nullable String reasonCode) {
    }

    /** C2: join the caller transaction; exceptions propagate so the business transaction rolls back. */
    void recordInTransaction(AdminAuditEvent event);

    /** C3: standalone best-effort; failures are logged and swallowed. */
    void recordStandalone(AdminAuditEvent event);
}
