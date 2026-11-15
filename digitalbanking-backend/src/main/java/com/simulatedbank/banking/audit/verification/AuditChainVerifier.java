package com.simulatedbank.banking.audit.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.audit.domain.AuditCanonicalJson;
import com.simulatedbank.banking.audit.domain.AuditHashing;

import java.util.Objects;
import java.util.Set;

/** Verifies one audit record against the current v1 proposal. */
public final class AuditChainVerifier {
    public enum Status { PASS, EMPTY, UNSUPPORTED, MISMATCH, INCOMPLETE }
    public record Result(Status status, String reason) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private AuditChainVerifier() { }

    public static Result verifyRecord(String payload, String rowPreviousHash, String rowRecordHash, String expectedPreviousHash) {
        if (!Objects.equals(rowPreviousHash, expectedPreviousHash)) return new Result(Status.MISMATCH, "PREV_HASH");
        Result version = verifyPayloadVersion(payload, 1);
        if (version.status() != Status.PASS) return version;
        String canonical;
        try {
            JsonNode node = JSON.readTree(payload);
            Set<String> required = Set.of("occurred_at", "actor_type", "actor_user_id", "actor_role", "action", "entity_type", "entity_id", "before_state", "after_state", "amount", "currency", "correlation_id", "request_id", "outcome", "client_info_masked", "hash_version");
            if (node.size() != required.size() || !required.stream().allMatch(node::has)) {
                return new Result(Status.INCOMPLETE, "PAYLOAD_FIELDS");
            }
            canonical = AuditCanonicalJson.canonicalize(JSON.convertValue(node, Object.class));
        } catch (Exception ex) { return new Result(Status.MISMATCH, "PAYLOAD"); }
        if (!AuditHashing.recordHash(canonical, rowPreviousHash).equals(rowRecordHash)) {
            return new Result(Status.MISMATCH, "RECORD_HASH");
        }
        return new Result(Status.INCOMPLETE, "COLUMN_COMPARISON_NOT_IMPLEMENTED");
    }

    public static Result verifyRow(String payload, int rowVersion, String previousHash, String recordHash,
                                   String expectedPreviousHash, JsonNode columns) {
        Result version = verifyPayloadVersion(payload, rowVersion);
        if (version.status() != Status.PASS) return version;
        Result record = verifyRecord(payload, previousHash, recordHash, expectedPreviousHash);
        if (record.status() != Status.INCOMPLETE || !"COLUMN_COMPARISON_NOT_IMPLEMENTED".equals(record.reason())) return record;
        try {
            JsonNode node = JSON.readTree(payload);
            if (columns == null || !node.equals(columns)) return new Result(Status.MISMATCH, "COLUMN_MISMATCH");
            return new Result(Status.PASS, null);
        } catch (java.io.IOException ex) {
            return new Result(Status.MISMATCH, "PAYLOAD");
        }
    }

    public static Result verifyPayloadVersion(String payload, int rowVersion) {
        if (rowVersion != 1) return new Result(Status.UNSUPPORTED, "HASH_VERSION");
        try {
            JsonNode node = JSON.readTree(payload);
            if (node == null || !node.isObject() || !node.has("hash_version") || !node.get("hash_version").isIntegralNumber() || !node.get("hash_version").canConvertToInt()) {
                return new Result(Status.MISMATCH, "PAYLOAD_VERSION");
            }
            int version = node.get("hash_version").intValue();
            if (version != rowVersion) return new Result(Status.MISMATCH, "PAYLOAD_VERSION");
            return new Result(Status.PASS, null);
        } catch (Exception ex) {
            return new Result(Status.MISMATCH, "PAYLOAD");
        }
    }
}
