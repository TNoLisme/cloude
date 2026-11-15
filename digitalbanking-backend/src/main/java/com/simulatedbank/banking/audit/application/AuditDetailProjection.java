package com.simulatedbank.banking.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Read-time projection and safe validation for historical audit snapshots and client information.
 * Enforces per-entity field allowlists, resource bounds, and fail-closed security without mutating historical evidence.
 */
public final class AuditDetailProjection {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Pattern SAFE_MASKED_IP = Pattern.compile("^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}\\*{1,3}$");
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i)@|\\bBearer\\s|\\b(?:token|otp|password)\\s*[:=]");
    private static final Set<String> ALLOWED_CLIENT_INFO_KEYS = Set.of("ipMasked", "userAgent");

    private static final int MAX_SNAPSHOT_BYTES = 32 * 1024; // 32 KiB
    private static final int MAX_ROOT_FIELDS = 64;
    private static final int MAX_NESTING_DEPTH = 6;
    private static final int MAX_TOTAL_NODES = 256;

    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private static final Pattern DECIMAL_PATTERN = Pattern.compile("^[0-9]{1,17}(?:\\.[0-9]{1,2})?$");
    private static final Pattern ROLE_CODE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_:-]{0,63}$");
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_:-]{0,63}$");
    private static final Pattern WINDOW_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_:-]{0,31}$");
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("^TXN[A-Za-z0-9]{8,29}$");
    private static final Pattern LAST4_PATTERN = Pattern.compile("^[0-9]{4}$");
    private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private static final Set<String> ALLOWED_FRAUD_MATCHED_KEYS = Set.of("amount", "count", "window");

    private static final Set<String> MONEY_FIELDS = Set.of(
            "amount", "availableBalance", "ledgerBalance", "perTransactionLimit", "dailyLimit");

    private static final Set<String> COUNTER_FIELDS = Set.of(
            "attemptCount", "rowCount", "recordCount", "ruleVersion", "count",
            "retentionDays", "archiveAfterDays", "purgeAfterDays");

    private static final Set<String> ID_FIELDS = Set.of(
            "sourceAccountId", "destinationAccountId", "accountId", "customerId", "userId",
            "approvalId", "flagId", "holdId", "policyId", "configId", "challengeId", "sessionId",
            "deviceId", "targetId", "makerId", "checkerId", "entityId", "scopeId", "exportId");

    private static final Set<String> CODE_FIELDS = Set.of(
            "status", "failureCode", "currency", "ruleCode", "severity", "decision", "reasonCode",
            "purpose", "method", "dataClass", "format", "scopeType", "requestType", "targetType",
            "entityType", "transferType");

    private static final Set<String> TIMESTAMP_FIELDS = Set.of(
            "createdAt", "completedAt", "expiresAt", "lockedUntil", "placedAt", "releasedAt",
            "effectiveFrom", "effectiveTo", "from", "to");

    private static final Set<String> HASH_FIELDS = Set.of(
            "fileHash", "filterHash", "keyHash", "scopeHash");

    private static final Set<String> REFERENCE_FIELDS = Set.of(
            "reference", "transferReference");

    public static final Map<String, Set<String>> ALLOWED_ENTITY_FIELDS = Map.ofEntries(
            Map.entry("TRANSFER", Set.of("reference", "status", "failureCode", "sourceAccountId",
                    "destinationAccountId", "amount", "currency", "createdAt", "completedAt", "transferType")),
            Map.entry("ACCOUNT", Set.of("accountId", "customerId", "status", "last4",
                    "availableBalance", "ledgerBalance", "reasonCode")),
            Map.entry("APP_USER", Set.of("userId", "roleCodes", "status", "lockedUntil", "reasonCode")),
            Map.entry("CUSTOMER", Set.of("customerId", "status", "reasonCode")),
            Map.entry("LIMIT_CONFIG", Set.of("configId", "scopeType", "scopeId", "perTransactionLimit",
                    "dailyLimit", "effectiveFrom", "effectiveTo", "status")),
            Map.entry("APPROVAL_REQUEST", Set.of("approvalId", "requestType", "targetType", "targetId",
                    "status", "makerId", "checkerId", "decision")),
            Map.entry("FRAUD_FLAG", Set.of("flagId", "transferReference", "ruleCode", "ruleVersion",
                    "severity", "status", "decision", "matchedValue")),
            Map.entry("AUTH_CHALLENGE", Set.of("challengeId", "purpose", "method", "status",
                    "attemptCount", "expiresAt")),
            Map.entry("SESSION", Set.of("sessionId", "userId", "deviceId", "status", "reasonCode")),
            Map.entry("LEGAL_HOLD", Set.of("holdId", "entityType", "entityId", "status", "reasonCode",
                    "placedAt", "releasedAt")),
            Map.entry("RETENTION_POLICY", Set.of("policyId", "dataClass", "retentionDays",
                    "archiveAfterDays", "purgeAfterDays", "enabled")),
            Map.entry("AUDIT_LOG", Set.of("exportId", "filterHash", "rowCount", "from", "to", "format", "fileHash")),
            Map.entry("IDEMPOTENCY_RECORD", Set.of("keyHash", "transferReference", "status")),
            Map.entry("DATA_SCOPE", Set.of("scopeType", "scopeHash", "recordCount", "range"))
    );

    public record ClientInfo(String ipMasked, String userAgent) { }

    private AuditDetailProjection() { }

    public static Map<String, Object> projectSnapshot(String entityType, String json) {
        if (json == null || json.isBlank()) return null;
        if (entityType == null || !ALLOWED_ENTITY_FIELDS.containsKey(entityType)) {
            throw new IllegalStateException("Historical audit snapshot entity type is unsupported or missing");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) {
            throw new IllegalStateException("Historical audit snapshot exceeds maximum byte size");
        }

        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("Historical audit snapshot contains malformed JSON");
        }
        if (!root.isObject()) {
            throw new IllegalStateException("Historical audit snapshot is not a JSON object");
        }
        if (root.size() > MAX_ROOT_FIELDS) {
            throw new IllegalStateException("Historical audit snapshot exceeds maximum field limit");
        }

        Set<String> allowedFields = ALLOWED_ENTITY_FIELDS.get(entityType);
        Map<String, Object> map = new LinkedHashMap<>();
        AtomicInteger nodeCount = new AtomicInteger(1); // root node

        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if (!allowedFields.contains(key)) {
                throw new IllegalStateException("Historical audit snapshot contains unallowlisted field for entity: " + entityType);
            }
            if (!SAFE_KEY.matcher(key).matches()) {
                throw new IllegalStateException("Historical audit snapshot contains invalid key");
            }
            if (isSecretKey(key)) {
                throw new IllegalStateException("Historical audit snapshot contains prohibited key");
            }
            map.put(key, projectFieldValue(entityType, key, entry.getValue(), 1, nodeCount));
        }
        return Collections.unmodifiableMap(map);
    }

    public static ClientInfo projectClientInfo(String json) {
        if (json == null || json.isBlank()) return null;
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) {
            throw new IllegalStateException("Historical audit client info exceeds maximum byte size");
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("Historical audit client info contains malformed JSON");
        }
        if (!root.isObject()) {
            throw new IllegalStateException("Historical audit client info is not a JSON object");
        }
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String name = fieldNames.next();
            if (!ALLOWED_CLIENT_INFO_KEYS.contains(name)) {
                throw new IllegalStateException("Historical audit client info contains unallowlisted field");
            }
        }

        String ipMasked = null;
        if (root.has("ipMasked") && !root.get("ipMasked").isNull()) {
            JsonNode ipNode = root.get("ipMasked");
            if (!ipNode.isTextual()) {
                throw new IllegalStateException("Historical audit ipMasked must be text");
            }
            String ip = ipNode.asText();
            if (!SAFE_MASKED_IP.matcher(ip).matches()) {
                throw new IllegalStateException("Historical audit ipMasked is not properly masked");
            }
            ipMasked = ip;
        }

        String userAgent = null;
        if (root.has("userAgent") && !root.get("userAgent").isNull()) {
            JsonNode uaNode = root.get("userAgent");
            if (!uaNode.isTextual()) {
                throw new IllegalStateException("Historical audit userAgent must be text");
            }
            String ua = uaNode.asText();
            if (ua.length() > 256 || ua.codePoints().anyMatch(Character::isISOControl) || SENSITIVE_PATTERN.matcher(ua).find()) {
                throw new IllegalStateException("Historical audit userAgent contains invalid or sensitive characters");
            }
            userAgent = ua;
        }

        return new ClientInfo(ipMasked, userAgent);
    }

    private static Object projectFieldValue(String entityType, String key, JsonNode node, int depth, AtomicInteger nodeCount) {
        if (depth > MAX_NESTING_DEPTH) {
            throw new IllegalStateException("Historical audit snapshot exceeds maximum nesting depth");
        }
        if (nodeCount.incrementAndGet() > MAX_TOTAL_NODES) {
            throw new IllegalStateException("Historical audit snapshot exceeds maximum node count");
        }
        if (node == null || node.isNull()) return null;

        // Nested object handling: only FRAUD_FLAG.matchedValue is permitted across all entities
        if ("matchedValue".equals(key)) {
            if (!"FRAUD_FLAG".equals(entityType)) {
                throw new IllegalStateException("Historical audit matchedValue is only permitted for FRAUD_FLAG");
            }
            if (!node.isObject()) {
                throw new IllegalStateException("Historical audit matchedValue must be a JSON object");
            }
            Map<String, Object> child = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                var childEntry = it.next();
                String childKey = childEntry.getKey();
                if (!SAFE_KEY.matcher(childKey).matches() || isSecretKey(childKey)) {
                    throw new IllegalStateException("Historical audit matchedValue contains invalid key");
                }
                if (!ALLOWED_FRAUD_MATCHED_KEYS.contains(childKey)) {
                    throw new IllegalStateException("Historical audit matchedValue contains unallowlisted field");
                }
                JsonNode childValue = childEntry.getValue();
                if (depth + 1 > MAX_NESTING_DEPTH) {
                    throw new IllegalStateException("Historical audit snapshot exceeds maximum nesting depth");
                }
                if (nodeCount.incrementAndGet() > MAX_TOTAL_NODES) {
                    throw new IllegalStateException("Historical audit snapshot exceeds maximum node count");
                }
                if (childValue == null || childValue.isNull()) {
                    throw new IllegalStateException("Historical audit matchedValue field cannot be null");
                }
                if ("count".equals(childKey)) {
                    if (!childValue.isIntegralNumber() || !childValue.canConvertToLong()) {
                        throw new IllegalStateException("Historical audit matchedValue count must be an integer");
                    }
                    long count = childValue.asLong();
                    if (count < 0 || count > MAX_SAFE_INTEGER) {
                        throw new IllegalStateException("Historical audit matchedValue count must be a non-negative safe integer");
                    }
                    child.put(childKey, count);
                } else if ("amount".equals(childKey)) {
                    if (!childValue.isTextual()) {
                        throw new IllegalStateException("Historical audit matchedValue amount must be decimal text");
                    }
                    String amount = childValue.asText();
                    validateTextScalar(amount);
                    if (!DECIMAL_PATTERN.matcher(amount).matches()) {
                        throw new IllegalStateException("Historical audit matchedValue amount must be valid decimal text");
                    }
                    child.put(childKey, amount);
                } else if ("window".equals(childKey)) {
                    if (!childValue.isTextual()) {
                        throw new IllegalStateException("Historical audit matchedValue window must be text");
                    }
                    String window = childValue.asText();
                    validateTextScalar(window);
                    if (!WINDOW_PATTERN.matcher(window).matches()) {
                        throw new IllegalStateException("Historical audit matchedValue window must be a bounded code");
                    }
                    child.put(childKey, window);
                }
            }
            return Collections.unmodifiableMap(child);
        }

        // Any other field must NOT be an object
        if (node.isObject()) {
            throw new IllegalStateException("Historical audit snapshot contains undefined nested object for field: " + key);
        }

        // Array handling: only APP_USER.roleCodes is permitted
        if (node.isArray()) {
            if (!"APP_USER".equals(entityType) || !"roleCodes".equals(key)) {
                throw new IllegalStateException("Historical audit snapshot contains undefined array for field: " + key);
            }
            if (node.size() > 512) {
                throw new IllegalStateException("Historical audit roleCodes exceeds 512 items");
            }
            java.util.List<Object> list = new java.util.ArrayList<>(node.size());
            for (JsonNode item : node) {
                if (depth + 1 > MAX_NESTING_DEPTH) {
                    throw new IllegalStateException("Historical audit snapshot exceeds maximum nesting depth");
                }
                if (nodeCount.incrementAndGet() > MAX_TOTAL_NODES) {
                    throw new IllegalStateException("Historical audit snapshot exceeds maximum node count");
                }
                if (item == null || item.isNull() || !item.isTextual()) {
                    throw new IllegalStateException("Historical audit roleCodes element must be text");
                }
                String role = item.asText();
                validateTextScalar(role);
                if (!ROLE_CODE_PATTERN.matcher(role).matches()) {
                    throw new IllegalStateException("Historical audit roleCodes element must be a bounded code");
                }
                list.add(role);
            }
            return Collections.unmodifiableList(list);
        }

        // No floating-point numbers allowed anywhere in v1 snapshots
        if (node.isFloatingPointNumber()) {
            throw new IllegalStateException("Historical audit snapshot contains floating-point number violating v1 contract");
        }

        // Integral numbers: only counters and ID fields may be integers
        if (node.isIntegralNumber()) {
            if (MONEY_FIELDS.contains(key)) {
                throw new IllegalStateException("Historical audit money field must be decimal text, not number: " + key);
            }
            if (!node.canConvertToLong()) {
                throw new IllegalStateException("Historical audit integer exceeds 64-bit long limit");
            }
            long val = node.asLong();
            if (COUNTER_FIELDS.contains(key) || ID_FIELDS.contains(key)) {
                if (val < 0 || val > MAX_SAFE_INTEGER) {
                    throw new IllegalStateException("Historical audit integer must be a non-negative safe integer: " + key);
                }
                return val;
            }
            throw new IllegalStateException("Historical audit field cannot be a number: " + key);
        }

        // Booleans: only enabled is boolean
        if (node.isBoolean()) {
            if ("enabled".equals(key)) {
                return node.asBoolean();
            }
            throw new IllegalStateException("Historical audit field cannot be boolean: " + key);
        }

        // Text scalars
        if (node.isTextual()) {
            String text = node.asText();
            validateTextScalar(text);

            if (MONEY_FIELDS.contains(key)) {
                if (!DECIMAL_PATTERN.matcher(text).matches()) {
                    throw new IllegalStateException("Historical audit money field must be valid decimal text: " + key);
                }
            } else if ("last4".equals(key)) {
                if (!LAST4_PATTERN.matcher(text).matches()) {
                    throw new IllegalStateException("Historical audit last4 must contain exactly four digits");
                }
            } else if (REFERENCE_FIELDS.contains(key)) {
                if (!REFERENCE_PATTERN.matcher(text).matches()) {
                    throw new IllegalStateException("Historical audit reference is invalid: " + key);
                }
            } else if (HASH_FIELDS.contains(key)) {
                if (!HASH_PATTERN.matcher(text).matches()) {
                    throw new IllegalStateException("Historical audit hash must be lowercase SHA-256 hex: " + key);
                }
            } else if (TIMESTAMP_FIELDS.contains(key)) {
                if (text.length() > 40) {
                    throw new IllegalStateException("Historical audit timestamp too long");
                }
                try {
                    java.time.Instant.parse(text);
                } catch (Exception ex) {
                    throw new IllegalStateException("Historical audit timestamp is invalid: " + key);
                }
            } else if (CODE_FIELDS.contains(key)) {
                if (!CODE_PATTERN.matcher(text).matches()) {
                    throw new IllegalStateException("Historical audit code is invalid: " + key);
                }
            } else if (ID_FIELDS.contains(key)) {
                if (!SAFE_KEY.matcher(text).matches()) {
                    throw new IllegalStateException("Historical audit identifier is invalid: " + key);
                }
            }
            return text;
        }

        throw new IllegalStateException("Historical audit snapshot contains unsupported JSON type for field: " + key);
    }

    private static void validateTextScalar(String text) {
        if (text.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("Historical audit value contains control characters");
        }
        if (SENSITIVE_PATTERN.matcher(text).find()) {
            throw new IllegalStateException("Historical audit value contains prohibited sensitive token pattern");
        }
    }

    private static boolean isSecretKey(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("token") || lower.contains("password") || lower.contains("secret") || lower.contains("otp");
    }
}
