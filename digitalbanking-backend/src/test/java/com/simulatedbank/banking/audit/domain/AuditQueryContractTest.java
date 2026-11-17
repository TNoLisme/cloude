package com.simulatedbank.banking.audit.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class AuditQueryContractTest {
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    @Test
    void defaultsWindowAndPaginationFromOneRequestTime() {
        var query = AuditSearchQuery.create(null, null, null, null, null, NOW);
        assertThat(query.from()).isEqualTo(Instant.parse("2026-10-15T12:00:00Z"));
        assertThat(query.to()).isEqualTo(NOW);
        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(20);
        assertThat(query.ascending()).isFalse();
    }

    @Test
    void appliesEachMissingTimeBoundIndependently() {
        var query = AuditSearchQuery.create(Instant.parse("2026-11-01T00:00:00Z"), null, 1, 100, "occurredAt,asc", NOW);
        assertThat(query.to()).isEqualTo(NOW);
        assertThat(query.ascending()).isTrue();
        assertThat(query.offset()).isEqualTo(100);
    }

    @Test
    void rejectsInvalidBoundsSortAndPage() {
        assertThatThrownBy(() -> AuditSearchQuery.create(NOW, NOW, null, null, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditSearchQuery.create(NOW.minusSeconds(367L * 86400), NOW, null, null, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditSearchQuery.create(null, null, -1, 20, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditSearchQuery.create(null, null, 0, 101, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditSearchQuery.create(null, null, 0, 20, "occurredAt desc; DROP TABLE audit", NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownActorTypeAndOutcome() {
        assertThatThrownBy(() -> AuditSearchQuery.create(null, null, null, null, null, NOW,
                null, null, null, "ADMIN", null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditSearchQuery.create(null, null, null, null, null, NOW,
                null, null, null, null, null, null, null, "UNKNOWN")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void calculatesLargeOffsetWithoutIntegerOverflow() {
        var query = AuditSearchQuery.create(null, null, Integer.MAX_VALUE, 100, null, NOW);
        assertThat(query.offset()).isEqualTo(214748364700L);
    }

    @Test
    void auditRecordRefPreservesUncheckedConstructorForLegacyCompatibility() {
        // Must allow unvalidated constructor arguments to preserve exact pre-refactor record behavior
        assertThatCode(() -> new com.simulatedbank.banking.audit.application.port.AuditRecordRef(0L, null, (short) 0))
                .doesNotThrowAnyException();
        assertThatCode(() -> new com.simulatedbank.banking.audit.application.port.AuditRecordRef(-1L, "", (short) 1))
                .doesNotThrowAnyException();
    }
}
