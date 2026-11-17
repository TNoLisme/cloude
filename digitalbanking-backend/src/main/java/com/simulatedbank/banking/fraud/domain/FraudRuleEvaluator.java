package com.simulatedbank.banking.fraud.domain;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pure, deterministic rule evaluator for T2 transaction evaluation.
 */
public class FraudRuleEvaluator {

    private final FraudMatchedValueValidator matchedValueValidator = new FraudMatchedValueValidator();

    public FraudEvaluationResult evaluate(
            FraudTransferSnapshot snapshot,
            List<FraudRuleConfig> ruleConfigs,
            Instant evaluationTime,
            FrequencyComparator frequencyComparator
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot cannot be null");
        }
        if (evaluationTime == null) {
            throw new IllegalArgumentException("evaluationTime cannot be null");
        }
        FrequencyComparator comparator = frequencyComparator == null ? FrequencyComparator.GREATER_THAN : frequencyComparator;

        if (ruleConfigs == null || ruleConfigs.isEmpty()) {
            return new FraudEvaluationResult(snapshot.transferId(), false, Collections.emptyList(), evaluationTime);
        }

        // 1. Group by ruleCode and pick the active version with highest version number that is effective at evaluationTime
        Map<String, Optional<FraudRuleConfig>> activeRulesByCode = ruleConfigs.stream()
                .filter(c -> c.isEffectiveAt(evaluationTime))
                .collect(Collectors.groupingBy(
                        FraudRuleConfig::ruleCode,
                        Collectors.maxBy(Comparator.comparingInt(FraudRuleConfig::version))
                ));

        List<FraudRuleMatch> matches = new ArrayList<>();

        for (Optional<FraudRuleConfig> optConfig : activeRulesByCode.values()) {
            if (optConfig.isEmpty()) {
                continue;
            }
            FraudRuleConfig config = optConfig.get();

            if (config.ruleType() == RuleType.LARGE_VALUE) {
                if (snapshot.amount().compareTo(config.thresholdAmount()) >= 0) {
                    Map<String, Object> matchedValue = Map.of(
                            "amount", snapshot.amount(),
                            "thresholdAmount", config.thresholdAmount(),
                            "currency", snapshot.currency()
                    );
                    if (matchedValueValidator.isSafe(matchedValue)) {
                        matches.add(new FraudRuleMatch(
                                config.ruleCode(),
                                config.version(),
                                config.ruleType(),
                                config.severity(),
                                matchedValue
                        ));
                    }
                }
            } else if (config.ruleType() == RuleType.HIGH_FREQUENCY) {
                // Replay exclusion
                if (!snapshot.isReplay()) {
                    if (comparator.matches(snapshot.windowTransactionCount(), config.thresholdCount())) {
                        Map<String, Object> matchedValue = Map.of(
                                "count", snapshot.windowTransactionCount(),
                                "thresholdCount", config.thresholdCount(),
                                "windowSeconds", config.windowSeconds(),
                                "comparator", comparator.name()
                        );
                        if (matchedValueValidator.isSafe(matchedValue)) {
                            matches.add(new FraudRuleMatch(
                                    config.ruleCode(),
                                    config.version(),
                                    config.ruleType(),
                                    config.severity(),
                                    matchedValue
                            ));
                        }
                    }
                }
            }
            // UNUSUAL_PATTERN remains proposed/disabled in MVP baseline
        }

        return new FraudEvaluationResult(
                snapshot.transferId(),
                !matches.isEmpty(),
                matches,
                evaluationTime
        );
    }
}
