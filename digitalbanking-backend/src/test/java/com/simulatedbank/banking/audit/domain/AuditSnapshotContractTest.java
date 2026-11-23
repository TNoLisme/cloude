package com.simulatedbank.banking.audit.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AuditSnapshotContractTest {
    @Test
    void normalizesIdsAndMoneyBeforeBothPersistenceAndHashing() {
        var event = transfer(Map.of("status", "COMPLETED", "sourceAccountId", Long.MAX_VALUE,
                "amount", new BigDecimal("150000.5"), "currency", "VND"));
        assertThat(event.afterState()).containsEntry("sourceAccountId", "9223372036854775807")
                .containsEntry("amount", "150000.50");
        assertThat(AuditHashing.canonicalPayload(event, Instant.parse("2026-11-03T10:15:31.123456Z")))
                .contains("\"sourceAccountId\":\"9223372036854775807\"", "\"amount\":\"150000.50\"");
    }

    @Test
    void rejectsMalformedMoneyWithoutRoundingOrExponentExpansion() {
        for (Object value : List.of("user@example.com", "1e9", "1.001", "-1.00", new BigDecimal("1E+1000000"))) {
            assertThatThrownBy(() -> AuditEventValidator.validate(transfer(Map.of("status", "COMPLETED", "amount", value))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> AuditEventValidator.validateAmountAndCurrency(new BigDecimal("100000000000000000.00"), "VND"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsCounterAsIntegerAndRejectsNonIntegerCounter() {
        var good = challenge(2L);
        assertThatCode(() -> AuditEventValidator.validate(good)).doesNotThrowAnyException();
        assertThat(good.afterState()).containsEntry("attemptCount", 2L);
        for (Object value : List.of("2", -1, 9_007_199_254_740_992L)) {
            assertThatThrownBy(() -> AuditEventValidator.validate(challenge(value))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void boundsTotalCanonicalUtf8PayloadNotEachSnapshotSeparately() {
        var roles = java.util.Collections.nCopies(320, "ROLE_" + "A".repeat(55));
        var state = Map.<String, Object>of("reasonCode", "LOGIN_OK", "roleCodes", roles);
        var event = new AuditEvent("AUTH_LOGIN_SUCCESS", "USER", 7L, "CUSTOMER", "APP_USER", "7",
                state, state, null, null, "corr-1", "req-1", "SUCCESS", Map.of());
        assertThatThrownBy(() -> AuditHashing.canonicalPayload(event, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32 KiB");
    }

    @Test
    void rejectsNestedSecretsAndInvalidRoleList() {
        var before = Map.<String, Object>of("status", "PENDING");
        for (Object roles : List.of(List.of("user@example.com"), Map.of("token", "secret"))) {
            var event = new AuditEvent("AUTH_LOGIN_SUCCESS", "USER", 7L, "CUSTOMER", "APP_USER", "7",
                    before, Map.of("reasonCode", "LOGIN_OK", "roleCodes", roles), null, null,
                    "corr-1", "req-1", "SUCCESS", null);
            assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsSensitiveUserAgentWithoutEchoingInput() {
        for (String input : List.of("Browser email=user@example.com", "Bearer abc.def.ghi", "otp=123456", "phone=0901234567")) {
            var event = new AuditEvent("AUTH_LOGIN_SUCCESS", "USER", 7L, "CUSTOMER", "APP_USER", "7",
                    null, Map.of("reasonCode", "LOGIN_OK"), null, null,
                    "corr-1", "req-1", "SUCCESS", Map.of("userAgent", input));
            assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(input);
        }
    }

    @Test
    void boundsSnapshotCopyExpansion() {
        Object value = "leaf";
        for (int i = 0; i < 15; i++) value = List.of(value, value);
        var state = Map.of("roleCodes", value);
        assertThatThrownBy(() -> new AuditEvent("AUTH_LOGIN_SUCCESS", "USER", 7L, "CUSTOMER", "APP_USER", "7",
                null, state, null, null, "corr-1", "req-1", "SUCCESS", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copiesSharedAcyclicValuesWithoutTreatingAliasesAsCycles() {
        var value = new HashMap<String, Object>();
        value.put("status", "PENDING");
        var event = new AuditEvent("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN20261103COPY",
                value, value, BigDecimal.ONE, "VND", "corr-1", "req-1", "SUCCESS", null);
        value.put("status", "FAILED");
        assertThat(event.beforeState()).containsEntry("status", "PENDING");
        assertThat(event.afterState()).containsEntry("status", "PENDING");
    }

    @Test
    void requestedApprovalDoesNotRequireFutureCheckerDecision() {
        var event = new AuditEvent("LIMIT_CHANGE_REQUESTED", "USER", 7L, "MAKER", "APPROVAL_REQUEST", "42",
                null, Map.of("requestType", "LIMIT_CHANGE", "status", "PENDING", "targetType", "ACCOUNT",
                        "targetId", "11", "makerId", "7"), null, null,
                "corr-1", "req-1", "SUCCESS", null);
        assertThatCode(() -> AuditEventValidator.validate(event)).doesNotThrowAnyException();
    }

    @Test
    void createdFraudFlagDoesNotRequireFutureReviewDecision() {
        var event = new AuditEvent("FRAUD_FLAG_CREATED", "SYSTEM", null, "SYSTEM", "FRAUD_FLAG", "42",
                null, Map.of("ruleVersion", 1, "ruleCode", "HIGH_AMOUNT", "severity", "HIGH", "status", "OPEN",
                        "transferReference", "TXN20261103FRAUD"), null, null,
                "corr-1", "req-1", "SUCCESS", null);
        assertThatCode(() -> AuditEventValidator.validate(event)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUntypedValuesInAllowlistedFields() {
        for (var entry : Map.<String, Object>of("createdAt", Map.of("secret", "value"),
                "completedAt", "user@example.com", "transferType", "0123456789").entrySet()) {
            assertThatThrownBy(() -> AuditEventValidator.validate(transfer(Map.of("status", "COMPLETED",
                    entry.getKey(), entry.getValue())))).isInstanceOf(IllegalArgumentException.class);
        }
        var event = new AuditEvent("RETENTION_POLICY_CHANGED", "USER", 7L, "ADMIN", "RETENTION_POLICY", "1",
                null, Map.of("dataClass", "AUDIT_LOG", "enabled", "yes"), null, null,
                "corr-1", "req-1", "SUCCESS", null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullOrBlankStatusWhenStatusIsRequired() {
        for (Object status : java.util.Arrays.asList(null, "", "  ")) {
            var state = new HashMap<String, Object>();
            state.put("status", status);
            var event = transfer(state);
            assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void acceptsStandardNonSensitiveUserAgent() {
        var good = loginClient(Map.of("userAgent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)",
                "ipMasked", "203.0.113.***"));
        assertThatCode(() -> AuditEventValidator.validate(good)).doesNotThrowAnyException();
        for (String agent : List.of("Browser\nemail=user@example.com", "Browser 0901234567", "Browser otp=123456")) {
            assertThatThrownBy(() -> AuditEventValidator.validate(loginClient(Map.of("userAgent", agent))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> AuditEventValidator.validate(loginClient(Map.of("ipMasked", "999.0.1.***"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSensitiveRoleAndOversizedScalarsBeforeCopying() {
        var event = new AuditEvent("AUTH_LOGIN_SUCCESS", "USER", 7L, "user@example.com", "APP_USER", "7",
                null, Map.of("reasonCode", "LOGIN_OK"), null, null, "corr-1", "req-1", "SUCCESS", null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfer(Map.of("status", "X".repeat(33000))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSensitiveOrWronglyTypedFraudMatchedValues() {
        for (Map<String, Object> matched : List.of(Map.<String, Object>of("count", "user@example.com"),
                Map.<String, Object>of("count", "2"), Map.<String, Object>of("window", "Bearer abc.def"),
                Map.<String, Object>of("count", -1))) {
            var state = new HashMap<String, Object>();
            state.put("transferReference", "TXN20261103FRAUD");
            state.put("ruleCode", "HIGH_FREQUENCY");
            state.put("ruleVersion", 1);
            state.put("severity", "HIGH");
            state.put("status", "OPEN");
            state.put("matchedValue", matched);
            var event = new AuditEvent("FRAUD_FLAG_CREATED", "SYSTEM", null, "SYSTEM", "FRAUD_FLAG", "42",
                    null, state, null, null, "corr-1", "req-1", "SUCCESS", null);
            assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsWrongOutcomeForFinancialFailure() {
        var event = new AuditEvent("TRANSFER_FAILED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN20261103FAILED",
                Map.of("status", "PENDING"), Map.of("status", "FAILED", "failureCode", "FRAUD_BLOCKED"),
                BigDecimal.ONE, "VND", "corr-1", "req-1", "SUCCESS", null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRoleLongerThanPersistedColumn() {
        var event = new AuditEvent("AUTH_LOGIN_SUCCESS", "USER", 7L, "R".repeat(33), "APP_USER", "7",
                null, Map.of("reasonCode", "LOGIN_OK"), null, null, "corr-1", "req-1", "SUCCESS", null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonReferenceFraudCorrelationAndSensitiveWindow() {
        for (var invalid : List.of(Map.<String, Object>of("transferReference", "user@example.com"),
                Map.<String, Object>of("matchedValue", Map.of("window", "0901234567")))) {
            var after = new HashMap<String, Object>(Map.of("transferReference", "TXN20261103FRAUD",
                    "ruleCode", "HIGH_FREQUENCY", "ruleVersion", 1, "severity", "HIGH", "status", "OPEN"));
            after.putAll(invalid);
            var event = new AuditEvent("FRAUD_FLAG_CREATED", "SYSTEM", null, "SYSTEM", "FRAUD_FLAG", "42",
                    null, after, null, null, "corr-1", "req-1", "SUCCESS", null);
            assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void acceptsTypedFraudWindowAndMoneyWithoutMistakingMoneyForPii() {
        var event = new AuditEvent("FRAUD_FLAG_CREATED", "SYSTEM", null, "SYSTEM", "FRAUD_FLAG", "42",
                null, Map.of("transferReference", "TXN20261103FRAUD", "ruleCode", "HIGH_FREQUENCY", "ruleVersion", 1,
                        "severity", "HIGH", "status", "OPEN", "matchedValue",
                        Map.of("window", "PT10M", "count", 6, "amount", new BigDecimal("123456789012.00"))),
                null, null, "corr-1", "req-1", "SUCCESS", null);
        assertThatCode(() -> AuditEventValidator.validate(event)).doesNotThrowAnyException();
    }

    @Test
    void rejectsRetentionPurgeUntilCheckpointContractIsReviewed() {
        var event = new AuditEvent("RETENTION_PURGED", "SYSTEM", null, "SYSTEM", "RETENTION_POLICY", "1",
                null, Map.of("dataClass", "AUDIT_LOG", "checkpointId", "42"), null, null,
                "corr-1", "req-1", "SUCCESS", null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsHashVectorForCanonicalPayloadAndPreviousHash() {
        var result = AuditHashing.recordHash("{\"action\":\"TRANSFER_COMPLETED\"}", AuditHashing.GENESIS_HASH);
        assertThat(result).isEqualTo("46236c80913a1fe504a70458df6323640ccb4899cfb8f0d273397863bbb565cc");
    }

    @Test
    void rejectsMalformedPreviousHashInsteadOfHashingArbitrarySuffix() {
        for (String previousHash : List.of("0", "A".repeat(64), "g".repeat(64), "0".repeat(63))) {
            assertThatThrownBy(() -> AuditHashing.recordHash("{}", previousHash))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsNullOccurredAtWithDomainValidationError() {
        var event = transfer(Map.of("status", "COMPLETED"));
        assertThatThrownBy(() -> AuditHashing.canonicalPayload(event, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCatalogRequiredFieldWhenSnapshotContainsOnlyNullValue() {
        var after = new HashMap<String, Object>();
        after.put("status", null);
        var event = new AuditEvent("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN20261103NULLSTATUS",
                Map.of("status", "PENDING"), after, BigDecimal.ONE, "VND",
                "corr-1", "req-1", "SUCCESS", null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRequestIdLongerThanPersistedColumn() {
        var event = new AuditEvent("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN20261103LONGREQ",
                Map.of("status", "PENDING"), Map.of("status", "COMPLETED"), BigDecimal.ONE, "VND",
                "corr-1", "R".repeat(65), "SUCCESS", null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authLoginSuccessRequiresMaskedClientContext() {
        var event = new AuditEvent("AUTH_LOGIN_SUCCESS", "USER", 7L, "CUSTOMER", "APP_USER", "7",
                null, Map.of("reasonCode", "LOGIN_OK"), null, null, "corr-1", "req-1", "SUCCESS", null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingRetentionPolicyEnabledField() {
        var event = new AuditEvent("RETENTION_POLICY_CHANGED", "USER", 7L, "ADMIN", "RETENTION_POLICY", "1",
                null, Map.of("dataClass", "AUDIT_LOG"), null, null,
                "corr-1", "req-1", "SUCCESS", null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    private static AuditEvent loginClient(Map<String, Object> client) {
        return new AuditEvent("AUTH_LOGIN_SUCCESS", "USER", 7L, "CUSTOMER", "APP_USER", "7",
                null, Map.of("reasonCode", "LOGIN_OK"), null, null, "corr-1", "req-1", "SUCCESS", client);
    }

    private static AuditEvent transfer(Map<String, Object> after) {
        return new AuditEvent("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN20261103NORM",
                Map.of("status", "PENDING"), after, new BigDecimal("150000.50"), "VND",
                "corr-1", "req-1", "SUCCESS", null);
    }

    private static AuditEvent challenge(Object attempts) {
        return new AuditEvent("AUTH_CHALLENGE_CREATED", "USER", 7L, "CUSTOMER", "AUTH_CHALLENGE", "challenge-1",
                null, Map.of("purpose", "LOGIN", "method", "OTP", "status", "PENDING", "attemptCount", attempts),
                null, null, "corr-1", "req-1", "SUCCESS", null);
    }
}
