package com.simulatedbank.banking.audit.domain;

import java.util.Map;
import java.util.Set;

/** Fail-closed audit action metadata sourced from the audit design catalog. */
public final class AuditActionCatalog {
    public enum Criticality { C1, C2, C3, C4 }

    public record Action(Criticality criticality, Set<String> allowedActorTypes,
                         String entityType, Set<String> requiredFields) {
        public Action {
            allowedActorTypes = Set.copyOf(allowedActorTypes);
            requiredFields = Set.copyOf(requiredFields);
        }
    }

    private static final Set<String> USER = Set.of("USER");
    private static final Set<String> SYSTEM = Set.of("SYSTEM");
    private static final Set<String> USER_OR_SYSTEM = Set.of("USER", "SYSTEM");
    private static final Set<String> TRANSFER_FIELDS = Set.of(
            "amount", "currency", "before_state.status", "after_state.status");
    private static final Set<String> STATUS_FIELDS = Set.of("before_state.status", "after_state.status", "after_state.reasonCode");
    private static final Set<String> CHALLENGE_FIELDS = Set.of("after_state.purpose", "after_state.method",
            "after_state.status", "after_state.attemptCount");
    private static final Set<String> APPROVAL_REQUEST_FIELDS = Set.of("after_state.requestType", "after_state.status",
            "after_state.targetType", "after_state.targetId", "after_state.makerId");
    private static final Set<String> APPROVAL_DECISION_FIELDS = Set.of("after_state.requestType", "after_state.status",
            "after_state.targetType", "after_state.targetId", "after_state.makerId",
            "after_state.checkerId", "after_state.decision");
    private static final Map<String, Action> ACTIONS = Map.ofEntries(
            Map.entry("AUTH_LOGIN_SUCCESS", new Action(Criticality.C2, USER, "APP_USER",
                    Set.of("after_state.reasonCode", "client_info_masked"))),
            Map.entry("AUTH_LOGIN_FAILED", new Action(Criticality.C3, USER_OR_SYSTEM, "APP_USER", Set.of("after_state.reasonCode"))),
            Map.entry("AUTH_ACCOUNT_LOCKED", new Action(Criticality.C3, SYSTEM, "APP_USER", Set.of("after_state.reasonCode"))),
            Map.entry("AUTH_SESSION_LOGOUT", new Action(Criticality.C3, USER, "SESSION", Set.of("after_state.reasonCode"))),
            Map.entry("AUTH_CHALLENGE_CREATED", new Action(Criticality.C2, USER, "AUTH_CHALLENGE", CHALLENGE_FIELDS)),
            Map.entry("AUTH_CHALLENGE_RESENT", new Action(Criticality.C2, USER, "AUTH_CHALLENGE", CHALLENGE_FIELDS)),
            Map.entry("AUTH_CHALLENGE_LOCKED", new Action(Criticality.C3, SYSTEM, "AUTH_CHALLENGE", Set.of("after_state.status", "after_state.attemptCount"))),
            Map.entry("AUTH_STEP_UP_VERIFIED", new Action(Criticality.C2, USER, "AUTH_CHALLENGE", CHALLENGE_FIELDS)),
            Map.entry("AUTH_STEP_UP_FAILED", new Action(Criticality.C3, USER, "AUTH_CHALLENGE", Set.of("after_state.reasonCode"))),
            Map.entry("TRANSFER_CREATED", new Action(Criticality.C1, USER, "TRANSFER",
                    Set.of("amount", "currency", "after_state.status"))),
            Map.entry("TRANSFER_COMPLETED", new Action(Criticality.C1, USER, "TRANSFER", TRANSFER_FIELDS)),
            Map.entry("TRANSFER_FAILED", new Action(Criticality.C1, USER, "TRANSFER", TRANSFER_FIELDS)),
            Map.entry("TRANSFER_RECONCILED", new Action(Criticality.C1, SYSTEM, "TRANSFER", TRANSFER_FIELDS)),
            Map.entry("SECURITY_IDEMPOTENCY_CONFLICT", new Action(Criticality.C3, USER, "TRANSFER", Set.of("after_state.reasonCode"))),
            Map.entry("LIMIT_CHANGE_REQUESTED", new Action(Criticality.C2, USER, "APPROVAL_REQUEST", APPROVAL_REQUEST_FIELDS)),
            Map.entry("LIMIT_CHANGE_APPROVED", new Action(Criticality.C2, USER, "APPROVAL_REQUEST", APPROVAL_DECISION_FIELDS)),
            Map.entry("LIMIT_CHANGE_REJECTED", new Action(Criticality.C2, USER, "APPROVAL_REQUEST", APPROVAL_DECISION_FIELDS)),
            Map.entry("ROLE_CHANGE_REQUESTED", new Action(Criticality.C2, USER, "APPROVAL_REQUEST", APPROVAL_REQUEST_FIELDS)),
            Map.entry("ROLE_CHANGE_APPROVED", new Action(Criticality.C2, USER, "APPROVAL_REQUEST", APPROVAL_DECISION_FIELDS)),
            Map.entry("ROLE_CHANGE_REJECTED", new Action(Criticality.C2, USER, "APPROVAL_REQUEST", APPROVAL_DECISION_FIELDS)),
            Map.entry("ACCOUNT_STATUS_CHANGED", new Action(Criticality.C2, USER, "ACCOUNT", STATUS_FIELDS)),
            Map.entry("FRAUD_FLAG_CREATED", new Action(Criticality.C2, SYSTEM, "FRAUD_FLAG", Set.of("after_state.transferReference", "after_state.ruleCode", "after_state.severity", "after_state.status"))),
            Map.entry("FRAUD_FLAG_REVIEWED", new Action(Criticality.C2, USER, "FRAUD_FLAG", Set.of("after_state.transferReference", "after_state.ruleCode", "after_state.severity", "after_state.decision", "after_state.status"))),
            Map.entry("FRAUD_ENGINE_ERROR", new Action(Criticality.C4, SYSTEM, "TRANSFER", Set.of("after_state.reasonCode"))),
            Map.entry("LEGAL_HOLD_PLACED", new Action(Criticality.C2, USER, "LEGAL_HOLD", Set.of("after_state.entityType", "after_state.entityId", "after_state.status", "after_state.reasonCode"))),
            Map.entry("LEGAL_HOLD_RELEASED", new Action(Criticality.C2, USER, "LEGAL_HOLD", Set.of("after_state.entityType", "after_state.entityId", "after_state.status", "after_state.reasonCode"))),
            Map.entry("RETENTION_POLICY_CHANGED", new Action(Criticality.C2, USER, "RETENTION_POLICY", Set.of("after_state.dataClass", "after_state.enabled"))),
            Map.entry("RETENTION_ARCHIVED", new Action(Criticality.C4, SYSTEM, "RETENTION_POLICY", Set.of("after_state.dataClass"))),
            Map.entry("RETENTION_PURGED", new Action(Criticality.C2, SYSTEM, "RETENTION_POLICY", Set.of("after_state.dataClass", "after_state.checkpointId"))),
            Map.entry("AUDIT_EXPORTED", new Action(Criticality.C2, USER, "AUDIT_LOG", Set.of("after_state.exportId", "after_state.rowCount", "after_state.from", "after_state.to", "after_state.format", "after_state.fileHash", "after_state.filterHash"))),
            Map.entry("SECURITY_ACCESS_DENIED", new Action(Criticality.C3, USER_OR_SYSTEM, "SYSTEM", Set.of("after_state.reasonCode"))));

    private AuditActionCatalog() { }

    public static Action require(String action) {
        Action entry = action == null ? null : ACTIONS.get(action);
        if (entry == null) throw new IllegalArgumentException("Unknown or unsupported audit action");
        return entry;
    }

    public static boolean requiresClientInfo(String action) {
        return "AUTH_LOGIN_SUCCESS".equals(action);
    }

    public static boolean requiresOwnerReview(String action) {
        return "RETENTION_PURGED".equals(action) || "SECURITY_ACCESS_DENIED".equals(action);
    }
}
