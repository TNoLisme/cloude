package com.simulatedbank.banking.audit.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simulatedbank.banking.audit.domain.AuditCanonicalJson;
import com.simulatedbank.banking.audit.domain.AuditHashing;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class AuditChainVerifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VALID_V1_PAYLOAD = "{\"occurred_at\":\"2026-11-03T10:15:31.123456Z\",\"actor_type\":\"USER\",\"actor_user_id\":\"7\",\"actor_role\":\"CUSTOMER\",\"action\":\"TRANSFER_CREATED\",\"entity_type\":\"TRANSFER\",\"entity_id\":\"TXN12345678\",\"before_state\":null,\"after_state\":{\"status\":\"PENDING\"},\"amount\":\"12.00\",\"currency\":\"VND\",\"correlation_id\":\"corr-1\",\"request_id\":\"req-1\",\"outcome\":\"SUCCESS\",\"client_info_masked\":null,\"hash_version\":1}";

    @Test
    void doesNotClaimFullVerificationFromAnIncompletePayload() {
        String payload = "{\"action\":\"TRANSFER_COMPLETED\",\"hash_version\":1}";
        var result = AuditChainVerifier.verifyRecord(payload, AuditHashing.GENESIS_HASH,
                AuditHashing.GENESIS_HASH, AuditHashing.GENESIS_HASH);
        assertThat(result.status()).isEqualTo(AuditChainVerifier.Status.INCOMPLETE);
        assertThat(result.reason()).isEqualTo("PAYLOAD_FIELDS");
    }

    @Test
    void rejectsWrongPreviousHashWithoutRepairingIt() {
        String payload = "{\"action\":\"TRANSFER_COMPLETED\"}";
        var result = AuditChainVerifier.verifyRecord(payload, "1".repeat(64),
                AuditHashing.recordHash(payload, "1".repeat(64)), AuditHashing.GENESIS_HASH);
        assertThat(result.status()).isEqualTo(AuditChainVerifier.Status.MISMATCH);
        assertThat(result.reason()).isEqualTo("PREV_HASH");
    }

    @Test
    void rejectsWrongRecordHash() {
        String canonical = AuditCanonicalJson.canonicalize(MAPPER.convertValue(
                assertDoesNotThrowTree(VALID_V1_PAYLOAD), Object.class));
        String correctHash = AuditHashing.recordHash(canonical, AuditHashing.GENESIS_HASH);
        String wrongHash = "a".repeat(64);
        assertThat(correctHash).isNotEqualTo(wrongHash);

        var result = AuditChainVerifier.verifyRecord(VALID_V1_PAYLOAD, AuditHashing.GENESIS_HASH,
                wrongHash, AuditHashing.GENESIS_HASH);
        assertThat(result.status()).isEqualTo(AuditChainVerifier.Status.MISMATCH);
        assertThat(result.reason()).isEqualTo("RECORD_HASH");
    }

    @Test
    void rejectsExtraFieldInPayload() throws Exception {
        ObjectNode node = (ObjectNode) MAPPER.readTree(VALID_V1_PAYLOAD);
        node.put("unexpected_field", "evil");
        String payload = node.toString();

        var result = AuditChainVerifier.verifyRecord(payload, AuditHashing.GENESIS_HASH,
                AuditHashing.GENESIS_HASH, AuditHashing.GENESIS_HASH);
        assertThat(result.status()).isEqualTo(AuditChainVerifier.Status.INCOMPLETE);
        assertThat(result.reason()).isEqualTo("PAYLOAD_FIELDS");
    }

    @Test
    void rejectsMissingFieldInPayload() throws Exception {
        ObjectNode node = (ObjectNode) MAPPER.readTree(VALID_V1_PAYLOAD);
        node.remove("request_id");
        String payload = node.toString();

        var result = AuditChainVerifier.verifyRecord(payload, AuditHashing.GENESIS_HASH,
                AuditHashing.GENESIS_HASH, AuditHashing.GENESIS_HASH);
        assertThat(result.status()).isEqualTo(AuditChainVerifier.Status.INCOMPLETE);
        assertThat(result.reason()).isEqualTo("PAYLOAD_FIELDS");
    }

    @Test
    void rejectsMalformedPayloadJson() {
        String malformed = "{\"occurred_at\": invalid_json";
        var recordResult = AuditChainVerifier.verifyRecord(malformed, AuditHashing.GENESIS_HASH,
                AuditHashing.GENESIS_HASH, AuditHashing.GENESIS_HASH);
        assertThat(recordResult.status()).isEqualTo(AuditChainVerifier.Status.MISMATCH);
        assertThat(recordResult.reason()).isEqualTo("PAYLOAD");

        var rowResult = AuditChainVerifier.verifyRow(malformed, 1, AuditHashing.GENESIS_HASH,
                AuditHashing.GENESIS_HASH, AuditHashing.GENESIS_HASH, MAPPER.createObjectNode());
        assertThat(rowResult.status()).isEqualTo(AuditChainVerifier.Status.MISMATCH);
    }

    @Test
    void comparesPersistedColumnsAndVersionBeforePassingRecord() throws Exception {
        var columns = MAPPER.readTree(VALID_V1_PAYLOAD);
        var canonicalizer = new com.simulatedbank.banking.common.crypto.JcsCanonicalizer();
        String canonical = new String(canonicalizer.canonicalize(VALID_V1_PAYLOAD), StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((canonical + "|" + AuditHashing.GENESIS_HASH).getBytes(StandardCharsets.UTF_8));
        String hash = HexFormat.of().formatHex(digest);

        assertThat(AuditChainVerifier.verifyRow(VALID_V1_PAYLOAD, 1, AuditHashing.GENESIS_HASH, hash,
                AuditHashing.GENESIS_HASH, columns).status()).isEqualTo(AuditChainVerifier.Status.PASS);

        ((ObjectNode) columns).put("amount", "99.00");
        assertThat(AuditChainVerifier.verifyRow(VALID_V1_PAYLOAD, 1, AuditHashing.GENESIS_HASH, hash,
                AuditHashing.GENESIS_HASH, columns).status()).isEqualTo(AuditChainVerifier.Status.MISMATCH);

        assertThat(AuditChainVerifier.verifyRow(VALID_V1_PAYLOAD, 2, AuditHashing.GENESIS_HASH, hash,
                AuditHashing.GENESIS_HASH, columns).status()).isEqualTo(AuditChainVerifier.Status.UNSUPPORTED);
    }

    @Test
    void rejectsPayloadFieldTypeMismatchAgainstColumns() throws Exception {
        // Payload has actor_user_id as number 7, columns has "7" as text
        ObjectNode payloadNode = (ObjectNode) MAPPER.readTree(VALID_V1_PAYLOAD);
        payloadNode.put("actor_user_id", 7);
        String payload = payloadNode.toString();

        ObjectNode columns = (ObjectNode) MAPPER.readTree(VALID_V1_PAYLOAD); // columns has string "7"
        String canonical = AuditCanonicalJson.canonicalize(MAPPER.convertValue(payloadNode, Object.class));
        String hash = AuditHashing.recordHash(canonical, AuditHashing.GENESIS_HASH);

        var result = AuditChainVerifier.verifyRow(payload, 1, AuditHashing.GENESIS_HASH, hash,
                AuditHashing.GENESIS_HASH, columns);
        assertThat(result.status()).isEqualTo(AuditChainVerifier.Status.MISMATCH);
        assertThat(result.reason()).isEqualTo("COLUMN_MISMATCH");
    }

    @Test
    void rejectsNullVsValueMismatchBetweenPayloadAndColumns() throws Exception {
        ObjectNode columns = (ObjectNode) MAPPER.readTree(VALID_V1_PAYLOAD);
        columns.putNull("after_state"); // columns has null, payload has {"status":"PENDING"}

        var canonicalizer = new com.simulatedbank.banking.common.crypto.JcsCanonicalizer();
        String canonical = new String(canonicalizer.canonicalize(VALID_V1_PAYLOAD), StandardCharsets.UTF_8);
        String hash = AuditHashing.recordHash(canonical, AuditHashing.GENESIS_HASH);

        var result = AuditChainVerifier.verifyRow(VALID_V1_PAYLOAD, 1, AuditHashing.GENESIS_HASH, hash,
                AuditHashing.GENESIS_HASH, columns);
        assertThat(result.status()).isEqualTo(AuditChainVerifier.Status.MISMATCH);
        assertThat(result.reason()).isEqualTo("COLUMN_MISMATCH");
    }

    @Test
    void rejectsMalformedOrUnsupportedVersion() {
        assertThat(AuditChainVerifier.verifyPayloadVersion("{}", 1).status())
                .isEqualTo(AuditChainVerifier.Status.MISMATCH);
        assertThat(AuditChainVerifier.verifyPayloadVersion("{\"hash_version\":2}", 2).status())
                .isEqualTo(AuditChainVerifier.Status.UNSUPPORTED);
        // String version instead of int
        assertThat(AuditChainVerifier.verifyPayloadVersion("{\"hash_version\":\"1\"}", 1).status())
                .isEqualTo(AuditChainVerifier.Status.MISMATCH);
        // Floating point version
        assertThat(AuditChainVerifier.verifyPayloadVersion("{\"hash_version\":1.5}", 1).status())
                .isEqualTo(AuditChainVerifier.Status.MISMATCH);
    }

    private static com.fasterxml.jackson.databind.JsonNode assertDoesNotThrowTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
