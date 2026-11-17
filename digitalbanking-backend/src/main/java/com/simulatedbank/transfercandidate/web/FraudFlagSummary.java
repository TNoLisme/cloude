package com.simulatedbank.transfercandidate.web;

/** Fraud flag summary for authorized staff (API contracts §7.2.11). */
public record FraudFlagSummary(String flagId, String ruleCode, int ruleVersion, String severity, String status) {
}