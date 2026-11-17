package com.simulatedbank.banking.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** BE-ID-IMP-13 — redaction trung tâm: biến thể tên header/field, nested, text, exception. */
class LogRedactionTest {

    @Test
    void headerNameVariationsAreRedactedInStructuredMaps() {
        Map<String, Object> input = Map.ofEntries(
                Map.entry("Authorization", "Bearer abc.def.ghi"),
                Map.entry("authorization", "Bearer abc"),
                Map.entry("AUTHORIZATION", "Bearer abc"),
                Map.entry("Cookie", "DB_SESSION=tok"),
                Map.entry("set-cookie", "DB_SESSION=tok; HttpOnly"),
                Map.entry("X-CSRF-Token", "csrf-raw"),
                Map.entry("x-csrf-token", "csrf-raw"),
                Map.entry("csrf_token", "csrf-raw"),
                Map.entry("password", "hunter2"),
                Map.entry("Password", "hunter2"),
                Map.entry("PASSWORD", "hunter2"),
                Map.entry("password_hash", "$argon2id$abc"),
                Map.entry("passwordHash", "$argon2id$abc"),
                Map.entry("refreshToken", "rtok"),
                Map.entry("access_token", "atok"),
                Map.entry("AccessToken", "atok"),
                Map.entry("sessionToken", "stok"),
                Map.entry("session_id", "sid"),
                Map.entry("sessionId", "sid"),
                Map.entry("OTP", "123456"),
                Map.entry("otpCode", "123456"),
                Map.entry("mfaSecret", "JBSWY3DP"),
                Map.entry("recoveryCode", "abcd-ef12"),
                Map.entry("apiKey", "sk-1"),
                Map.entry("api_key", "sk-1"),
                Map.entry("privateKey", "pk"),
                Map.entry("username", "customer01"));
        Map<String, Object> redacted = LogRedaction.redact(input);
        assertThat(redacted.values().stream().distinct()).containsExactly(LogRedaction.REDACTED);
    }

    @Test
    void nonSensitiveDiagnosticsArePreserved() {
        Map<String, Object> input = Map.of(
                "route", "/api/v1/auth/login",
                "method", "POST",
                "status", 429,
                "user_id", "sha256:ab12",
                "correlationId", "corr-1",
                "action", "AUTH_LOGIN_FAILED",
                "reasonCode", "WRONG_PASSWORD");
        assertThat(LogRedaction.redact(input)).isEqualTo(input);
    }

    @Test
    void nestedObjectsAndListsAreRedactedRecursively() {
        Map<String, Object> input = Map.of(
                "client", Map.of("ip", "203.0.113.***", "password", "hunter2",
                        "headers", Map.of("Authorization", "Bearer abc")),
                "attempts", List.of(Map.of("otp", "123456"), Map.of("attempt", 2)),
                "plain", "value");
        Map<String, Object> redacted = LogRedaction.redact(input);
        @SuppressWarnings("unchecked")
        Map<String, Object> client = (Map<String, Object>) redacted.get("client");
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) client.get("headers");
        assertThat(client.get("ip")).isEqualTo("203.0.113.***");
        assertThat(client.get("password")).isEqualTo(LogRedaction.REDACTED);
        assertThat(headers.get("Authorization")).isEqualTo(LogRedaction.REDACTED);
        assertThat((List<?>) redacted.get("attempts")).hasSize(2)
                .satisfiesExactly(first -> assertThat(((Map<?, ?>) first).get("otp")).isEqualTo(LogRedaction.REDACTED),
                        second -> assertThat(((Map<?, ?>) second).get("attempt")).isEqualTo(2));
        assertThat(redacted.get("plain")).isEqualTo("value");
    }

    @Test
    void emptyAndNullInputsAreSafe() {
        assertThat(LogRedaction.redact(null)).isNull();
        assertThat(LogRedaction.redact(Map.of())).isEmpty();
        assertThat(LogRedaction.redactText(null)).isNull();
        assertThat(LogRedaction.redactText("")).isEmpty();
    }

    @Test
    void exceptionAndMessageTextScrubbingCoversCommonShapes() {
        assertThat(LogRedaction.redactText("password=hunter2")).isEqualTo("password=" + LogRedaction.REDACTED);
        assertThat(LogRedaction.redactText("Password: hunter2")).isEqualTo("Password: " + LogRedaction.REDACTED);
        assertThat(LogRedaction.redactText("{\"password\":\"hunter2\"}"))
                .isEqualTo("{\"password\":" + LogRedaction.REDACTED + "}");
        assertThat(LogRedaction.redactText("Authorization=Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"))
                .isEqualTo("Authorization=" + LogRedaction.REDACTED + " " + LogRedaction.REDACTED);
        assertThat(LogRedaction.redactText("header Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig failed"))
                .isEqualTo("header Bearer " + LogRedaction.REDACTED + " failed");
        assertThat(LogRedaction.redactText("otp=123456"))
                .isEqualTo("otp=" + LogRedaction.REDACTED);
        assertThat(LogRedaction.redactText("sessionToken: tok_abc123"))
                .isEqualTo("sessionToken: " + LogRedaction.REDACTED);
        assertThat(LogRedaction.redactText("X-CSRF-Token=csrf-raw"))
                .isEqualTo("X-CSRF-Token=" + LogRedaction.REDACTED);
    }

    @Test
    void nonSensitiveTextIsUnchanged() {
        String diagnostic = "SECURITY_AUDIT action=AUTH_LOGIN_FAILED reason_code=WRONG_PASSWORD corr-1";
        assertThat(LogRedaction.redactText(diagnostic)).isEqualTo(diagnostic);
    }
}
