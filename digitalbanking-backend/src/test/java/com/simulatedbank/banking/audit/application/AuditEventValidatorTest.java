package com.simulatedbank.banking.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.audit.api.AuditAction;
import com.simulatedbank.banking.audit.api.AuditActorType;
import com.simulatedbank.banking.audit.api.AuditEvent;
import com.simulatedbank.banking.audit.api.AuditOutcome;
import com.simulatedbank.banking.audit.domain.AuditChain;
import com.simulatedbank.banking.audit.domain.ValidatedAuditEvent;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Rule-level tests of the C2/C3 audit validator (design/01 §5.3–§5.6, §6.3, §7.2). */
class AuditEventValidatorTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-03T10:15:31.123456Z");

    private static final String EXPECTED_CANONICAL_JSON = "{\"action\":\"TRANSFER_COMPLETED\","
            + "\"actor_role\":\"SYSTEM\",\"actor_type\":\"SYSTEM\",\"actor_user_id\":null,"
            + "\"after_state\":{\"status\":\"COMPLETED\"},\"amount\":\"150000.00\","
            + "\"before_state\":{\"status\":\"PENDING\"},"
            + "\"client_info_masked\":{\"ipMasked\":\"203.0.113.***\"},"
            + "\"correlation_id\":\"corr-audit-1\",\"currency\":\"VND\","
            + "\"entity_id\":\"TXN20261103A1B2C3D4\",\"entity_type\":\"TRANSFER\",\"hash_version\":1,"
            + "\"occurred_at\":\"2026-11-03T10:15:31.123456Z\",\"outcome\":\"SUCCESS\","
            + "\"request_id\":\"req-audit-1\"}";

    private static final String EXPECTED_RECORD_HASH =
            "16ed00a3030b394413a3aa1956e65307d813b48414ed31eece6f4a23d5efc28b";

    private final AuditEventValidator validator =
            new AuditEventValidator(new ObjectMapper(), new JcsCanonicalizer());

    @Test
    @DisplayName("a valid approval event is accepted and carries the canonical payload")
    void acceptsValidApprovalEvent() {
        ValidatedAuditEvent validated = validator.validate(approvalEvent(
                Map.<String, Object>of("status", "PENDING"),
                Map.<String, Object>of("approvalId", "42", "makerId", "1000", "checkerId", "1001",
                        "decision", "APPROVE", "status", "APPROVED", "requestType", "LIMIT_CHANGE")));

        assertThat(validated.action()).isEqualTo(AuditAction.LIMIT_CHANGE_APPROVED);
        assertThat(validated.actorUserId()).isEqualTo(1001L);
        assertThat(validated.hashVersion()).isEqualTo(1);
        assertThat(validated.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(validated.canonicalJson())
                .contains("\"hash_version\":1")
                .contains("\"occurred_at\":\"2026-11-03T10:15:31.123456Z\"");
        assertThat(validated.beforeState()).containsEntry("status", "PENDING");
    }

    @Test
    @DisplayName("the canonical payload of a fixed event matches the independently computed vector")
    void canonicalPayloadMatchesFixedVector() {
        AuditEvent event = new AuditEvent(
                AuditAction.TRANSFER_COMPLETED,
                AuditActorType.SYSTEM,
                null,
                "SYSTEM",
                "TRANSFER",
                "TXN20261103A1B2C3D4",
                Map.<String, Object>of("status", "PENDING"),
                Map.<String, Object>of("status", "COMPLETED"),
                new BigDecimal("150000"),
                "VND",
                AuditOutcome.SUCCESS,
                "corr-audit-1",
                "req-audit-1",
                Map.<String, Object>of("ipMasked", "203.0.113.***"),
                OCCURRED_AT);

        ValidatedAuditEvent validated = validator.validate(event);

        assertThat(validated.canonicalJson()).isEqualTo(EXPECTED_CANONICAL_JSON);
        assertThat(AuditChain.recordHash(validated.canonicalJson(), AuditChain.GENESIS_PREV_HASH))
                .isEqualTo(EXPECTED_RECORD_HASH);
    }

    @Test
    @DisplayName("null event is rejected with AUDIT_WRITE_FAILED")
    void rejectsNullEvent() {
        assertRejected(() -> validator.validate(null));
    }

    @Test
    @DisplayName("unknown entity type is rejected")
    void rejectsUnknownEntityType() {
        AuditEvent event = new AuditEvent(AuditAction.TRANSFER_COMPLETED, AuditActorType.SYSTEM, null, "SYSTEM",
                "UNKNOWN_ENTITY", "1", null, null, null, null, AuditOutcome.SUCCESS, "corr-1", null, null,
                OCCURRED_AT);
        assertRejected(() -> validator.validate(event));
    }

    @Test
    @DisplayName("USER actor without actorUserId is rejected")
    void rejectsUserActorWithoutUserId() {
        AuditEvent event = new AuditEvent(AuditAction.AUTH_LOGIN_SUCCESS, AuditActorType.USER, null, "CUSTOMER",
                "APP_USER", "1001", null, null, null, null, AuditOutcome.SUCCESS, "corr-1", null, null,
                OCCURRED_AT);
        assertRejected(() -> validator.validate(event));
    }

    @Test
    @DisplayName("SYSTEM actor carrying actorUserId is rejected")
    void rejectsSystemActorWithUserId() {
        AuditEvent event = new AuditEvent(AuditAction.AUTH_LOGIN_FAILED, AuditActorType.SYSTEM, 1001L, "SYSTEM",
                "APP_USER", "anon:0123456789abcdef", null, null, null, null, AuditOutcome.FAILURE, "corr-1",
                null, null, OCCURRED_AT);
        assertRejected(() -> validator.validate(event));
    }

    @Test
    @DisplayName("a state key outside the per-entity allowlist is rejected")
    void rejectsDisallowedStateKey() {
        assertRejected(() -> validator.validate(
                approvalEvent(null, Map.<String, Object>of("status", "APPROVED", "fullName", "Nguyen Van A"))));
        assertRejected(() -> validator.validate(
                approvalEvent(Map.<String, Object>of("accountNo", "1234567890123"), null)));
    }

    @Test
    @DisplayName("account numbers, e-mail, phone and OTP values are rejected by the scanner")
    void rejectsPiiValues() {
        assertRejected(() -> validator.validate(
                transferEvent(null, Map.<String, Object>of("reference", "1234567890123"))));
        assertRejected(() -> validator.validate(
                approvalEvent(null, Map.<String, Object>of("status", "customer@example.com"))));
        assertRejected(() -> validator.validate(
                approvalEvent(null, Map.<String, Object>of("status", "+84912345678"))));
        assertRejected(() -> validator.validate(
                approvalEvent(null, Map.<String, Object>of("status", "123456"))));
    }

    @Test
    @DisplayName("bearer/JWT/password-style values are rejected")
    void rejectsSecretValues() {
        assertRejected(() -> validator.validate(approvalEvent(null,
                Map.<String, Object>of("status", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghijklmnop"))));
        assertRejected(() -> validator.validate(approvalEvent(null,
                Map.<String, Object>of("decision", "password=hunter2"))));
        assertRejected(() -> validator.validate(approvalEvent(null,
                Map.<String, Object>of("decision", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghijklmnop"))));
    }

    @Test
    @DisplayName("free text longer than 512 characters is rejected")
    void rejectsLongFreeText() {
        assertRejected(() -> validator.validate(
                approvalEvent(null, Map.<String, Object>of("status", "x".repeat(513)))));
    }

    @Test
    @DisplayName("a canonical payload larger than 32 KiB is rejected")
    void rejectsOversizedPayload() {
        String oversizedValue = "\"".repeat(512);
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : new String[] {"approvalId", "requestType", "targetType", "targetId", "status",
                "makerId", "checkerId", "decision", "operation", "roleCode", "privilegeCode",
                "perTransactionLimit", "dailyLimit", "version", "previousVersion", "resultingVersion",
                "scopeType", "scopeId"}) {
            values.put(key, oversizedValue);
        }
        assertRejected(() -> validator.validate(approvalEvent(values, values)));
    }

    @Test
    @DisplayName("float/dec float state values and numeric IDs are rejected")
    void rejectsNonCanonicalNumbers() {
        assertRejected(() -> validator.validate(
                approvalEvent(null, Map.<String, Object>of("version", 1.5d))));
        assertRejected(() -> validator.validate(
                approvalEvent(null, Map.<String, Object>of("makerId", 1001L))));
        assertRejected(() -> validator.validate(
                approvalEvent(null, Map.<String, Object>of("perTransactionLimit", new BigDecimal("1000000.00")))));
    }

    @Test
    @DisplayName("amount requires VND and a non-negative two-decimal value")
    void rejectsInvalidAmount() {
        AuditEvent noCurrency = new AuditEvent(AuditAction.TRANSFER_COMPLETED, AuditActorType.SYSTEM, null,
                "SYSTEM", "TRANSFER", "TXN1", null, null, new BigDecimal("150000.00"), null,
                AuditOutcome.SUCCESS, "corr-1", null, null, OCCURRED_AT);
        assertRejected(() -> validator.validate(noCurrency));

        AuditEvent nonVnd = new AuditEvent(AuditAction.TRANSFER_COMPLETED, AuditActorType.SYSTEM, null, "SYSTEM",
                "TRANSFER", "TXN1", null, null, new BigDecimal("150000.00"), "USD", AuditOutcome.SUCCESS,
                "corr-1", null, null, OCCURRED_AT);
        assertRejected(() -> validator.validate(nonVnd));

        AuditEvent negative = new AuditEvent(AuditAction.TRANSFER_COMPLETED, AuditActorType.SYSTEM, null,
                "SYSTEM", "TRANSFER", "TXN1", null, null, new BigDecimal("-1.00"), "VND",
                AuditOutcome.SUCCESS, "corr-1", null, null, OCCURRED_AT);
        assertRejected(() -> validator.validate(negative));
    }

    @Test
    @DisplayName("client_info_masked only accepts the documented masked keys")
    void rejectsUnknownClientInfoKey() {
        AuditEvent event = new AuditEvent(AuditAction.AUTH_LOGIN_FAILED, AuditActorType.SYSTEM, null, "SYSTEM",
                "APP_USER", "anon:0123456789abcdef", null, null, null, null, AuditOutcome.FAILURE, "corr-1",
                null, Map.<String, Object>of("rawIp", "203.0.113.10"), OCCURRED_AT);
        assertRejected(() -> validator.validate(event));
    }

    private static AuditEvent approvalEvent(Map<String, Object> before, Map<String, Object> after) {
        return new AuditEvent(AuditAction.LIMIT_CHANGE_APPROVED, AuditActorType.USER, 1001L, "CHECKER",
                "APPROVAL_REQUEST", "42", before, after, null, null, AuditOutcome.SUCCESS,
                "corr-1", "req-1", Map.<String, Object>of("ipMasked", "203.0.113.***"), OCCURRED_AT);
    }

    private static AuditEvent transferEvent(Map<String, Object> before, Map<String, Object> after) {
        return new AuditEvent(AuditAction.TRANSFER_COMPLETED, AuditActorType.SYSTEM, null, "SYSTEM",
                "TRANSFER", "TXN20261103A1B2C3D4", before, after, null, null, AuditOutcome.SUCCESS,
                "corr-1", null, null, OCCURRED_AT);
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(failure -> assertThat(((BankingBusinessException) failure).code())
                        .isEqualTo(ErrorCode.AUDIT_WRITE_FAILED));
    }
}
