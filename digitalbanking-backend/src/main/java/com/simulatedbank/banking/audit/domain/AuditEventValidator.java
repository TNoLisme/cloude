package com.simulatedbank.banking.audit.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class AuditEventValidator {
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Pattern SAFE_USER_AGENT_SECRET = Pattern.compile(
            "(?i)@|\\bBearer\\s|\\b(?:token|otp|phone|password)\\s*[:=]|(?<![0-9])[0-9]{9,20}(?![0-9])");
    private static final Set<String> ACTOR_TYPES = Set.of("USER", "SYSTEM");
    private static final Set<String> OUTCOMES = Set.of("SUCCESS", "FAILURE", "REJECTED");
    private static final Currency VND = Currency.getInstance("VND");

    private AuditEventValidator() { }

    public static void validateAmountAndCurrency(BigDecimal amount, String currency) {
        if (amount == null) {
            if (currency != null) throw new IllegalArgumentException("Currency requires an amount");
            return;
        }
        AuditSnapshotValues.validateMoney(amount);
        if (!VND.getCurrencyCode().equals(currency)) {
            throw new IllegalArgumentException("Currency must be VND");
        }
    }

    public static void validate(AuditEvent event) {
        if (event == null) throw new IllegalArgumentException("Audit event is required");
        var action = AuditActionCatalog.require(event.action());
        if (AuditActionCatalog.requiresOwnerReview(event.action())) {
            throw new IllegalArgumentException("Audit action contract requires owner review: " + event.action());
        }
        if (event.actorType() == null || !ACTOR_TYPES.contains(event.actorType())
                || !action.allowedActorTypes().contains(event.actorType())) {
            throw new IllegalArgumentException("Actor type is invalid for audit action");
        }
        if ("USER".equals(event.actorType()) && (event.actorUserId() == null || event.actorUserId() <= 0)) {
            throw new IllegalArgumentException("User actor requires a positive user ID");
        }
        if ("SYSTEM".equals(event.actorType()) && event.actorUserId() != null) {
            throw new IllegalArgumentException("System actor must not have a user ID");
        }
        if (event.actorRole() == null || event.actorRole().isBlank() || event.actorRole().length() > 32
                || !SAFE_ID.matcher(event.actorRole()).matches()) {
            throw new IllegalArgumentException("Actor role is required");
        }
        if (!action.entityType().equals(event.entityType()) || !validId(event.entityId())) {
            throw new IllegalArgumentException("Entity type or ID is invalid");
        }
        if (event.outcome() == null || !OUTCOMES.contains(event.outcome())) {
            throw new IllegalArgumentException("Outcome is invalid");
        }
        if ("TRANSFER_FAILED".equals(event.action()) && !"FAILURE".equals(event.outcome())) {
            throw new IllegalArgumentException("Transfer failure must use FAILURE outcome");
        }
        if ("SECURITY_ACCESS_DENIED".equals(event.action()) && !"REJECTED".equals(event.outcome())) {
            throw new IllegalArgumentException("Access denial must use REJECTED outcome");
        }
        if (!validId(event.correlationId()) || !validId(event.requestId())) {
            throw new IllegalArgumentException("Correlation and request IDs must be safe identifiers");
        }
        validateAmountAndCurrency(event.amount(), event.currency());
        for (String required : action.requiredFields()) {
            if ("amount".equals(required) && event.amount() == null) {
                throw new IllegalArgumentException("Action requires an amount");
            }
            if ("currency".equals(required) && event.currency() == null) {
                throw new IllegalArgumentException("Action requires a currency");
            }
            if (required.startsWith("before_state.") || required.startsWith("after_state.")) {
                Map<String, Object> state = required.startsWith("before_state.") ? event.beforeState() : event.afterState();
                Object value = state == null ? null : state.get(required.substring(required.indexOf('.') + 1));
                if (value == null || value instanceof String string && string.isBlank()) {
                    throw new IllegalArgumentException("Action requires field: " + required);
                }
            }
        }
        validateState(event.entityType(), event.beforeState(), "before_state");
        validateState(event.entityType(), event.afterState(), "after_state");
        validateClientInfo(event.clientInfoMasked());
        if (AuditActionCatalog.requiresClientInfo(event.action()) && event.clientInfoMasked() == null) {
            throw new IllegalArgumentException("Action requires masked client information");
        }
    }

    private static boolean validId(String value) {
        return value != null && SAFE_ID.matcher(value).matches();
    }

    private static void validateState(String entityType, Map<String, Object> state, String name) {
        if (state == null) return;
        if (state.size() > 64) throw new IllegalArgumentException("Snapshot has too many fields");
        if (AuditCanonicalJson.serialize(state).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32 * 1024) {
            throw new IllegalArgumentException("Snapshot exceeds 32 KiB");
        }
        Set<String> allowed = switch (entityType) {
            case "TRANSFER" -> Set.of("reference", "status", "failureCode", "sourceAccountId",
                    "destinationAccountId", "amount", "currency", "createdAt", "completedAt", "transferType");
            case "ACCOUNT" -> Set.of("accountId", "customerId", "status", "last4", "availableBalance", "ledgerBalance", "reasonCode");
            case "APP_USER" -> Set.of("userId", "roleCodes", "status", "lockedUntil", "reasonCode");
            case "CUSTOMER" -> Set.of("customerId", "status", "reasonCode");
            case "LIMIT_CONFIG" -> Set.of("configId", "scopeType", "scopeId", "perTransactionLimit", "dailyLimit", "effectiveFrom", "effectiveTo", "status");
            case "APPROVAL_REQUEST" -> Set.of("approvalId", "requestType", "targetType", "targetId", "status", "makerId", "checkerId", "decision");
            case "FRAUD_FLAG" -> Set.of("flagId", "transferReference", "ruleCode", "ruleVersion", "severity", "status", "decision", "matchedValue");
            case "AUTH_CHALLENGE" -> Set.of("challengeId", "purpose", "method", "status", "attemptCount", "expiresAt");
            case "SESSION" -> Set.of("sessionId", "userId", "deviceId", "status", "reasonCode");
            case "LEGAL_HOLD" -> Set.of("holdId", "entityType", "entityId", "status", "reasonCode", "placedAt", "releasedAt");
            case "RETENTION_POLICY" -> Set.of("policyId", "dataClass", "retentionDays", "archiveAfterDays", "purgeAfterDays", "enabled");
            case "AUDIT_LOG" -> Set.of("exportId", "filterHash", "rowCount", "from", "to", "format", "fileHash");
            case "IDEMPOTENCY_RECORD" -> Set.of("keyHash", "transferReference", "status");
            case "DATA_SCOPE" -> Set.of("scopeType", "scopeHash", "recordCount", "range");
            default -> throw new IllegalArgumentException("Unsupported entity type");
        };
        for (String key : state.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException("Field is not allowlisted: " + key);
            validateSnapshotValue(entityType, key, state.get(key));
        }
        if (actionRequiresStatus(name, entityType) && !state.containsKey("status")) {
            throw new IllegalArgumentException(name + " requires status");
        }
    }

    private static boolean actionRequiresStatus(String snapshotName, String entityType) {
        return ("before_state".equals(snapshotName) || "after_state".equals(snapshotName))
                && ("TRANSFER".equals(entityType) || "ACCOUNT".equals(entityType));
    }

    private static void validateSnapshotValue(String entityType, String key, Object value) {
        if (value == null) return;
        if ("last4".equals(key) && (!(value instanceof String string) || !string.matches("[0-9]{4}"))) {
            throw new IllegalArgumentException("Account last4 must contain exactly four digits");
        }
        if (Set.of("status", "failureCode", "currency", "ruleCode", "severity", "decision", "reasonCode",
                "purpose", "method", "dataClass", "format", "scopeType", "requestType", "targetType",
                "entityType", "transferType").contains(key)
                && (!(value instanceof String string) || !string.matches("[A-Za-z][A-Za-z0-9_:-]{0,63}"))) {
            throw new IllegalArgumentException("Snapshot field must be a bounded code: " + key);
        }
        if (Set.of("createdAt", "completedAt", "expiresAt", "lockedUntil", "placedAt", "releasedAt",
                "effectiveFrom", "effectiveTo", "from", "to").contains(key)) {
            if (!(value instanceof String text) || text.length() > 40) {
                throw new IllegalArgumentException("Snapshot timestamp must be ISO-8601 text");
            }
            try {
                java.time.Instant.parse((String) value);
            } catch (java.time.format.DateTimeParseException exception) {
                throw new IllegalArgumentException("Invalid snapshot timestamp");
            }
        }
        if (Set.of("fileHash", "filterHash", "keyHash", "scopeHash").contains(key)
                && (!(value instanceof String text) || !text.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Snapshot hash must be lowercase SHA-256 hex");
        }
        if ("enabled".equals(key) && !(value instanceof Boolean)) {
            throw new IllegalArgumentException("Snapshot enabled must be boolean");
        }
        if ("RETENTION_POLICY".equals(entityType) && "dataClass".equals(key)
                && (!(value instanceof String dataClass) || !Set.of("TRANSFER", "LEDGER_ENTRY", "AUDIT_LOG", "AUTH_CHALLENGE", "SESSION", "NOTIFICATION", "FRAUD_FLAG", "LIMIT_HISTORY", "IDEMPOTENCY_RECORD").contains(dataClass))) {
            throw new IllegalArgumentException("Retention data class is not supported");
        }
        if ("APPROVAL_REQUEST".equals(entityType) && "requestType".equals(key)
                && (!(value instanceof String requestType) || !Set.of("LIMIT_CHANGE", "ROLE_CHANGE", "RETENTION_HOLD", "RETENTION_POLICY_CHANGE").contains(requestType))) {
            throw new IllegalArgumentException("Approval request type is not supported");
        }
        if (Set.of("amount", "availableBalance", "ledgerBalance", "perTransactionLimit", "dailyLimit").contains(key)
                && (!(value instanceof String || value instanceof BigDecimal))) {
            throw new IllegalArgumentException("Money snapshot field must be decimal text: " + key);
        }
        if (AuditSnapshotValues.COUNTERS.contains(key)
                && (!(value instanceof Integer || value instanceof Long)
                    || ((Number) value).longValue() < 0 || ((Number) value).longValue() > 9_007_199_254_740_991L)) {
            throw new IllegalArgumentException("Snapshot counter must be a non-negative safe integer: " + key);
        }
        if (Set.of("sourceAccountId", "destinationAccountId", "accountId", "customerId", "userId",
                "approvalId", "flagId", "holdId", "policyId", "recordCount", "rowCount", "attemptCount", "ruleVersion").contains(key)
                && !(value instanceof String || value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException("Snapshot identifier/counter has an invalid type: " + key);
        }
        if ("TRANSFER".equals(entityType) && "reference".equals(key)
                && (!(value instanceof String string) || !string.matches("^TXN[A-Za-z0-9]{8,29}$"))) {
            throw new IllegalArgumentException("Transfer reference is invalid");
        }
        if ("FRAUD_FLAG".equals(entityType) && "transferReference".equals(key)
                && (!(value instanceof String reference) || !reference.matches("TXN[A-Za-z0-9]{8,29}"))) {
            throw new IllegalArgumentException("Fraud transfer reference is invalid");
        }
        if ("roleCodes".equals(key)) {
            if (!(value instanceof java.util.List<?> roles) || roles.size() > 512) {
                throw new IllegalArgumentException("Role codes must be a bounded list");
            }
            for (Object role : roles) {
                if (!(role instanceof String code) || !code.matches("[A-Za-z][A-Za-z0-9_:-]{0,63}")) {
                    throw new IllegalArgumentException("Role codes must contain bounded codes only");
                }
            }
        }
        if ("FRAUD_FLAG".equals(entityType) && "matchedValue".equals(key)) {
            if (!(value instanceof Map<?, ?> map)
                    || !Set.of("amount", "count", "window").containsAll(map.keySet())) {
                throw new IllegalArgumentException("Fraud matched value is invalid");
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String field) || entry.getValue() == null) {
                    throw new IllegalArgumentException("Fraud matched values must not be null");
                }
                Object item = entry.getValue();
                if ("count".equals(field) && (!(item instanceof Integer || item instanceof Long)
                        || ((Number) item).longValue() < 0 || ((Number) item).longValue() > 9_007_199_254_740_991L)) {
                    throw new IllegalArgumentException("Fraud match count must be a non-negative safe integer");
                }
                if ("amount".equals(field) && !(item instanceof String)) {
                    throw new IllegalArgumentException("Fraud match amount must be decimal text");
                }
                if ("window".equals(field) && (!(item instanceof String window)
                        || !window.matches("[A-Za-z][A-Za-z0-9_:-]{0,31}"))) {
                    throw new IllegalArgumentException("Fraud match window must be a bounded code");
                }
            }
        }
    }

    private static void validateClientInfo(Map<String, Object> clientInfo) {
        if (clientInfo == null) return;
        if (clientInfo.size() > 2 || !clientInfo.keySet().stream().allMatch(Set.of("ipMasked", "userAgent")::contains)) {
            throw new IllegalArgumentException("Client info field is not allowlisted");
        }
        Object userAgent = clientInfo.get("userAgent");
        if (userAgent != null && (!(userAgent instanceof String string) || string.length() > 256
                || string.codePoints().anyMatch(Character::isISOControl)
                || SAFE_USER_AGENT_SECRET.matcher(string).find())) {
            throw new IllegalArgumentException("User agent is invalid");
        }
        Object ip = clientInfo.get("ipMasked");
        if (ip != null && (!(ip instanceof String string) || !string.matches("^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}\\*{1,3}$"))) {
            throw new IllegalArgumentException("IP address must be masked");
        }
    }

    private static int estimateBytes(Object value) {
        if (value == null) return 4;
        if (value instanceof String string) return string.length() * 4 + 2;
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().mapToInt(entry -> estimateBytes(entry.getKey()) + estimateBytes(entry.getValue()) + 4).sum();
        }
        if (value instanceof Iterable<?> iterable) {
            int size = 0;
            for (Object item : iterable) size += estimateBytes(item);
            return size;
        }
        return 16;
    }
}
