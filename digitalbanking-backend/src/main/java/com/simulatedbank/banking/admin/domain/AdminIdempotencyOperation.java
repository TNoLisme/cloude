package com.simulatedbank.banking.admin.domain;

/**
 * Logical Admin write operation covered by idempotency. The operation is part of the key scope so one key can
 * never silently replay an unrelated workflow.
 */
public enum AdminIdempotencyOperation {
    CREATE_LIMIT_PROPOSAL,
    CREATE_ROLE_PROPOSAL,
    DECIDE_APPROVAL
}
