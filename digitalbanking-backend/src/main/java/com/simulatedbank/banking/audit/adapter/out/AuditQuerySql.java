package com.simulatedbank.banking.audit.adapter.out;

import com.simulatedbank.banking.audit.domain.AuditSearchQuery;

/** Builds the fixed projection and whitelisted order for audit searches. */
final class AuditQuerySql {
    private AuditQuerySql() { }

    static String search(AuditSearchQuery query) {
        return search(query, "occurred_at >= :from AND occurred_at < :to");
    }

    static String search(AuditSearchQuery query, String predicate) {
        String direction = query.ascending() ? "ASC" : "DESC";
        return "SELECT audit_id, occurred_at, recorded_at, actor_type, actor_user_id, actor_role, "
                + "action, entity_type, entity_id, amount, currency, correlation_id, outcome, "
                + "prev_hash, record_hash, hash_version FROM banking.audit_log WHERE " + predicate + " "
                + "ORDER BY occurred_at " + direction + ", audit_id " + direction + " LIMIT :size OFFSET :offset";
    }
}
