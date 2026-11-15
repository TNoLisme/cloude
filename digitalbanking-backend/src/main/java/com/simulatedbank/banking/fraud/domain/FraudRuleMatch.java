package com.simulatedbank.banking.fraud.domain;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable match record when a fraud rule conditions are met.
 */
public record FraudRuleMatch(
        String ruleCode,
        int ruleVersion,
        RuleType ruleType,
        Severity severity,
        Map<String, Object> matchedValue
) {
    public FraudRuleMatch {
        matchedValue = matchedValue == null ? Collections.emptyMap() : Collections.unmodifiableMap(Map.copyOf(matchedValue));
    }
}
