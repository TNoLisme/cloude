package com.simulatedbank.banking.audit.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AuditDetailProjectionTest {

    @Test
    void projectsValidTransferSnapshotPreservingExactValuesWithoutMutatingDecimals() {
        String json = "{\"status\":\"COMPLETED\",\"amount\":\"1.0\",\"sourceAccountId\":\"123\"}";
        Map<String, Object> projected = AuditDetailProjection.projectSnapshot("TRANSFER", json);
        assertThat(projected).isNotNull()
                .containsEntry("status", "COMPLETED")
                .containsEntry("amount", "1.0")
                .containsEntry("sourceAccountId", "123");
        // Verify it didn't mutate "1.0" to "1.00"
        assertThat(projected.get("amount")).isEqualTo("1.0");
    }

    @Test
    void nullOrEmptySnapshotReturnsNull() {
        assertThat(AuditDetailProjection.projectSnapshot("TRANSFER", null)).isNull();
        assertThat(AuditDetailProjection.projectSnapshot("TRANSFER", "")).isNull();
        assertThat(AuditDetailProjection.projectSnapshot("TRANSFER", "   ")).isNull();
    }

    @Test
    void rejectsUnallowlistedFieldsAndPiiFailClosed() {
        // Test synthetic PII: customerName, accountNo, email, phone
        for (String piiKey : java.util.List.of("customerName", "accountNo", "email", "phoneNumber", "cardPan")) {
            String piiJson = "{\"status\":\"COMPLETED\",\"" + piiKey + "\":\"John Doe\"}";
            assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("TRANSFER", piiJson))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("John Doe");
        }
    }

    @Test
    void enforcesAllowlistForAccountEntity() {
        String validAccount = "{\"accountId\":\"acc-1\",\"status\":\"ACTIVE\",\"last4\":\"1234\",\"availableBalance\":\"50000.00\"}";
        Map<String, Object> projected = AuditDetailProjection.projectSnapshot("ACCOUNT", validAccount);
        assertThat(projected).containsEntry("status", "ACTIVE")
                .containsEntry("last4", "1234");

        // Field from another entity (e.g. transferType) on ACCOUNT must fail closed
        String invalidAccount = "{\"accountId\":\"acc-1\",\"transferType\":\"INTERNAL\"}";
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("ACCOUNT", invalidAccount))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnknownEntityType() {
        String json = "{\"status\":\"COMPLETED\"}";
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("UNKNOWN_ENTITY", json))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot(null, json))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void malformedJsonFailsClosedWithoutEchoingInput() {
        String badJson = "{\"status\": invalid_syntax_value_xyz";
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("TRANSFER", badJson))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("invalid_syntax_value_xyz");
    }

    @Test
    void unsafeKeysOrSecretsFailClosedWithoutEchoingInput() {
        String secretJson = "{\"status\":\"OK\",\"token\":\"secret_token_value_abc\"}";
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("TRANSFER", secretJson))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret_token_value_abc");
    }

    @Test
    void controlCharactersInValuesFailClosed() {
        String controlCharJson = "{\"status\":\"OK\\u0000\"}";
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("TRANSFER", controlCharJson))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enforcesPayloadByteSizeBound() {
        String largeValue = "A".repeat(33 * 1024);
        String largeJson = "{\"status\":\"" + largeValue + "\"}";
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("TRANSFER", largeJson))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enforcesNestingDepthBound() {
        // Deeply nested structure > 6 levels
        StringBuilder nested = new StringBuilder("{\"matchedValue\":");
        for (int i = 0; i < 10; i++) nested.append("{\"nested\":");
        nested.append("\"deep\"");
        for (int i = 0; i < 10; i++) nested.append("}");
        nested.append("}");

        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("FRAUD_FLAG", nested.toString()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enforcesTotalNodeCountBound() {
        // Array with 300 elements
        StringBuilder sb = new StringBuilder("{\"roleCodes\":[");
        for (int i = 0; i < 300; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"ROLE_").append(i).append("\"");
        }
        sb.append("]}");

        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("APP_USER", sb.toString()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void projectsValidMaskedClientInfo() {
        String json = "{\"ipMasked\":\"192.168.1.***\",\"userAgent\":\"Mozilla/5.0 (Windows NT 10.0; Win64; x64)\"}";
        var clientInfo = AuditDetailProjection.projectClientInfo(json);
        assertThat(clientInfo).isNotNull();
        assertThat(clientInfo.ipMasked()).isEqualTo("192.168.1.***");
        assertThat(clientInfo.userAgent()).isEqualTo("Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
    }

    @Test
    void unmaskedIpFailsClosedWithoutEchoingInput() {
        String unmaskedIpJson = "{\"ipMasked\":\"192.168.1.100\",\"userAgent\":\"curl/7.68.0\"}";
        assertThatThrownBy(() -> AuditDetailProjection.projectClientInfo(unmaskedIpJson))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("192.168.1.100");
    }

    @Test
    void secretInUserAgentFailsClosedWithoutEchoingInput() {
        String secretUserAgentJson = "{\"ipMasked\":\"10.0.0.***\",\"userAgent\":\"Mozilla/5.0 Bearer secret_jwt_token\"}";
        assertThatThrownBy(() -> AuditDetailProjection.projectClientInfo(secretUserAgentJson))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret_jwt_token");
    }

    @Test
    void unallowlistedFieldInClientInfoFailsClosed() {
        String extraFieldJson = "{\"ipMasked\":\"10.0.0.***\",\"latitude\":\"10.5\"}";
        assertThatThrownBy(() -> AuditDetailProjection.projectClientInfo(extraFieldJson))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("latitude");
    }

    @Test
    void rejectsUnallowlistedNestedFieldsInFraudMatchedValue() {
        for (String piiKey : java.util.List.of("customerName", "phoneNumber", "email", "accountNo", "unapprovedField")) {
            String json = "{\"ruleCode\":\"HIGH_FREQ\",\"matchedValue\":{\"" + piiKey + "\":\"sensitive_val_123\"}}";
            assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("FRAUD_FLAG", json))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("sensitive_val_123");
        }
    }

    @Test
    void allowsValidNestedKeysAndTypesInFraudMatchedValue() {
        String validJson = "{\"ruleCode\":\"HIGH_FREQ\",\"matchedValue\":{\"amount\":\"100.50\",\"count\":5,\"window\":\"PT10M\"}}";
        Map<String, Object> projected = AuditDetailProjection.projectSnapshot("FRAUD_FLAG", validJson);
        assertThat(projected).isNotNull().containsKey("matchedValue");

        @SuppressWarnings("unchecked")
        Map<String, Object> matched = (Map<String, Object>) projected.get("matchedValue");
        assertThat(matched).containsEntry("amount", "100.50")
                .containsEntry("count", 5L)
                .containsEntry("window", "PT10M");
    }

    @Test
    void rejectsInvalidTypesInFraudMatchedValue() {
        // count as string
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("FRAUD_FLAG",
                "{\"ruleCode\":\"HIGH_FREQ\",\"matchedValue\":{\"count\":\"5\"}}"))
                .isInstanceOf(IllegalStateException.class);

        // count as floating point number
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("FRAUD_FLAG",
                "{\"ruleCode\":\"HIGH_FREQ\",\"matchedValue\":{\"count\":5.5}}"))
                .isInstanceOf(IllegalStateException.class);

        // count as negative
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("FRAUD_FLAG",
                "{\"ruleCode\":\"HIGH_FREQ\",\"matchedValue\":{\"count\":-1}}"))
                .isInstanceOf(IllegalStateException.class);

        // amount as JSON number instead of decimal string
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("FRAUD_FLAG",
                "{\"ruleCode\":\"HIGH_FREQ\",\"matchedValue\":{\"amount\":100.50}}"))
                .isInstanceOf(IllegalStateException.class);

        // window invalid code format
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("FRAUD_FLAG",
                "{\"ruleCode\":\"HIGH_FREQ\",\"matchedValue\":{\"window\":\"bad window with spaces!\"}}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNestedObjectsOnEntitiesWithoutDefinedNestedSchema() {
        // TRANSFER does not define any nested object
        String invalidTransfer = "{\"status\":\"COMPLETED\",\"amount\":{\"nested\":\"object\"}}";
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("TRANSFER", invalidTransfer))
                .isInstanceOf(IllegalStateException.class);

        // ACCOUNT does not define any nested object
        String invalidAccount = "{\"accountId\":\"acc-1\",\"status\":{\"code\":\"ACTIVE\"}}";
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("ACCOUNT", invalidAccount))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMoneyExpressedAsJsonNumber() {
        // amount on TRANSFER as JSON number
        String numberAmount = "{\"status\":\"COMPLETED\",\"amount\":100.50}";
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("TRANSFER", numberAmount))
                .isInstanceOf(IllegalStateException.class);

        // availableBalance on ACCOUNT as JSON number
        String numberBalance = "{\"accountId\":\"acc-1\",\"availableBalance\":50000}";
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("ACCOUNT", numberBalance))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsFloatingPointNumbersOrScientificNotation() {
        // Exponent notation
        String exponentJson = "{\"status\":\"COMPLETED\",\"amount\":1e5}";
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("TRANSFER", exponentJson))
                .isInstanceOf(IllegalStateException.class);

        // Floating point in counter
        String floatCounter = "{\"attemptCount\":1.5}";
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("AUTH_CHALLENGE", floatCounter))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsIntegerExceedingSafeIntegerOrLong() {
        // Huge integer exceeding long (BigInteger)
        String hugeBigInt = "{\"attemptCount\":9999999999999999999999999999999999999999999999999999}";
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("AUTH_CHALLENGE", hugeBigInt))
                .isInstanceOf(IllegalStateException.class);

        // Integer exceeding safe integer limit 9_007_199_254_740_991L
        String unsafeInt = "{\"attemptCount\":9007199254740992}";
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("AUTH_CHALLENGE", unsafeInt))
                .isInstanceOf(IllegalStateException.class);

        // Negative counter
        String negativeCounter = "{\"attemptCount\":-1}";
        assertThatThrownBy(() -> AuditDetailProjection.projectSnapshot("AUTH_CHALLENGE", negativeCounter))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void preservesExactSafeIntegerAndStringValues() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String validCounter = "{\"attemptCount\":42}";
        Map<String, Object> projected = AuditDetailProjection.projectSnapshot("AUTH_CHALLENGE", validCounter);
        assertThat(projected).containsEntry("attemptCount", 42L);

        // Verify JSON serialization maintains exact integer representation without precision alteration
        String serialized = mapper.writeValueAsString(projected);
        assertThat(serialized).contains("\"attemptCount\":42");
    }
}
