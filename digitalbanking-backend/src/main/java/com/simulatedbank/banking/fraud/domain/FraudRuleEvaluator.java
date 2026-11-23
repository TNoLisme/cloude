package com.simulatedbank.banking.fraud.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntUnaryOperator;
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

        var matches = activeRulesByCode.values().stream().flatMap(Optional::stream)
                .filter(config -> config.ruleType() != RuleType.UNUSUAL_PATTERN)
                .map(config -> match(snapshot, config, comparator, seconds -> snapshot.windowTransactionCount(), false))
                .flatMap(Optional::stream).filter(match -> matchedValueValidator.isSafe(match.matchedValue())).toList();
        return new FraudEvaluationResult(snapshot.transferId(), !matches.isEmpty(), matches, evaluationTime);
    }

    /** The JDBC provider supplies the count for this exact version's window. */
    public Optional<FraudRuleMatch> evaluateRule(FraudTransferSnapshot snapshot, FraudRuleConfig config,
                                                 IntUnaryOperator countInWindow) {
        if (config.ruleType() == RuleType.UNUSUAL_PATTERN
                && (config.thresholdAmount() == null || config.thresholdAmount().signum() <= 0
                || config.thresholdCount() == null || config.thresholdCount() <= 0
                || config.windowSeconds() == null || config.windowSeconds() <= 0)) {
            throw new IllegalStateException("Invalid enabled unusual-pattern rule");
        }
        var result = match(snapshot, config, FrequencyComparator.GREATER_THAN, countInWindow, true);
        result.ifPresent(value -> matchedValueValidator.validate(value.matchedValue()));
        return result;
    }

    private Optional<FraudRuleMatch> match(FraudTransferSnapshot snapshot, FraudRuleConfig config,
                                          FrequencyComparator comparator, IntUnaryOperator countInWindow,
                                          boolean includeWindowBounds) {
        Map<String, Object> evidence = new HashMap<>();
        if (config.ruleType() != RuleType.HIGH_FREQUENCY) {
            if (snapshot.amount().compareTo(config.thresholdAmount()) < 0) return Optional.empty();
            evidence.put("amount", snapshot.amount());
            evidence.put("thresholdAmount", config.thresholdAmount());
            evidence.put("currency", snapshot.currency());
        }

        if (config.ruleType() != RuleType.LARGE_VALUE) {
            if (snapshot.isReplay()) return Optional.empty();
            int count = countInWindow.applyAsInt(config.windowSeconds());
            if (!comparator.matches(count, config.thresholdCount())) return Optional.empty();
            evidence.put("count", count);
            evidence.put("thresholdCount", config.thresholdCount());
            evidence.put("windowSeconds", config.windowSeconds());
            evidence.put("comparator", comparator.name());
            if (includeWindowBounds) {
                evidence.put("windowStartExclusive", snapshot.occurredAt().minusSeconds(config.windowSeconds()).toString());
                evidence.put("windowEndInclusive", snapshot.occurredAt().toString());
            }
        }
        return Optional.of(new FraudRuleMatch(config.ruleCode(), config.version(),
                config.ruleType(), config.severity(), evidence));
    }
}
