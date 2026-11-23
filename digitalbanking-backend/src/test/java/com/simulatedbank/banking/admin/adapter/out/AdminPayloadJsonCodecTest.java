package com.simulatedbank.banking.admin.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.admin.domain.LimitChangePayload;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.admin.domain.RoleChangePayload;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** BE-ADM-DOM-01: typed payload serialization and public masking of server-captured fields. */
class AdminPayloadJsonCodecTest {

    private static final long TARGET = 1102L;

    private final AdminPayloadJsonCodec codec = new AdminPayloadJsonCodec(new JcsCanonicalizer());

    @Test
    void publicRolePayloadOmitsServerCapturedVersionAndKeepsContractFields() {
        Map<String, Object> payload = codec.publicPayload(new RoleChangePayload(
                RoleChangeOperation.GRANT_PRIVILEGE, TARGET, null, "CHECKER", 7L, "reason"));

        assertThat(payload).containsOnlyKeys("operation", "targetUserId", "roleCode", "privilegeCode", "reason");
        assertThat(payload).doesNotContainKey("expectedVersion");
        assertThat(payload).containsEntry("operation", "GRANT_PRIVILEGE")
                .containsEntry("targetUserId", Long.toString(TARGET))
                .containsEntry("privilegeCode", "CHECKER");
    }

    @Test
    void persistedRolePayloadRetainsExpectedVersion() {
        Map<String, Object> payload = codec.encode(new RoleChangePayload(
                RoleChangeOperation.ASSIGN_ROLE, TARGET, "AUDITOR", null, 7L, null));

        assertThat(payload).containsEntry("expectedVersion", 7L);
        assertThat(codec.decode(com.simulatedbank.banking.admin.domain.ApprovalRequestType.ROLE_CHANGE, payload))
                .isEqualTo(new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, TARGET, "AUDITOR", null, 7L,
                        null));
    }

    @Test
    void limitPayloadUsesDecimalStringsAndIsoInstants() {
        Map<String, Object> payload = codec.publicPayload(new LimitChangePayload(2001L, 3001L,
                new BigDecimal("5000000.00"), new BigDecimal("20000000.00"), "VND",
                Instant.parse("2026-11-18T00:00:00Z"), "reason"));

        assertThat(payload).containsEntry("customerId", "2001")
                .containsEntry("accountId", "3001")
                .containsEntry("perTransactionLimit", "5000000.00")
                .containsEntry("dailyLimit", "20000000.00")
                .containsEntry("effectiveFrom", "2026-11-18T00:00:00Z");
        assertThat(String.valueOf(payload)).doesNotContain("null");
    }

    @Test
    void publicPayloadRedactsCredentialAssignmentsInFreeText() {
        Map<String, Object> payload = codec.publicPayload(new RoleChangePayload(
                RoleChangeOperation.ASSIGN_ROLE, TARGET, "AUDITOR", null, 7L,
                "approved password=hunter2 otp=812345 token=abc"));

        assertThat(payload).doesNotContainKey("expectedVersion");
        assertThat(payload.get("reason"))
                .isEqualTo("approved password=[REDACTED] otp=[REDACTED] token=[REDACTED]");
    }

    @Test
    void canonicalJsonIsStableAndNeverContainsSecrets() {
        RoleChangePayload payload = new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, TARGET, "AUDITOR",
                null, 7L, "reason");

        String canonical = new String(codec.canonicalJson(payload), StandardCharsets.UTF_8);

        assertThat(canonical).isEqualTo(new String(codec.canonicalJson(payload), StandardCharsets.UTF_8));
        assertThat(canonical).doesNotContainIgnoringCase("password")
                .doesNotContainIgnoringCase("token")
                .doesNotContainIgnoringCase("otp");
    }
}
