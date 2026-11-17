package com.simulatedbank.banking.fraud.domain;

/**
 * Baseline fraud flag status from 04 §4.7.2.
 */
public enum FlagStatus {
    OPEN,
    UNDER_REVIEW,
    ALLOWED,
    BLOCKED,
    CLOSED;

    public boolean isTerminal() {
        return this == ALLOWED || this == BLOCKED || this == CLOSED;
    }
}
