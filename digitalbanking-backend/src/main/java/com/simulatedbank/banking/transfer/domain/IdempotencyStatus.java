package com.simulatedbank.banking.transfer.domain;

/** Enum names are the stable values stored in idempotency_record.status. */
public enum IdempotencyStatus {
    RECEIVED, IN_PROGRESS, COMPLETED, FAILED;

    /** Optional transient T2 marker, not a claim or lease. */
    public IdempotencyStatus startProcessing() {
        if (this != RECEIVED) {
            throw new IllegalStateException("Only a received record can start processing");
        }
        return IN_PROGRESS;
    }

    public IdempotencyStatus complete() {
        requireNonterminal();
        return COMPLETED;
    }

    public IdempotencyStatus fail() {
        requireNonterminal();
        return FAILED;
    }

    private void requireNonterminal() {
        if (this == COMPLETED || this == FAILED) {
            throw new IllegalStateException("Terminal idempotency status cannot change");
        }
    }
}
