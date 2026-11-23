package com.simulatedbank.banking.audit.adapter.out;

import com.simulatedbank.banking.audit.application.AuditRecorder;
import com.simulatedbank.banking.audit.application.port.AuditQueryPort;
import com.simulatedbank.banking.audit.domain.AuditEvent;
import com.simulatedbank.banking.audit.domain.AuditSearchQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class AuditQueryJdbcStoreIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("audit/init-roles.sql");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", () -> "banking_app");
        r.add("spring.datasource.password", () -> "test_app");
        r.add("spring.flyway.url", postgres::getJdbcUrl);
        r.add("spring.flyway.user", () -> "banking_migrator");
        r.add("spring.flyway.password", () -> "test_migrator");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }
    @Autowired AuditRecorder recorder;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.simulatedbank.banking.audit.application.AuditQueryService queryService;
    @Autowired AuditQueryPort queryPort;

    @org.junit.jupiter.api.BeforeEach
    void seedActor() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
             var statement = connection.createStatement()) {
            statement.execute("INSERT INTO banking.app_user(user_id,username,password_hash,user_type) "
                    + "OVERRIDING SYSTEM VALUE VALUES "
                    + "(7,'audit-test-actor-7','test-only','CUSTOMER'),"
                    + "(100,'audit-test-actor-100','test-only','CUSTOMER'),"
                    + "(200,'audit-test-actor-200','test-only','CUSTOMER'),"
                    + "(201,'audit-test-actor-201','test-only','CUSTOMER') "
                    + "ON CONFLICT (user_id) DO NOTHING");
        }
    }

    @Test
    void auditQueryServiceAndPortAreBoundInSpringContext() {
        assertThat(queryService).isNotNull();
        assertThat(queryPort).isNotNull();
        assertThat(queryPort).isInstanceOf(AuditQueryJdbcStore.class);
    }

    @Test void searchOrdersTiesByIdAndDoesNotWriteAudit() {
        Instant time = Instant.parse("2026-11-03T12:00:00Z");
        var first = tx.execute(s -> recorder.recordInTransaction(event("TXN20261103QUERYA"), time));
        var second = tx.execute(s -> recorder.recordInTransaction(event("TXN20261103QUERYB"), time));
        long count = jdbc.queryForObject("SELECT count(*) FROM banking.audit_log", Long.class);
        var store = new AuditQueryJdbcStore(jdbc);
        var query = AuditSearchQuery.create(time.minusSeconds(1), time.plusSeconds(1), 0, 20, null, time);
        var page = store.search(query);
        assertThat(page.items()).extracting(AuditQueryPort.AuditRecordSummary::auditId).containsExactly(Long.toString(second.auditId()), Long.toString(first.auditId()));
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.audit_log", Long.class)).isEqualTo(count);
    }
    @Test void filtersReferenceAndCorrelationTogether() {
        Instant time = Instant.parse("2026-10-15T12:00:00Z");
        tx.executeWithoutResult(s -> recorder.recordInTransaction(event("TXN20261015FILTERA"), time));
        tx.executeWithoutResult(s -> recorder.recordInTransaction(event("TXN20261015FILTERB"), time));
        var query = AuditSearchQuery.create(time.minusSeconds(1), time.plusSeconds(1), 0, 20, null, time);
        var page = new AuditQueryJdbcStore(jdbc).search(query, "TXN20261015FILTERA", "corr-1");
        assertThat(page.items()).extracting(AuditQueryPort.AuditRecordSummary::entityId).containsExactly("TXN20261015FILTERA");
        assertThat(new AuditQueryJdbcStore(jdbc).search(query, "TXN20261015FILTERA", "other").items()).isEmpty();
    }
    @Test void allTypedFiltersRestrictResultsAndConflictingEntityFiltersReturnEmpty() {
        Instant time = Instant.parse("2026-09-15T12:00:00Z");
        tx.executeWithoutResult(s -> recorder.recordInTransaction(event("TXN20260915FILTERA"), time));
        var q = AuditSearchQuery.create(time.minusSeconds(1), time.plusSeconds(1), 0, 20, null, time,
                "corr-1", "TXN20260915FILTERA", 7L, "USER", "TRANSFER_CREATED", "TRANSFER", "TXN20260915FILTERA", "SUCCESS");
        var store = new AuditQueryJdbcStore(jdbc);
        assertThat(store.search(q).items()).hasSize(1);
        var conflict = AuditSearchQuery.create(time.minusSeconds(1), time.plusSeconds(1), 0, 20, null, time,
                "corr-1", "TXN20260915FILTERA", 7L, "USER", "TRANSFER_CREATED", "ACCOUNT", "TXN20260915FILTERA", "SUCCESS");
        assertThat(store.search(conflict).items()).isEmpty();
    }
    @Test void verifiesPersistedChainAndDetectsColumnTampering() throws Exception {
        var verifier = new AuditChainJdbcVerifier(jdbc);
        var before = verifier.verify();
        assertThat(before.status()).isIn(com.simulatedbank.banking.audit.verification.AuditChainVerifier.Status.PASS, com.simulatedbank.banking.audit.verification.AuditChainVerifier.Status.EMPTY);
        var id = tx.execute(s -> recorder.recordInTransaction(event("TXN20261103VERIFY"), Instant.parse("2026-11-03T14:00:00Z"))).auditId();
        assertThat(verifier.verify().status()).isEqualTo(com.simulatedbank.banking.audit.verification.AuditChainVerifier.Status.PASS);
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator"); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE banking.audit_log DISABLE TRIGGER trg_audit_log_no_update_delete");
            try {
                statement.executeUpdate("UPDATE banking.audit_log SET amount = 99 WHERE audit_id = " + id);
                assertThat(verifier.verify().status()).isEqualTo(com.simulatedbank.banking.audit.verification.AuditChainVerifier.Status.MISMATCH);
            } finally {
                statement.executeUpdate("UPDATE banking.audit_log SET amount = 12 WHERE audit_id = " + id);
                statement.execute("ALTER TABLE banking.audit_log ENABLE TRIGGER trg_audit_log_no_update_delete");
            }
        }
    }
    @Test void detailProjectionReturnsRequestAndStatesWithoutChangingAuditCount() {
        Instant time = Instant.parse("2026-11-04T12:00:00Z");
        var created = tx.execute(s -> recorder.recordInTransaction(event("TXN20261104DETAIL"), time));
        long count = jdbc.queryForObject("SELECT count(*) FROM banking.audit_log", Long.class);
        var store = new AuditQueryJdbcStore(jdbc);
        var detail = store.findDetail(created.auditId());
        assertThat(detail).isPresent();
        assertThat(detail.orElseThrow().summary().auditId()).isEqualTo(Long.toString(created.auditId()));
        assertThat(detail.orElseThrow().requestId()).isEqualTo("req-1");
        assertThat(detail.orElseThrow().beforeStateJson()).isNull();
        assertThat(detail.orElseThrow().afterStateJson()).contains("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.audit_log", Long.class)).isEqualTo(count);

        var service = new com.simulatedbank.banking.audit.application.AuditQueryService(store);
        var view = service.getDetail(created.auditId());
        assertThat(view.beforeState()).isNull();
        assertThat(view.afterState()).containsEntry("status", "PENDING");
    }
    @Test void deterministicAsOfCanBeSuppliedViaClockOrParameter() {
        Instant fixed = Instant.parse("2026-11-14T15:30:00Z");
        var store = new AuditQueryJdbcStore(jdbc, Clock.fixed(fixed, ZoneOffset.UTC));
        Instant time = Instant.parse("2026-11-03T12:00:00Z");
        var query = AuditSearchQuery.create(time.minusSeconds(1), time.plusSeconds(1), 0, 20, null, time);
        var page = store.search(query);
        assertThat(page.asOf()).isEqualTo(fixed);

        Instant explicit = Instant.parse("2026-11-14T16:00:00Z");
        var pageExplicit = store.search(query, explicit);
        assertThat(pageExplicit.asOf()).isEqualTo(explicit);
    }
    @Test void findDetailReturnsEmptyForNonExistentOrNegativeAuditId() {
        var store = new AuditQueryJdbcStore(jdbc);
        assertThat(store.findDetail(-1)).isEmpty();
        assertThat(store.findDetail(0)).isEmpty();
        assertThat(store.findDetail(999999999L)).isEmpty();
    }
    @Test void verifiesChainAcrossRollbackSequenceGaps() {
        var verifier = new AuditChainJdbcVerifier(jdbc);
        // Successful row before gap
        tx.executeWithoutResult(s -> recorder.recordInTransaction(event("TXN20261105GAPPRE"), Instant.parse("2026-11-05T10:00:00Z")));

        // Transaction that rolls back and burns a sequence ID
        try {
            tx.executeWithoutResult(s -> {
                recorder.recordInTransaction(event("TXN20261105GAPBURN"), Instant.parse("2026-11-05T10:01:00Z"));
                throw new RuntimeException("Simulated transaction rollback to burn sequence ID");
            });
        } catch (RuntimeException ignored) { }

        // Next successful transaction picks up the previous committed record_hash
        tx.executeWithoutResult(s -> recorder.recordInTransaction(event("TXN20261105GAPPOST"), Instant.parse("2026-11-05T10:02:00Z")));

        // Sequence ID gap from rollback is valid as long as prev_hash chain remains intact
        assertThat(verifier.verify().status()).isEqualTo(com.simulatedbank.banking.audit.verification.AuditChainVerifier.Status.PASS);
    }
    @Test void verifiesChainWithNonMonotonicOccurredAt() {
        var verifier = new AuditChainJdbcVerifier(jdbc);
        // Row 1 recorded with later time
        tx.executeWithoutResult(s -> recorder.recordInTransaction(event("TXN20261106TIME1"), Instant.parse("2026-11-06T15:00:00Z")));
        // Row 2 recorded with earlier (backdated) time
        tx.executeWithoutResult(s -> recorder.recordInTransaction(event("TXN20261106TIME2"), Instant.parse("2026-11-06T12:00:00Z")));

        // Chain is ordered by audit_id ASC, not occurred_at
        assertThat(verifier.verify().status()).isEqualTo(com.simulatedbank.banking.audit.verification.AuditChainVerifier.Status.PASS);
    }
    @Test void verifiesChainAcrossBatchBoundariesWithSmallBatchSize() {
        // Run verifier with batch size 2 across all existing rows
        var verifier = new AuditChainJdbcVerifier(jdbc, 2);
        assertThat(verifier.verify().status()).isEqualTo(com.simulatedbank.banking.audit.verification.AuditChainVerifier.Status.PASS);
    }
    @Test void batchSizeBoundsEnforced() {
        assertThatThrownBy(() -> new AuditChainJdbcVerifier(jdbc, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditChainJdbcVerifier(jdbc, 1001))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void detectsMissingMiddleRowWhenRowIsDeleted() throws Exception {
        var verifier = new AuditChainJdbcVerifier(jdbc);
        var idA = tx.execute(s -> recorder.recordInTransaction(event("TXN20261107DELA"), Instant.parse("2026-11-07T10:00:00Z"))).auditId();
        var idB = tx.execute(s -> recorder.recordInTransaction(event("TXN20261107DELB"), Instant.parse("2026-11-07T10:01:00Z"))).auditId();
        var idC = tx.execute(s -> recorder.recordInTransaction(event("TXN20261107DELC"), Instant.parse("2026-11-07T10:02:00Z"))).auditId();

        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator"); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE banking.audit_log DISABLE TRIGGER trg_audit_log_no_update_delete");
            try {
                // Delete middle row idB
                statement.executeUpdate("DELETE FROM banking.audit_log WHERE audit_id = " + idB);
                // Verifier must detect missing row as MISMATCH because idC's prev_hash points to deleted idB
                assertThat(verifier.verify().status()).isEqualTo(com.simulatedbank.banking.audit.verification.AuditChainVerifier.Status.MISMATCH);
            } finally {
                // Clean up idC so remaining chain is restored
                statement.executeUpdate("DELETE FROM banking.audit_log WHERE audit_id = " + idC);
                statement.execute("ALTER TABLE banking.audit_log ENABLE TRIGGER trg_audit_log_no_update_delete");
            }
        }
        // Restored chain must pass again
        assertThat(verifier.verify().status()).isEqualTo(com.simulatedbank.banking.audit.verification.AuditChainVerifier.Status.PASS);
    }

    @Test void roundTripAppUserRoleCodesUpTo512ElementsIsPreservedAndProjectable() {
        Instant time = Instant.parse("2026-11-08T12:00:00Z");
        var roles = java.util.stream.IntStream.range(0, 512)
                .mapToObj(i -> "R" + i)
                .toList();
        var clientInfo = Map.<String, Object>of("ipMasked", "192.168.1.***", "userAgent", "Mozilla/5.0");
        var event = new AuditEvent(
                "AUTH_LOGIN_SUCCESS",
                "USER",
                100L,
                "CUSTOMER",
                "APP_USER",
                "100",
                null,
                Map.of("reasonCode", "SUCCESS", "roleCodes", roles),
                null,
                null,
                "corr-roundtrip-roles",
                "req-roundtrip-roles",
                "SUCCESS",
                clientInfo
        );

        var ref = tx.execute(s -> recorder.recordInTransaction(event, time));
        assertThat(ref).isNotNull();

        var store = new AuditQueryJdbcStore(jdbc);
        var service = new com.simulatedbank.banking.audit.application.AuditQueryService(store);
        var detailView = service.getDetail(ref.auditId());

        assertThat(detailView).isNotNull();
        assertThat(detailView.entity().type()).isEqualTo("APP_USER");
        assertThat(detailView.entity().id()).isEqualTo("100");
        assertThat(detailView.afterState()).isNotNull();
        assertThat(detailView.afterState().get("roleCodes")).isInstanceOf(java.util.List.class);
        var retrievedRoles = (java.util.List<?>) detailView.afterState().get("roleCodes");
        assertThat(retrievedRoles).hasSize(512).isEqualTo(roles);
    }

    @Test void roundTripSnapshotNear32KiBBoundIsPreservedAndProjectable() {
        Instant time = Instant.parse("2026-11-09T14:00:00Z");
        // 500 roles of length 58 chars gives ~30.5 KiB snapshot (near 32 KiB limit)
        var roles = java.util.stream.IntStream.range(0, 500)
                .mapToObj(i -> "ROLE_" + String.format("%03d", i) + "_" + "A".repeat(48))
                .toList();
        var clientInfo = Map.<String, Object>of("ipMasked", "10.0.0.***", "userAgent", "Mozilla/5.0");
        var event = new AuditEvent(
                "AUTH_LOGIN_SUCCESS",
                "USER",
                200L,
                "CUSTOMER",
                "APP_USER",
                "200",
                null,
                Map.of("reasonCode", "SUCCESS", "roleCodes", roles),
                null,
                null,
                "corr-roundtrip-32k",
                "req-roundtrip-32k",
                "SUCCESS",
                clientInfo
        );

        var ref = tx.execute(s -> recorder.recordInTransaction(event, time));
        assertThat(ref).isNotNull();

        var detailView = queryService.getDetail(ref.auditId());
        assertThat(detailView).isNotNull();
        assertThat(detailView.entity().type()).isEqualTo("APP_USER");
        assertThat(detailView.entity().id()).isEqualTo("200");
        assertThat(detailView.afterState()).isNotNull();
        var retrievedRoles = (java.util.List<?>) detailView.afterState().get("roleCodes");
        assertThat(retrievedRoles).hasSize(500).isEqualTo(roles);
    }

    @Test void readsWriterSnapshotDespiteJsonbWhitespaceExpansion() {
        Instant time = Instant.parse("2026-11-09T14:00:00Z");
        var roles = java.util.Collections.nCopies(512, "R" + "A".repeat(59));
        var event = new AuditEvent(
                "AUTH_LOGIN_SUCCESS", "USER", 201L, "CUSTOMER", "APP_USER", "201",
                null, Map.of("reasonCode", "SUCCESS", "roleCodes", roles),
                null, null, "corr-roundtrip-jsonb-limit", "req-roundtrip-jsonb-limit", "SUCCESS",
                Map.of("ipMasked", "10.0.0.***", "userAgent", "Mozilla/5.0"));

        var ref = tx.execute(s -> recorder.recordInTransaction(event, time));
        assertThat(ref).isNotNull();

        var detailView = queryService.getDetail(ref.auditId());
        assertThat(detailView.afterState()).isNotNull();
        assertThat((java.util.List<?>) detailView.afterState().get("roleCodes"))
                .hasSize(512).isEqualTo(roles);
    }

    private static AuditEvent event(String ref) {
        return new AuditEvent("TRANSFER_CREATED", "USER", 7L, "CUSTOMER", "TRANSFER", ref, null, Map.of("status", "PENDING"), new BigDecimal("12.00"), "VND", "corr-1", "req-1", "SUCCESS", null);
    }
}
