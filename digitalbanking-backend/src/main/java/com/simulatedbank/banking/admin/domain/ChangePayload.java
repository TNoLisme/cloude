package com.simulatedbank.banking.admin.domain;

/**
 * Typed content of one administrative change request. The payload owns the request/target classification so an
 * approval row cannot carry a mismatched target pair.
 *
 * <p>OpenAPI request DTOs stay in the inbound adapter; these records are the Admin-owned domain representation
 * persisted in {@code approval_request.payload}.</p>
 */
public sealed interface ChangePayload permits LimitChangePayload, RoleChangePayload {

    ApprovalRequestType requestType();

    ApprovalTargetType targetType();

    String targetId();

    /** Free-text reason already validated for length/control characters; raw PII must never be stored. */
    String reason();
}
