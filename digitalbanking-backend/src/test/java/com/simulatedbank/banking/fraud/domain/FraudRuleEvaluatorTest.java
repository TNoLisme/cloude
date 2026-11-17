package com.simulatedbank.banking.fraud.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudRuleEvaluatorTest {

    private final FraudRuleEvaluator evaluator = new FraudRuleEvaluator();
    private final Instant now = Instant.parse("2026-11-15T10:00:00Z");

    @Test
    @DisplayName("1. Large-value below threshold does not match")
    void largeValueBelowThresholdDoesNotMatch() {
        FraudRuleConfig config = createLargeValueConfig("LARGE_TRANSFER", 1, new BigDecimal("5000000"), true);
        FraudTransferSnapshot snapshot = createSnapshot(new BigDecimal("4999999"), 1, false);

        FraudEvaluationResult result = evaluator.evaluate(snapshot, List.of(config), now, FrequencyComparator.GREATER_THAN);

        assertThat(result.flagged()).isFalse();
        assertThat(result.matches()).isEmpty();
    }

    @Test
    @DisplayName("2. Large-value equal to threshold matches (amount >= threshold)")
    void largeValueEqualToThresholdMatches() {
        FraudRuleConfig config = createLargeValueConfig("LARGE_TRANSFER", 1, new BigDecimal("5000000"), true);
        FraudTransferSnapshot snapshot = createSnapshot(new BigDecimal("5000000"), 1, false);

        FraudEvaluationResult result = evaluator.evaluate(snapshot, List.of(config), now, FrequencyComparator.GREATER_THAN);

        assertThat(result.flagged()).isTrue();
        assertThat(result.matches()).hasSize(1);
        FraudRuleMatch match = result.matches().get(0);
        assertThat(match.ruleCode()).isEqualTo("LARGE_TRANSFER");
        assertThat(match.ruleVersion()).isEqualTo(1);
        assertThat(match.ruleType()).isEqualTo(RuleType.LARGE_VALUE);
        assertThat(match.matchedValue()).containsEntry("amount", new BigDecimal("5000000"));
        assertThat(match.matchedValue()).containsEntry("thresholdAmount", new BigDecimal("5000000"));
    }

    @Test
    @DisplayName("3. Large-value above threshold matches")
    void largeValueAboveThresholdMatches() {
        FraudRuleConfig config = createLargeValueConfig("LARGE_TRANSFER", 1, new BigDecimal("5000000"), true);
        FraudTransferSnapshot snapshot = createSnapshot(new BigDecimal("10000000"), 1, false);

        FraudEvaluationResult result = evaluator.evaluate(snapshot, List.of(config), now, FrequencyComparator.GREATER_THAN);

        assertThat(result.flagged()).isTrue();
        assertThat(result.matches()).hasSize(1);
    }

    @Test
    @DisplayName("4. Disabled rule does not evaluate")
    void disabledRuleDoesNotMatch() {
        FraudRuleConfig config = createLargeValueConfig("LARGE_TRANSFER", 1, new BigDecimal("5000000"), false);
        FraudTransferSnapshot snapshot = createSnapshot(new BigDecimal("10000000"), 1, false);

        FraudEvaluationResult result = evaluator.evaluate(snapshot, List.of(config), now, FrequencyComparator.GREATER_THAN);

        assertThat(result.flagged()).isFalse();
    }

    @Test
    @DisplayName("5. Effective-from and effective-to boundary checks")
    void effectiveTimeBoundaries() {
        // Future rule (not yet effective)
        FraudRuleConfig futureRule = new FraudRuleConfig(
                "LARGE_TRANSFER", 1, "Large Transfer", RuleType.LARGE_VALUE,
                new BigDecimal("5000000"), null, null, Severity.HIGH, true,
                now.plusSeconds(3600), null, 1L, 2L, now, now
        );
        FraudTransferSnapshot snapshot = createSnapshot(new BigDecimal("6000000"), 1, false);
        assertThat(evaluator.evaluate(snapshot, List.of(futureRule), now, FrequencyComparator.GREATER_THAN).flagged()).isFalse();

        // Expired rule
        FraudRuleConfig expiredRule = new FraudRuleConfig(
                "LARGE_TRANSFER", 1, "Large Transfer", RuleType.LARGE_VALUE,
                new BigDecimal("5000000"), null, null, Severity.HIGH, true,
                now.minusSeconds(7200), now.minusSeconds(3600), 1L, 2L, now, now
        );
        assertThat(evaluator.evaluate(snapshot, List.of(expiredRule), now, FrequencyComparator.GREATER_THAN).flagged()).isFalse();
    }

    @Test
    @DisplayName("6. Active rule version selection when multiple versions exist")
    void activeVersionSelection() {
        // Version 1 expired, Version 2 active
        FraudRuleConfig v1 = new FraudRuleConfig(
                "LARGE_TRANSFER", 1, "Large Transfer v1", RuleType.LARGE_VALUE,
                new BigDecimal("2000000"), null, null, Severity.MEDIUM, true,
                now.minusSeconds(7200), now.minusSeconds(60), 1L, 2L, now, now
        );
        FraudRuleConfig v2 = new FraudRuleConfig(
                "LARGE_TRANSFER", 2, "Large Transfer v2", RuleType.LARGE_VALUE,
                new BigDecimal("5000000"), null, null, Severity.HIGH, true,
                now.minusSeconds(60), null, 1L, 2L, now, now
        );

        // 3M is above v1 (2M) but below v2 (5M). Active rule is v2, so should NOT match!
        FraudTransferSnapshot snapshot = createSnapshot(new BigDecimal("3000000"), 1, false);
        FraudEvaluationResult result = evaluator.evaluate(snapshot, List.of(v1, v2), now, FrequencyComparator.GREATER_THAN);
        assertThat(result.flagged()).isFalse();

        // 6M matches v2
        FraudTransferSnapshot snapshot2 = createSnapshot(new BigDecimal("6000000"), 1, false);
        FraudEvaluationResult result2 = evaluator.evaluate(snapshot2, List.of(v1, v2), now, FrequencyComparator.GREATER_THAN);
        assertThat(result2.flagged()).isTrue();
        assertThat(result2.matches().get(0).ruleVersion()).isEqualTo(2);
    }

    @ParameterizedTest(name = "GREATER_THAN fixture: count={0}, threshold=5, expectedMatch={1}")
    @CsvSource({
            "4, false", // N - 1
            "5, false", // N
            "6, true"   // N + 1
    })
    @DisplayName("7. High-frequency GREATER_THAN comparator boundary (SRS/04 proposal >5 in 10m)")
    void highFrequencyGreaterThanComparator(int count, boolean expectedMatch) {
        FraudRuleConfig config = createHighFrequencyConfig("HIGH_FREQ", 1, 5, 600, true);
        FraudTransferSnapshot snapshot = createSnapshot(new BigDecimal("100000"), count, false);

        FraudEvaluationResult result = evaluator.evaluate(snapshot, List.of(config), now, FrequencyComparator.GREATER_THAN);

        assertThat(result.flagged()).isEqualTo(expectedMatch);
        if (expectedMatch) {
            FraudRuleMatch match = result.matches().get(0);
            assertThat(match.matchedValue()).containsEntry("count", count);
            assertThat(match.matchedValue()).containsEntry("thresholdCount", 5);
            assertThat(match.matchedValue()).containsEntry("windowSeconds", 600);
            assertThat(match.matchedValue()).containsEntry("comparator", "GREATER_THAN");
        }
    }

    @ParameterizedTest(name = "GREATER_OR_EQUAL fixture: count={0}, threshold=3, expectedMatch={1}")
    @CsvSource({
            "2, false", // N - 1
            "3, true",  // N
            "4, true"   // N + 1
    })
    @DisplayName("8. High-frequency GREATER_OR_EQUAL comparator boundary (Design-B 3rd txn in 120s)")
    void highFrequencyGreaterOrEqualComparator(int count, boolean expectedMatch) {
        FraudRuleConfig config = createHighFrequencyConfig("VELOCITY_ALERT", 1, 3, 120, true);
        FraudTransferSnapshot snapshot = createSnapshot(new BigDecimal("100000"), count, false);

        FraudEvaluationResult result = evaluator.evaluate(snapshot, List.of(config), now, FrequencyComparator.GREATER_OR_EQUAL);

        assertThat(result.flagged()).isEqualTo(expectedMatch);
        if (expectedMatch) {
            FraudRuleMatch match = result.matches().get(0);
            assertThat(match.matchedValue()).containsEntry("count", count);
            assertThat(match.matchedValue()).containsEntry("thresholdCount", 3);
            assertThat(match.matchedValue()).containsEntry("windowSeconds", 120);
            assertThat(match.matchedValue()).containsEntry("comparator", "GREATER_OR_EQUAL");
        }
    }

    @Test
    @DisplayName("9. High-frequency replay exclusion fixture suppresses trigger")
    void highFrequencyReplayExclusion() {
        FraudRuleConfig config = createHighFrequencyConfig("VELOCITY_ALERT", 1, 3, 120, true);
        // Even if count is 5, isReplay = true suppresses the velocity rule trigger
        FraudTransferSnapshot snapshot = createSnapshot(new BigDecimal("100000"), 5, true);

        FraudEvaluationResult result = evaluator.evaluate(snapshot, List.of(config), now, FrequencyComparator.GREATER_OR_EQUAL);

        assertThat(result.flagged()).isFalse();
    }

    @Test
    @DisplayName("10. Matched-value allowlist strictly validates fields and rejects PII")
    void matchedValueAllowlistEnforcement() {
        FraudMatchedValueValidator validator = new FraudMatchedValueValidator();

        // Valid matched values
        assertThat(validator.isSafe(Map.of("amount", "5000000", "currency", "VND", "thresholdAmount", "5000000"))).isTrue();
        assertThat(validator.isSafe(Map.of("count", 3, "thresholdCount", 3, "windowSeconds", 120, "comparator", "GREATER_OR_EQUAL"))).isTrue();

        // Rejects unmasked account / phone / email / credentials
        assertThat(validator.isSafe(Map.of("account", "123456789012"))).isFalse();
        assertThat(validator.isSafe(Map.of("account", "1234 5678 9012"))).isFalse();
        assertThat(validator.isSafe(Map.of("account", "1234-5678-9012"))).isFalse();
        assertThat(validator.isSafe(Map.of("phone", "+84912345678"))).isFalse();
        assertThat(validator.isSafe(Map.of("email", "attacker@bank.com"))).isFalse();
        assertThat(validator.isSafe(Map.of("otp", "123456"))).isFalse();
        assertThat(validator.isSafe(Map.of("password", "secret"))).isFalse();
        assertThat(validator.isSafe(Map.of("freeTextNote", "Suspicious operation note"))).isFalse();
    }

    @Test
    @DisplayName("11. Input snapshot is immutable and deterministic across runs")
    void determinismAndImmutability() {
        FraudRuleConfig config = createLargeValueConfig("LARGE_TRANSFER", 1, new BigDecimal("5000000"), true);
        FraudTransferSnapshot snapshot = createSnapshot(new BigDecimal("7000000"), 1, false);

        FraudEvaluationResult run1 = evaluator.evaluate(snapshot, List.of(config), now, FrequencyComparator.GREATER_THAN);
        FraudEvaluationResult run2 = evaluator.evaluate(snapshot, List.of(config), now, FrequencyComparator.GREATER_THAN);

        assertThat(run1).isEqualTo(run2);
        assertThat(snapshot.amount()).isEqualTo(new BigDecimal("7000000"));
    }

    @Test
    @DisplayName("12. Invalid rule config fails validation")
    void invalidRuleConfigFailsValidation() {
        // LARGE_VALUE missing threshold_amount
        assertThatThrownBy(() -> new FraudRuleConfig(
                "LARGE_VAL", 1, "Large Value", RuleType.LARGE_VALUE,
                null, null, null, Severity.HIGH, true,
                now, null, 1L, 2L, now, now
        )).isInstanceOf(IllegalArgumentException.class);

        // HIGH_FREQUENCY missing threshold_count or window_seconds
        assertThatThrownBy(() -> new FraudRuleConfig(
                "HIGH_FREQ", 1, "High Freq", RuleType.HIGH_FREQUENCY,
                null, null, 600, Severity.MEDIUM, true,
                now, null, 1L, 2L, now, now
        )).isInstanceOf(IllegalArgumentException.class);

        // effectiveTo before effectiveFrom
        assertThatThrownBy(() -> new FraudRuleConfig(
                "RULE1", 1, "Rule", RuleType.LARGE_VALUE,
                new BigDecimal("1000"), null, null, Severity.LOW, true,
                now, now.minusSeconds(10), 1L, 2L, now, now
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private FraudRuleConfig createLargeValueConfig(String code, int version, BigDecimal threshold, boolean enabled) {
        return new FraudRuleConfig(
                code, version, "Large Value Rule", RuleType.LARGE_VALUE,
                threshold, null, null, Severity.HIGH, enabled,
                now.minusSeconds(3600), null, 1L, 2L, now, now
        );
    }

    private FraudRuleConfig createHighFrequencyConfig(String code, int version, int thresholdCount, int windowSeconds, boolean enabled) {
        return new FraudRuleConfig(
                code, version, "High Frequency Rule", RuleType.HIGH_FREQUENCY,
                null, thresholdCount, windowSeconds, Severity.MEDIUM, enabled,
                now.minusSeconds(3600), null, 1L, 2L, now, now
        );
    }

    private FraudTransferSnapshot createSnapshot(BigDecimal amount, int windowCount, boolean isReplay) {
        return new FraudTransferSnapshot(
                8001L,
                1001L,
                2001L,
                3001L,
                amount,
                "VND",
                now,
                windowCount,
                isReplay,
                "corr-transfer-8001"
        );
    }
}
