package com.simulatedbank.banking.fraud.domain;

/**
 * Review decisions from 04 §4.7.2 and API contracts 07 §8.7.2.
 */
public enum ReviewDecision {
    ALLOW,
    BLOCK,
    ESCALATE,
    CLOSE;

    public FlagStatus toTargetStatus() {
        return switch (this) {
            case ALLOW -> FlagStatus.ALLOWED;
            case BLOCK -> FlagStatus.BLOCKED;
            case ESCALATE -> FlagStatus.UNDER_REVIEW;
            case CLOSE -> FlagStatus.CLOSED;
        };
    }
}
