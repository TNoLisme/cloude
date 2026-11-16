package com.simulatedbank.banking.fraud.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Immutable domain representation of a fraud_flag database record.
 */
public record FraudFlagItem(
        Long flagId,
        Long transferId,
        String ruleCode,
        int ruleVersion,
        Severity severity,
        FlagStatus status,
        Map<String, Object> matchedValue,
        Long reviewedBy,
        Instant reviewedAt,
        ReviewDecision decision,
        String notes,
        String correlationId,
        Instant createdAt
) {
    public FraudFlagItem {
        matchedValue = matchedValue == null ? Collections.emptyMap() : Collections.unmodifiableMap(Map.copyOf(matchedValue));
    }
}
