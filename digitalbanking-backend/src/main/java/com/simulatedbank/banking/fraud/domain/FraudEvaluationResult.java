package com.simulatedbank.banking.fraud.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Immutable evaluation result returned to Transfer caller inside T2.
 */
public record FraudEvaluationResult(
        Long transferId,
        boolean flagged,
        List<FraudRuleMatch> matches,
        Instant evaluatedAt
) {
    public FraudEvaluationResult {
        matches = matches == null ? Collections.emptyList() : Collections.unmodifiableList(List.copyOf(matches));
    }
}
