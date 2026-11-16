package com.simulatedbank.banking.audit.domain;

import java.math.BigDecimal;
import java.util.Set;

/** Normalizes snapshot scalars before their immutable copy is hashed or persisted. */
final class AuditSnapshotValues {
    static final Set<String> MONEY = Set.of("amount", "availableBalance", "ledgerBalance", "perTransactionLimit", "dailyLimit");
    static final Set<String> IDS = Set.of("sourceAccountId", "destinationAccountId", "accountId", "customerId", "userId",
            "approvalId", "flagId", "holdId", "policyId", "configId", "challengeId", "sessionId", "deviceId",
            "targetId", "makerId", "checkerId", "entityId", "scopeId", "exportId", "checkpointId");
    static final Set<String> COUNTERS = Set.of("attemptCount", "rowCount", "recordCount", "ruleVersion", "count",
            "retentionDays", "archiveAfterDays", "purgeAfterDays");
    private static final BigDecimal MAX_MONEY = new BigDecimal("99999999999999999.99");

    private AuditSnapshotValues() { }

    static Object normalize(String key, Object value) {
        if (value == null) return null;
        if (IDS.contains(key)) {
            if (!(value instanceof String || value instanceof Integer || value instanceof Long)) {
                throw new IllegalArgumentException("Snapshot identifier must be text or an integer");
            }
            String id = value.toString();
            if (!id.matches("[A-Za-z0-9._:-]{1,64}")) throw new IllegalArgumentException("Invalid snapshot identifier");
            return id;
        }
        if (MONEY.contains(key)) {
            BigDecimal decimal;
            if (value instanceof BigDecimal number) decimal = number;
            else if (value instanceof String text && text.matches("[0-9]{1,17}(?:\\.[0-9]{1,2})?")) {
                decimal = new BigDecimal(text);
            } else throw new IllegalArgumentException("Money snapshot must be bounded decimal text");
            validateMoney(decimal);
            return decimal.setScale(2).toPlainString();
        }
        return value;
    }

    static void validateMoney(BigDecimal value) {
        if (value.signum() < 0 || value.scale() < 0 || value.scale() > 2 || value.compareTo(MAX_MONEY) > 0) {
            throw new IllegalArgumentException("Money must fit NUMERIC(19,2) without rounding");
        }
    }
}
