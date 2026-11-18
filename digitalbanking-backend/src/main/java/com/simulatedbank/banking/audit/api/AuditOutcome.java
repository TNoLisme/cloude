package com.simulatedbank.banking.audit.api;

/** Wire outcome of an audit event (design/01 §5.4). {@code REJECTED} is a denial, never a technical failure. */
public enum AuditOutcome {
    SUCCESS,
    FAILURE,
    REJECTED
}
