package com.simulatedbank.banking.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Fail-closed validation of provider-owned audit events before any SQL is executed (design/01 §4.3, §5.3–§5.6,
 * §6.3, §7.2). Unknown actions cannot exist because {@link AuditAction} is the catalog enum; everything a
 * consumer can still get wrong is rejected here:
 *
 * <ul>
 *   <li>actor rules — {@code USER} requires a positive {@code actorUserId}, {@code SYSTEM} must not carry one;</li>
 *   <li>entity type allowlist and bounded identifiers (design/01 §6.3);</li>
 *   <li>{@code before_state}/{@code after_state} keys against the §5.6 per-entity allowlist plus the documented
 *       approval/limit fields, with IDs/money as strings, counters as integers and no floats;</li>
 *   <li>value scanner — account numbers, e-mail, phone, OTP, bearer/JWT/password-style values and free text over
 *       512 characters are rejected; {@code client_info_masked} is limited to the three documented keys;</li>
 *   <li>canonical payload at most 32 KiB (JCS UTF-8 bytes).</li>
 * </ul>
 *
 * <p>Every rejection is a {@link BankingBusinessException} with
 * {@link ErrorCode#AUDIT_WRITE_FAILED} and a constant safe detail; the offending value never appears in the
 * exception, metric or log. The validator also builds the immutable canonical payload (JCS) so the writer only
 * has to read the previous hash and hash it.</p>
 */
@Component
public class AuditEventValidator {

    private static final int MAX_CANONICAL_BYTES = 32 * 1024;
    private static final int MAX_STATE_TEXT = 512;
    private static final int MAX_CLIENT_INFO_TEXT = 256;
    private static final int MAX_ACTOR_ROLE = 32;
    private static final int MAX_ENTITY_TYPE = 32;
    private static final int MAX_ENTITY_ID = 64;
    private static final int MAX_TRACE_ID = 64;

    private static final Set<String> ENTITY_TYPES = Set.of(
            "TRANSFER", "ACCOUNT", "APP_USER", "CUSTOMER", "LIMIT_CONFIG", "APPROVAL_REQUEST", "FRAUD_FLAG",
            "AUTH_CHALLENGE", "SESSION", "LEGAL_HOLD", "RETENTION_POLICY", "AUDIT_LOG", "DATA_SCOPE",
            "IDEMPOTENCY_RECORD", "SYSTEM");

    private static final Map<String, Set<String>> STATE_FIELDS = Map.ofEntries(
            Map.entry("TRANSFER", Set.of("reference", "status", "failureCode", "sourceAccountId",
                    "destinationAccountId", "amount", "currency", "createdAt", "completedAt", "transferType", "reasonCode")),
            Map.entry("ACCOUNT", Set.of("accountId", "customerId", "status", "last4", "availableBalance",
                    "ledgerBalance", "reasonCode")),
            Map.entry("APP_USER", Set.of("userId", "roleCodes", "status", "lockedUntil", "reasonCode")),
            Map.entry("CUSTOMER", Set.of("customerId", "status", "reasonCode")),
            Map.entry("LIMIT_CONFIG", Set.of("configId", "scopeType", "scopeId", "perTransactionLimit",
                    "dailyLimit", "effectiveFrom", "effectiveTo", "status")),
            Map.entry("APPROVAL_REQUEST", Set.of("approvalId", "requestType", "targetType", "targetId", "status",
                    "makerId", "checkerId", "decision")),
            Map.entry("FRAUD_FLAG", Set.of("flagId", "transferReference", "ruleCode", "ruleVersion", "severity",
                    "status", "decision", "matchedValue")),
            Map.entry("AUTH_CHALLENGE", Set.of("challengeId", "purpose", "method", "status", "attemptCount",
                    "expiresAt", "reasonCode")),
            Map.entry("SESSION", Set.of("sessionId", "userId", "deviceId", "status", "reasonCode")),
            Map.entry("LEGAL_HOLD", Set.of("holdId", "entityType", "entityId", "status", "reasonCode",
                    "placedAt", "releasedAt")),
            Map.entry("RETENTION_POLICY", Set.of("policyId", "dataClass", "retentionDays", "archiveAfterDays",
                    "purgeAfterDays", "enabled")),
            Map.entry("AUDIT_LOG", Set.of("exportId", "filterHash", "rowCount", "from", "to", "format",
                    "fileHash")),
            Map.entry("IDEMPOTENCY_RECORD", Set.of("keyHash", "transferReference", "status")),
            Map.entry("DATA_SCOPE", Set.of("scopeType", "scopeHash", "recordCount", "range")),
            Map.entry("SYSTEM", Set.of("jobName", "result", "counts")));

    /**
     * Approval/limit fields documented by design/01 §5.5 and the Admin/Limit providers; they are accepted for the
     * approval entity even when the §5.6 table lists them under a different provider entity.
     */
    private static final Set<String> APPROVAL_AND_LIMIT_FIELDS = Set.of(
            "approvalId", "requestType", "targetType", "targetId", "status", "makerId", "checkerId", "decision",
            "operation", "roleCode", "privilegeCode", "perTransactionLimit", "dailyLimit", "version",
            "previousVersion", "resultingVersion", "scopeType", "scopeId");

    private static final Set<String> CLIENT_INFO_FIELDS = Set.of("ipMasked", "userAgent", "deviceIdHash");

    private static final Set<String> STRING_ONLY_FIELDS = Set.of(
            "approvalId", "targetId", "makerId", "checkerId", "userId", "customerId", "accountId",
            "sourceAccountId", "destinationAccountId", "configId", "flagId", "challengeId", "sessionId",
            "deviceId", "holdId", "policyId", "exportId", "scopeId", "entityId", "amount", "availableBalance",
            "ledgerBalance", "perTransactionLimit", "dailyLimit");

    /** Counters are canonical JSON integers, never strings and never floats (design/01 §7.2). */
    private static final Set<String> INTEGER_ONLY_FIELDS = Set.of(
            "attemptCount", "rowCount", "recordCount", "count", "counts", "retentionDays", "archiveAfterDays",
            "purgeAfterDays", "ruleVersion", "revokedSessionCount");

    /** Numeric-looking opaque identifiers are legitimate; they are exempt from the account/OTP numeric scan. */
    private static final Set<String> NUMERIC_SCAN_EXEMPT_FIELDS = Set.of(
            "approvalId", "targetId", "makerId", "checkerId", "userId", "customerId", "accountId",
            "sourceAccountId", "destinationAccountId", "configId", "flagId", "challengeId", "sessionId",
            "deviceId", "holdId", "policyId", "exportId", "scopeId", "entityId", "version", "previousVersion",
            "resultingVersion", "ruleVersion", "keyHash", "fileHash", "filterHash", "scopeHash", "matchedValue",
            "range", "from", "to");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|passcode|secret|token|authorization|api[_-]?key|csrf[_-]?token|otp)"
                    + "\\s*[:=]\\s*\\S+");
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile(
            "(?<![0-9A-Za-z.,-])\\d{8,19}(?![0-9A-Za-z.,-])");
    private static final Pattern PHONE = Pattern.compile(
            "(?<![0-9A-Za-z])(?:\\+?84|0)(?:[ .-]?\\d){9,10}(?![0-9A-Za-z])");
    private static final Pattern OTP = Pattern.compile("(?<![0-9A-Za-z.,-])\\d{6,8}(?![0-9A-Za-z.,-])");
    private static final Pattern MONEY = Pattern.compile("-?\\d{1,17}\\.\\d{2}");
    private static final Pattern ISO_TEMPORAL = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:\\d{2})?)?");
    private static final Pattern ROLE = Pattern.compile("[A-Za-z0-9_]{1,32}");
    private static final Pattern ENTITY_TYPE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final Pattern TRACE_ID = Pattern.compile("[\\x21-\\x7E]{1,64}");

    private final ObjectMapper json;
    private final JcsCanonicalizer canonicalizer;

    public AuditEventValidator(ObjectMapper json, JcsCanonicalizer canonicalizer) {
        this.json = json;
        this.canonicalizer = canonicalizer;
    }

    /**
     * Validates the event and returns its normalised form with the canonical payload.
     *
     * @throws BankingBusinessException with {@link ErrorCode#AUDIT_WRITE_FAILED} for every rule violation
     */
    public ValidatedAuditEvent validate(AuditEvent event) {
        try {
            return doValidate(event);
        } catch (BankingBusinessException rejection) {
            throw rejection;
        } catch (RuntimeException invalid) {
            // Never leak parser/validator messages: they can contain consumer payload fragments.
            throw rejection();
        }
    }

    private ValidatedAuditEvent doValidate(AuditEvent event) {
        if (event == null) {
            throw rejection();
        }
        AuditAction action = event.action();
        AuditActorType actorType = event.actorType();
        if (actorType == AuditActorType.USER) {
            if (event.actorUserId() == null || event.actorUserId() <= 0) {
                throw rejection();
            }
        } else if (event.actorUserId() != null) {
            throw rejection();
        }
        String actorRole = required(event.actorRole(), MAX_ACTOR_ROLE);
        if (!ROLE.matcher(actorRole).matches()) {
            throw rejection();
        }
        String entityType = required(event.entityType(), MAX_ENTITY_TYPE);
        if (!ENTITY_TYPE_PATTERN.matcher(entityType).matches() || !ENTITY_TYPES.contains(entityType)) {
            throw rejection();
        }
        String entityId = required(event.entityId(), MAX_ENTITY_ID);
        Map<String, Object> beforeState = validateState(entityType, event.beforeState());
        Map<String, Object> afterState = validateState(entityType, event.afterState());
        if (action == AuditAction.FRAUD_FLAG_CREATED
                && (actorType != AuditActorType.SYSTEM || !"FRAUD_FLAG".equals(entityType)
                || event.outcome() != AuditOutcome.SUCCESS
                || !entityId.matches("[1-9][0-9]{0,18}") || afterState == null
                || !(afterState.get("ruleVersion") instanceof Integer version) || version <= 0
                || !(afterState.get("ruleCode") instanceof String code) || !code.matches("[A-Za-z][A-Za-z0-9_:-]{0,31}")
                || !Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(afterState.getOrDefault("severity", ""))
                || !"OPEN".equals(afterState.get("status"))
                || !(afterState.get("transferReference") instanceof String reference)
                || !reference.matches("TXN[A-Za-z0-9]{8,29}"))) {
            throw rejection();
        }
        Map<String, Object> clientInfo = validateClientInfo(event.clientInfoMasked());
        BigDecimal amount = validatedAmount(event.amount());
        String currency = event.currency();
        if (amount == null) {
            if (currency != null) {
                throw rejection();
            }
        } else if (!"VND".equals(currency)) {
            throw rejection();
        }
        if (event.outcome() == null) {
            throw rejection();
        }
        String correlationId = required(event.correlationId(), MAX_TRACE_ID);
        if (!TRACE_ID.matcher(correlationId).matches()) {
            throw rejection();
        }
        String requestId = optionalTrace(event.requestId());
        Instant occurredAt = AuditChain.normalizeOccurredAt(event.occurredAt());

        String canonicalJson = canonicalJson(event, actorRole, entityType, entityId, beforeState, afterState,
                amount, currency, correlationId, requestId, clientInfo, occurredAt);
        return new ValidatedAuditEvent(action, actorType, event.actorUserId(), actorRole, entityType, entityId,
                beforeState, afterState, amount, currency, event.outcome(), correlationId, requestId, clientInfo,
                occurredAt, AuditChain.HASH_VERSION, canonicalJson);
    }

    private String canonicalJson(AuditEvent event, String actorRole, String entityType, String entityId,
            Map<String, Object> beforeState, Map<String, Object> afterState, BigDecimal amount, String currency,
            String correlationId, String requestId, Map<String, Object> clientInfo, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("occurred_at", AuditChain.formatOccurredAt(occurredAt));
        payload.put("actor_type", event.actorType().name());
        payload.put("actor_user_id", event.actorUserId() == null ? null : Long.toString(event.actorUserId()));
        payload.put("actor_role", actorRole);
        payload.put("action", event.action().name());
        payload.put("entity_type", entityType);
        payload.put("entity_id", entityId);
        payload.put("before_state", beforeState);
        payload.put("after_state", afterState);
        payload.put("amount", amount == null ? null : AuditChain.formatAmount(amount));
        payload.put("currency", currency);
        payload.put("correlation_id", correlationId);
        payload.put("request_id", requestId);
        payload.put("outcome", event.outcome().name());
        payload.put("client_info_masked", clientInfo);
        payload.put("hash_version", AuditChain.HASH_VERSION);
        try {
            byte[] canonical = canonicalizer.canonicalize(json.writeValueAsString(payload));
            if (canonical.length > MAX_CANONICAL_BYTES) {
                throw rejection();
            }
            return new String(canonical, StandardCharsets.UTF_8);
        } catch (JsonProcessingException invalid) {
            throw rejection();
        } catch (IllegalArgumentException invalid) {
            throw rejection();
        }
    }

    private static Map<String, Object> validateState(String entityType, Map<String, Object> state) {
        if (state == null) {
            return null;
        }
        Set<String> allowed = STATE_FIELDS.getOrDefault(entityType, Set.of());
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || !(allowed.contains(key)
                    || APPROVAL_AND_LIMIT_FIELDS.contains(key))) {
                throw rejection();
            }
            Object value = entry.getValue();
            if (INTEGER_ONLY_FIELDS.contains(key)) {
                if (!(value instanceof Integer || value instanceof Long || value instanceof Short
                        || value instanceof Byte || value instanceof java.math.BigInteger)) {
                    throw rejection();
                }
            } else if (value instanceof String text) {
                validateText(key, text);
            } else if (value instanceof Integer || value instanceof Long || value instanceof Short
                    || value instanceof Byte || value instanceof Boolean
                    || value instanceof java.math.BigInteger) {
                if (STRING_ONLY_FIELDS.contains(key)) {
                    throw rejection();
                }
            } else {
                // Floats, decimals, nested maps/lists/entities are never part of an audit state.
                throw rejection();
            }
            copy.put(key, value);
        }
        return Map.copyOf(copy);
    }

    private static Map<String, Object> validateClientInfo(Map<String, Object> clientInfo) {
        if (clientInfo == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : clientInfo.entrySet()) {
            String key = entry.getKey();
            if (key == null || !CLIENT_INFO_FIELDS.contains(key) || !(entry.getValue() instanceof String text)) {
                throw rejection();
            }
            if (text.length() > MAX_CLIENT_INFO_TEXT) {
                throw rejection();
            }
            copy.put(key, text);
        }
        return Map.copyOf(copy);
    }

    private static void validateText(String key, String text) {
        if (text.length() > MAX_STATE_TEXT) {
            throw rejection();
        }
        if (MONEY.matcher(text).matches() || ISO_TEMPORAL.matcher(text).matches()) {
            return;
        }
        if (EMAIL.matcher(text).find() || BEARER.matcher(text).find() || JWT.matcher(text).find()
                || SECRET_ASSIGNMENT.matcher(text).find()) {
            throw rejection();
        }
        if (NUMERIC_SCAN_EXEMPT_FIELDS.contains(key)) {
            return;
        }
        if (ACCOUNT_NUMBER.matcher(text).find() || PHONE.matcher(text).find() || OTP.matcher(text).find()) {
            throw rejection();
        }
    }

    private static BigDecimal validatedAmount(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        try {
            String text = AuditChain.formatAmount(amount);
            return new BigDecimal(text);
        } catch (ArithmeticException | IllegalArgumentException invalid) {
            throw rejection();
        }
    }

    private static String required(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw rejection();
        }
        return value;
    }

    private static String optionalTrace(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > MAX_TRACE_ID || !TRACE_ID.matcher(value).matches()) {
            throw rejection();
        }
        return value;
    }

    private static BankingBusinessException rejection() {
        return new BankingBusinessException(ErrorCode.AUDIT_WRITE_FAILED, "Audit event rejected");
    }
}
