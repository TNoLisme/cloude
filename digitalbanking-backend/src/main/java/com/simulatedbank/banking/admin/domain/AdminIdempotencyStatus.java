package com.simulatedbank.banking.admin.domain;

/** Persisted lifecycle of one Admin idempotency record (07 §5.3, design/02 §6.3). */
public enum AdminIdempotencyStatus {
    /** Claimed inside the business transaction; never committed without becoming terminal. */
    RECEIVED,
    /** Business operation and replay response committed together. */
    COMPLETED,
    /** Schema-reserved for a future durable failure outcome; the synchronous path rolls back instead. */
    FAILED;

    public boolean terminal() {
        return this != RECEIVED;
    }
}
