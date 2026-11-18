package com.simulatedbank.banking.audit.adapter.out;

import com.simulatedbank.banking.audit.domain.AuditSearchQuery;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class AuditQuerySqlTest {
    private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-14T00:00:00Z");

    @Test
    void buildsDescendingOrderWithIdTieBreakAndBoundedLimit() {
        var sql = AuditQuerySql.search(AuditSearchQuery.create(FROM, TO, 2, 20, "occurredAt,desc", TO));
        assertThat(sql).contains("ORDER BY occurred_at DESC, audit_id DESC")
                .contains("LIMIT :size OFFSET :offset")
                .doesNotContain("SELECT *");
    }

    @Test
    void buildsAscendingOrderWithoutAcceptingRawSortText() {
        var sql = AuditQuerySql.search(AuditSearchQuery.create(FROM, TO, 0, 20, "occurredAt,asc", TO));
        assertThat(sql).contains("ORDER BY occurred_at ASC, audit_id ASC");
        assertThatThrownBy(() -> AuditSearchQuery.create(FROM, TO, 0, 20, "occurredAt DESC, audit_id", TO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateHashReturnsExactValidLowercaseHashWithoutMutation() {
        String validHash = "0".repeat(64);
        assertThat(AuditQueryJdbcStore.validateHash(validHash, "prev_hash")).isEqualTo(validHash);
        assertThat(AuditQueryJdbcStore.validateHash(null, "prev_hash")).isNull();
    }

    @Test
    void validateHashRejectsAbnormalOrCorruptedHashWithoutSilentNormalization() {
        // Uppercase hex must fail closed, not silently be lowercased
        assertThatThrownBy(() -> AuditQueryJdbcStore.validateHash("A" + "0".repeat(63), "prev_hash"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prev_hash");

        // Non-hex character
        assertThatThrownBy(() -> AuditQueryJdbcStore.validateHash("g" + "0".repeat(63), "record_hash"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("record_hash");

        // Wrong length
        assertThatThrownBy(() -> AuditQueryJdbcStore.validateHash("0".repeat(63), "prev_hash"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AuditQueryJdbcStore.validateHash("0".repeat(65), "record_hash"))
                .isInstanceOf(IllegalStateException.class);
    }
}
