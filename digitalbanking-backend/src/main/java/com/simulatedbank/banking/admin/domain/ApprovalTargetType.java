package com.simulatedbank.banking.admin.domain;

/** Target types allowed by the canonical {@code banking.approval_request} schema (04 §4.5.3). */
public enum ApprovalTargetType {
    CUSTOMER,
    ACCOUNT,
    APP_USER,
    DATA_SCOPE,
    RETENTION_POLICY
}
