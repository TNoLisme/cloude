package com.simulatedbank.banking.admin.application;

import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.lang.Nullable;

/**
 * Audit boundary of the Admin module, following {@code design/01} §5.2/§5.3/§5.6.
 *
 * <p>Only catalog action names are emitted — no invented per-reason actions (design/01 §5.4):</p>
 * <ul>
 *   <li>C2 ({@code recordInTransaction}, joins the business transaction, fail-closed): the approval decision
 *       action of the owning group — {@code ROLE_CHANGE_REQUESTED/APPROVED/REJECTED} or
 *       {@code LIMIT_CHANGE_REQUESTED/APPROVED/REJECTED}. A failed write rolls the approval transition,
 *       provider change and idempotency completion back.</li>
 *   <li>C3 ({@code recordStandalone}, best-effort): {@code SECURITY_ACCESS_DENIED} after an authorization,
 *       binding, replay, expiry or provider rejection. The business call already failed; audit failure must
 *       never turn a 403/409/428 into a 500, so it is contained by the adapter.</li>
 * </ul>
 *
 * <p>The event carries only allowlisted, already-masked state (design/01 §5.6): identifiers, bounded enum
 * names, money strings and version counters. Free-text notes, payloads, OTPs, tokens, hashes and challenge
 * internals never reach this port.</p>
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
     * an exception message; {@code entityType}/{@code entityId} follow design/01 §6.3 ({@code APPROVAL_REQUEST}
     * for approval decisions, {@code APP_USER} when a conflict happens before an approval exists).
     */
    record AdminAuditEvent(AdminAction action, AuditClass auditClass, long actorUserId, String actorRole,
            String entityType, String entityId, @Nullable String requestType, @Nullable String targetType,
            @Nullable String targetId, long makerId, @Nullable Long checkerId, @Nullable String decision,
            String correlationId, @Nullable String requestId, String outcome, @Nullable String reasonCode,
            Map<String, Object> beforeState, Map<String, Object> afterState) {

        public AdminAuditEvent {
            Objects.requireNonNull(action, "action is required");
            Objects.requireNonNull(auditClass, "auditClass is required");
            Objects.requireNonNull(entityType, "entityType is required");
            Objects.requireNonNull(entityId, "entityId is required");
            Objects.requireNonNull(correlationId, "correlationId is required");
            Objects.requireNonNull(outcome, "outcome is required");
            beforeState = copy(beforeState);
            afterState = copy(afterState);
        }

        private static Map<String, Object> copy(Map<String, Object> source) {
            return source == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }

        /** C2 {@code *_CHANGE_REQUESTED} for a freshly persisted PENDING approval. */
        public static AdminAuditEvent requested(ApprovalRequest approval, long actorUserId, String actorRole,
                String correlationId, String requestId) {
            AdminAction action = approval.requestType() == com.simulatedbank.banking.admin.domain
                    .ApprovalRequestType.ROLE_CHANGE
                    ? AdminAction.ROLE_CHANGE_REQUESTED : AdminAction.LIMIT_CHANGE_REQUESTED;
            return new AdminAuditEvent(action, AuditClass.C2, actorUserId, actorRole, "APPROVAL_REQUEST",
                    Long.toString(approval.approvalId()), approval.requestType().name(),
                    approval.targetType().name(), approval.targetId(), approval.makerId(), null, null,
                    correlationId, requestId, "SUCCESS", null, null, targetState(approval, null, null,
                            Collections.emptyMap()));
        }

        /** C2 {@code *_CHANGE_APPROVED/REJECTED} for a persisted decision. */
        public static AdminAuditEvent decided(ApprovalRequest approval, ApprovalDecision decision, long checkerId,
                String actorRole, String correlationId, String requestId, Map<String, Object> beforeState,
                Map<String, Object> providerResult) {
            AdminAction action = AdminAction.decision(approval, decision);
            Objects.requireNonNull(action, "decision action");
            return new AdminAuditEvent(action, AuditClass.C2, checkerId, actorRole, "APPROVAL_REQUEST",
                    Long.toString(approval.approvalId()), approval.requestType().name(),
                    approval.targetType().name(), approval.targetId(), approval.makerId(), checkerId,
                    decision == ApprovalDecision.APPROVE ? "APPROVE" : "REJECT", correlationId, requestId,
                    "SUCCESS", null, beforeState,
                    targetState(approval, checkerId, decision == ApprovalDecision.APPROVE ? "APPROVE" : "REJECT",
                            providerResult == null ? Collections.emptyMap() : providerResult));
        }

        /** C3 {@code SECURITY_ACCESS_DENIED} for a known approval. */
        public static AdminAuditEvent denied(long actorUserId, String actorRole, long approvalId,
                @Nullable String requestType, String correlationId, @Nullable String requestId,
                ErrorCode reasonCode) {
            return new AdminAuditEvent(AdminAction.SECURITY_ACCESS_DENIED, AuditClass.C3, actorUserId, actorRole,
                    "APPROVAL_REQUEST", Long.toString(approvalId), requestType, null, null, 0L, null, null,
                    correlationId, requestId, "REJECTED", reasonCode.name(), null, null);
        }

        /** C3 {@code SECURITY_ACCESS_DENIED} when no approval identifier exists (pre-persistence conflict). */
        public static AdminAuditEvent deniedForActor(long actorUserId, String actorRole, String correlationId,
                @Nullable String requestId, ErrorCode reasonCode, String requestType) {
            return new AdminAuditEvent(AdminAction.SECURITY_ACCESS_DENIED, AuditClass.C3, actorUserId, actorRole,
                    "APP_USER", Long.toString(actorUserId), requestType, null, null, 0L, null, null,
                    correlationId, requestId, "REJECTED", reasonCode.name(), null, null);
        }

        /**
         * Allowlisted {@code after_state} for approval events (design/01 §5.5/§5.6): target/decision facts plus
         * the provider's bounded result values. IDs are strings; money values are already canonical strings.
         */
        private static Map<String, Object> targetState(ApprovalRequest approval, @Nullable Long checkerId,
                @Nullable String decision, Map<String, Object> providerResult) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("approvalId", Long.toString(approval.approvalId()));
            state.put("requestType", approval.requestType().name());
            state.put("targetType", approval.targetType().name());
            state.put("targetId", approval.targetId());
            state.put("status", approval.status().name());
            state.put("makerId", Long.toString(approval.makerId()));
            if (checkerId != null) {
                state.put("checkerId", Long.toString(checkerId));
            }
            if (decision != null) {
                state.put("decision", decision);
            }
            state.putAll(providerResult);
            return state;
        }
    }

    /** C2: join the caller transaction; exceptions propagate so the business transaction rolls back. */
    void recordInTransaction(AdminAuditEvent event);

    /** C3: standalone best-effort; failures are logged, counted and swallowed. */
    void recordStandalone(AdminAuditEvent event);
}
