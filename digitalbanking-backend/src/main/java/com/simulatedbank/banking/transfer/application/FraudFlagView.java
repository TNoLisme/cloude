package com.simulatedbank.banking.transfer.application;

/** Fraud flag summary exposed only to staff holding FRAUD_READ/FRAUD_REVIEW (API contracts §7.2.11). */
public record FraudFlagView(long flagId, String ruleCode, int ruleVersion, String severity, String status) {
    public FraudFlagView {
        if (flagId <= 0 || ruleVersion <= 0) throw new IllegalArgumentException("Invalid fraud flag identity");
        if (ruleCode == null || ruleCode.isBlank() || severity == null || status == null) {
            throw new IllegalArgumentException("Invalid fraud flag");
        }
    }
}