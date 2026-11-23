package com.simulatedbank.banking.audit.api;

/** Actor kind of an audit event (design/01 §4.3); {@code USER} requires an actor user id. */
public enum AuditActorType {
    USER,
    SYSTEM
}
