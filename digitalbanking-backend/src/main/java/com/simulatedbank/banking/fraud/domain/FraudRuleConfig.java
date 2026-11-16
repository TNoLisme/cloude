package com.simulatedbank.banking.fraud.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable fraud rule configuration from 04 §4.7.1.
 */
public record FraudRuleConfig(
        String ruleCode,
        int version,
        String ruleName,
        RuleType ruleType,
        BigDecimal thresholdAmount,
        Integer thresholdCount,
        Integer windowSeconds,
        Severity severity,
        boolean enabled,
        Instant effectiveFrom,
        Instant effectiveTo,
        Long createdBy,
        Long approvedBy,
        Instant createdAt,
        Instant updatedAt
) {
    public FraudRuleConfig {
        if (ruleCode == null || ruleCode.isBlank()) {
            throw new IllegalArgumentException("ruleCode cannot be null or blank");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (ruleType == null) {
            throw new IllegalArgumentException("ruleType cannot be null");
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity cannot be null");
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("effectiveFrom cannot be null");
        }
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        }

        // Baseline checks from 04 §4.7.1
        if (ruleType == RuleType.LARGE_VALUE) {
            if (thresholdAmount == null || thresholdAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("thresholdAmount must be positive for LARGE_VALUE rule");
            }
        } else if (ruleType == RuleType.HIGH_FREQUENCY) {
            if (thresholdCount == null || thresholdCount <= 0) {
                throw new IllegalArgumentException("thresholdCount must be positive for HIGH_FREQUENCY rule");
            }
            if (windowSeconds == null || windowSeconds <= 0) {
                throw new IllegalArgumentException("windowSeconds must be positive for HIGH_FREQUENCY rule");
            }
        }
    }

    public boolean isEffectiveAt(Instant timestamp) {
        if (!enabled) {
            return false;
        }
        if (timestamp.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || timestamp.isBefore(effectiveTo);
    }
}
