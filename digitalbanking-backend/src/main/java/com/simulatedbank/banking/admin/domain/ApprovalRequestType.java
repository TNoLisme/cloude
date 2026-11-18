package com.simulatedbank.banking.admin.domain;

/** Request types allowed by the canonical {@code banking.approval_request} schema (04 §4.5.3). */
public enum ApprovalRequestType {
    LIMIT_CHANGE,
    ROLE_CHANGE,
    RETENTION_HOLD,
    RETENTION_POLICY_CHANGE;

    /** Only the two request types with an approved API/BA contract are open in the MVP. */
    public boolean openInMvp() {
        return this == LIMIT_CHANGE || this == ROLE_CHANGE;
    }
}
