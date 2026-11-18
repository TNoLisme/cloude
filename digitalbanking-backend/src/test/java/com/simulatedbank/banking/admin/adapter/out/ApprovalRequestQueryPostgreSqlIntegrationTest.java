package com.simulatedbank.banking.admin.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.admin.application.port.ApprovalRequestReader;
import com.simulatedbank.banking.admin.application.query.ApprovalCriteria;
import com.simulatedbank.banking.admin.application.query.ApprovalDetailProjection;
import com.simulatedbank.banking.admin.application.query.ApprovalListQuery;
import com.simulatedbank.banking.admin.application.query.ApprovalPage;
import com.simulatedbank.banking.admin.application.query.ApprovalQueryService;
import com.simulatedbank.banking.admin.application.query.ApprovalSummaryProjection;
import com.simulatedbank.banking.admin.application.query.ApprovalVisibility;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import com.simulatedbank.banking.admin.domain.ApprovalTargetType;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** BE-ADM-DB-02: real PostgreSQL/Flyway evidence for approval list/detail projections. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ApprovalRequestQueryPostgreSqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final Set<ApprovalRequestType> ALL_TYPES =
            Set.of(ApprovalRequestType.ROLE_CHANGE, ApprovalRequestType.LIMIT_CHANGE);

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("admin_approval_query");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReadTransactionProbeConfig {

        @Bean
        TransactionProbe transactionProbe() {
            return new TransactionProbe();
        }

        @Bean
        @Primary
        ApprovalRequestReader probingReader(JpaApprovalRequestReader delegate, TransactionProbe probe) {
            return new ApprovalRequestReader() {
                @Override
                public ApprovalPage findPage(ApprovalCriteria criteria) {
                    probe.record();
                    return delegate.findPage(criteria);
                }

                @Override
                public Optional<ApprovalDetailProjection> findDetail(long approvalId,
                        ApprovalVisibility visibility) {
                    probe.record();
                    return delegate.findDetail(approvalId, visibility);
                }
            };
        }
    }

    static final class TransactionProbe {
        private boolean active;
        private boolean readOnly;

        void record() {
            active = TransactionSynchronizationManager.isActualTransactionActive();
            readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        }

        void reset() {
            active = false;
            readOnly = false;
        }

        boolean active() {
            return active;
        }

        boolean readOnly() {
            return readOnly;
        }
    }

    @Autowired private ApprovalQueryService queries;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TransactionProbe probe;

    private TransactionTemplate tx;
    private long makerA;
    private long makerB;
    private long checkerA;
    private long checkerB;
    private long target;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM banking.admin_idempotency_record");
            jdbc.update("DELETE FROM banking.admin_approval_attempt");
            jdbc.update("DELETE FROM banking.approval_request");
            makerA = user("adm-q-maker-a-" + suffix);
            makerB = user("adm-q-maker-b-" + suffix);
            checkerA = user("adm-q-checker-a-" + suffix);
            checkerB = user("adm-q-checker-b-" + suffix);
            target = user("adm-q-target-" + suffix);
        });
        probe.reset();
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM banking.admin_idempotency_record");
            jdbc.update("DELETE FROM banking.admin_approval_attempt");
            jdbc.update("DELETE FROM banking.approval_request");
        });
    }

    @Test
    void documentedFiltersAndHalfOpenWindowSelectExpectedRows() {
        long limitPending = insertLimit(makerA, "PENDING", null, null, NOW.minus(Duration.ofMinutes(10)), null, null);
        long rolePendingB = insertRole(makerB, "PENDING", null, null, NOW.minus(Duration.ofMinutes(5)), null, null);
        long roleRejected = insertRole(makerA, "REJECTED", checkerA, "out of policy", NOW.minus(Duration.ofMinutes(1)),
                NOW.minusSeconds(30), null);
        long roleApplied = insertRole(makerA, "APPLIED", checkerB, "approved", NOW.minus(Duration.ofMinutes(2)),
                NOW.minus(Duration.ofMinutes(1)), NOW.minus(Duration.ofMinutes(1)));
        insertLimit(makerA, "PENDING", null, null, NOW.plusSeconds(60), null, null);

        assertPageIds(list(null, null, null, null, NOW.minus(Duration.ofHours(1)), NOW, 0, 50, "createdAt,asc"),
                limitPending, rolePendingB, roleApplied, roleRejected);
        assertPageIds(list(ApprovalRequestType.ROLE_CHANGE, null, null, null, NOW.minus(Duration.ofHours(1)), NOW,
                0, 50, "createdAt,asc"), rolePendingB, roleApplied, roleRejected);
        assertPageIds(list(null, ApprovalStatus.PENDING, null, null, NOW.minus(Duration.ofHours(1)), NOW, 0, 50,
                "createdAt,asc"), limitPending, rolePendingB);
        assertPageIds(list(null, null, makerB, null, NOW.minus(Duration.ofHours(1)), NOW, 0, 50, "createdAt,asc"),
                rolePendingB);
        assertPageIds(list(null, null, null, checkerB, NOW.minus(Duration.ofHours(1)), NOW, 0, 50, "createdAt,asc"),
                roleApplied);

        // to is exclusive: the row created exactly at `to` is not returned.
        assertPageIds(list(null, null, null, null, NOW.minus(Duration.ofHours(1)), createdAt(roleApplied),
                0, 50, "createdAt,asc"), limitPending, rolePendingB);
        // from is inclusive: the row created exactly at `from` is returned.
        assertPageIds(list(null, null, null, null, createdAt(roleApplied), NOW, 0, 50, "createdAt,asc"),
                roleApplied, roleRejected);
    }

    @Test
    void paginationUsesStableCreatedAtThenApprovalIdOrderingAndEmptyPastLastPage() {
        long first = insertLimit(makerA, "PENDING", null, null, NOW.minus(Duration.ofMinutes(10)), null, null);
        long second = insertLimit(makerA, "PENDING", null, null, NOW.minus(Duration.ofMinutes(10)), null, null);
        long third = insertLimit(makerA, "PENDING", null, null, NOW.minus(Duration.ofMinutes(10)), null, null);
        long fourth = insertLimit(makerA, "PENDING", null, null, NOW.minus(Duration.ofMinutes(10)), null, null);
        long fifth = insertLimit(makerA, "PENDING", null, null, NOW.minus(Duration.ofMinutes(10)), null, null);

        ApprovalPage page0 = list(null, null, null, null, NOW.minus(Duration.ofHours(1)), NOW, 0, 2,
                "createdAt,desc");
        assertPageIds(page0, fifth, fourth);
        assertThat(page0.number()).isZero();
        assertThat(page0.size()).isEqualTo(2);
        assertThat(page0.totalElements()).isEqualTo(5);
        assertThat(page0.totalPages()).isEqualTo(3);

        assertPageIds(list(null, null, null, null, NOW.minus(Duration.ofHours(1)), NOW, 1, 2, "createdAt,desc"),
                third, second);
        assertPageIds(list(null, null, null, null, NOW.minus(Duration.ofHours(1)), NOW, 2, 2, "createdAt,desc"),
                first);

        ApprovalPage beyond = list(null, null, null, null, NOW.minus(Duration.ofHours(1)), NOW, 3, 2,
                "createdAt,desc");
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.totalElements()).isEqualTo(5);
        assertThat(beyond.totalPages()).isEqualTo(3);
    }

    @Test
    void emptyResultSetReturnsZeroedPageMetadata() {
        ApprovalPage page = list(ApprovalRequestType.ROLE_CHANGE, ApprovalStatus.APPLIED, null, null,
                NOW.minus(Duration.ofHours(1)), NOW, 0, 20, null);
        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.number()).isZero();
        assertThat(page.size()).isEqualTo(20);
    }

    @Test
    void listDoesNotReadOrDecodeJsonbPayload() {
        long id = insertRow("LIMIT_CHANGE", "CUSTOMER", "2001", "{\"unexpected\":\"shape\"}", "PENDING",
                makerA, null, null, "adm-q-bad-payload", NOW.minus(Duration.ofMinutes(1)), null, null);

        assertPageIds(list(null, null, null, null, NOW.minus(Duration.ofHours(1)), NOW, 0, 20, null), id);
        assertThatThrownBy(() -> queries.findDetail(id, reviewer(makerA))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void detailReturnsApprovedSummaryAndMaskedTypedPayload() {
        long id = insertRow("ROLE_CHANGE", "APP_USER", Long.toString(target), rolePayload("ASSIGN_ROLE",
                        target, "OPERATIONS", null, 3L, "promotion"), "PENDING", makerA, null, null,
                "adm-q-detail", NOW.minus(Duration.ofMinutes(2)), null, null);

        ApprovalDetailProjection detail = queries.findDetail(id, reviewer(makerA)).orElseThrow();
        ApprovalSummaryProjection summary = detail.summary();
        assertThat(summary.approvalId()).isEqualTo(id);
        assertThat(summary.requestType()).isEqualTo(ApprovalRequestType.ROLE_CHANGE);
        assertThat(summary.targetType()).isEqualTo(ApprovalTargetType.APP_USER);
        assertThat(summary.targetId()).isEqualTo(Long.toString(target));
        assertThat(summary.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(summary.makerId()).isEqualTo(makerA);
        assertThat(summary.checkerId()).isNull();
        assertThat(summary.createdAt()).isEqualTo(NOW.minus(Duration.ofMinutes(2)));
        assertThat(summary.decidedAt()).isNull();
        assertThat(summary.appliedAt()).isNull();
        assertThat(summary.decisionNote()).isNull();
        assertThat(summary.correlationId()).isEqualTo("adm-q-detail");

        assertThat(detail.payload())
                .containsEntry("operation", "ASSIGN_ROLE")
                .containsEntry("targetUserId", Long.toString(target))
                .containsEntry("roleCode", "OPERATIONS")
                .containsEntry("reason", "promotion")
                .containsEntry("privilegeCode", null)
                .doesNotContainKey("expectedVersion");
    }

    @Test
    void detailIncludesDecisionFactsForDecidedApproval() {
        long id = insertRole(makerA, "REJECTED", checkerA, "out of policy", NOW.minus(Duration.ofMinutes(5)),
                NOW.minus(Duration.ofMinutes(1)), null);

        ApprovalDetailProjection detail = queries.findDetail(id, reviewer(makerA)).orElseThrow();
        assertThat(detail.summary().status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(detail.summary().checkerId()).isEqualTo(checkerA);
        assertThat(detail.summary().decidedAt()).isEqualTo(NOW.minus(Duration.ofMinutes(1)));
        assertThat(detail.summary().appliedAt()).isNull();
        assertThat(detail.summary().decisionNote()).isEqualTo("out of policy");
    }

    @Test
    void listAndDetailRunInsideAnActiveReadOnlyTransaction() {
        long id = insertLimit(makerA, "PENDING", null, null, NOW.minus(Duration.ofMinutes(1)), null, null);

        queries.list(new ApprovalListQuery(null, null, null, null, NOW.minus(Duration.ofHours(1)), NOW,
                null, null, null), reviewer(makerA));
        assertThat(probe.active()).isTrue();
        assertThat(probe.readOnly()).isTrue();

        probe.reset();
        queries.findDetail(id, reviewer(makerA)).orElseThrow();
        assertThat(probe.active()).isTrue();
        assertThat(probe.readOnly()).isTrue();
    }

    @Test
    void invalidSortIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> list(null, null, null, null, NOW.minus(Duration.ofHours(1)), NOW, 0, 20,
                "status,desc")).isInstanceOfSatisfying(BankingBusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> queries.findDetail(0, reviewer(makerA))).isInstanceOfSatisfying(BankingBusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void actorScopeIsAppliedToRowsCountsAndDetailReads() {
        long roleByMakerB = insertRole(makerB, "PENDING", null, null, NOW.minus(Duration.ofMinutes(4)), null, null);
        long limitByMakerA = insertLimit(makerA, "PENDING", null, null, NOW.minus(Duration.ofMinutes(3)), null, null);
        long limitByMakerB = insertLimit(makerB, "PENDING", null, null, NOW.minus(Duration.ofMinutes(2)), null, null);
        long roleByMakerA = insertRole(makerA, "REJECTED", checkerA, "no", NOW.minus(Duration.ofMinutes(1)),
                NOW.minusSeconds(30), null);
        Instant from = NOW.minus(Duration.ofHours(1));
        ApprovalVisibility makerOnly = new ApprovalVisibility(makerA, Set.of());
        ApprovalVisibility roleReviewer = new ApprovalVisibility(makerA, Set.of(ApprovalRequestType.ROLE_CHANGE));

        ApprovalPage ownOnly = list(null, null, null, null, from, NOW, 0, 50, null, makerOnly);
        assertPageIds(ownOnly, roleByMakerA, limitByMakerA);
        assertThat(ownOnly.totalElements()).isEqualTo(2);

        // Reviewer scope: own requests of any type + every ROLE_CHANGE; another maker's LIMIT_CHANGE stays hidden.
        ApprovalPage reviewScope = list(null, null, null, null, from, NOW, 0, 50, null, roleReviewer);
        assertPageIds(reviewScope, roleByMakerA, limitByMakerA, roleByMakerB);
        assertThat(reviewScope.totalElements()).isEqualTo(3);

        // A client filter cannot widen the scope: maker-only actor asking for another maker sees nothing.
        ApprovalPage crossMakerFilter = list(null, null, makerB, null, from, NOW, 0, 50, null, makerOnly);
        assertThat(crossMakerFilter.items()).isEmpty();
        assertThat(crossMakerFilter.totalElements()).isZero();

        // Type filter inside the scope: a ROLE_CHANGE-only reviewer sees only their own LIMIT_CHANGE rows.
        ApprovalPage limitFilter = list(ApprovalRequestType.LIMIT_CHANGE, null, null, null, from, NOW, 0, 50,
                null, roleReviewer);
        assertPageIds(limitFilter, limitByMakerA);
        assertThat(limitFilter.totalElements()).isEqualTo(1);

        // Detail: in scope for the reviewer, 404-equivalent (empty) for a foreign type and for unknown ids.
        assertThat(queries.findDetail(roleByMakerB, roleReviewer)).isPresent();
        assertThat(queries.findDetail(limitByMakerB, roleReviewer)).isEmpty();
        assertThat(queries.findDetail(roleByMakerB, makerOnly)).isEmpty();
        assertThat(queries.findDetail(9_000_000_001L, roleReviewer)).isEmpty();
    }

    private ApprovalPage list(ApprovalRequestType requestType, ApprovalStatus status, Long makerId, Long checkerId,
            Instant from, Instant to, Integer page, Integer size, String sort) {
        return list(requestType, status, makerId, checkerId, from, to, page, size, sort, reviewer(makerA));
    }

    private ApprovalPage list(ApprovalRequestType requestType, ApprovalStatus status, Long makerId, Long checkerId,
            Instant from, Instant to, Integer page, Integer size, String sort, ApprovalVisibility visibility) {
        return queries.list(new ApprovalListQuery(requestType, status, makerId, checkerId, from, to,
                page, size, sort), visibility);
    }

    private static ApprovalVisibility reviewer(long actorId) {
        return new ApprovalVisibility(actorId, ALL_TYPES);
    }

    private static void assertPageIds(ApprovalPage page, long... expected) {
        assertThat(page.items()).extracting(ApprovalSummaryProjection::approvalId)
                .containsExactly(java.util.Arrays.stream(expected).boxed().toArray(Long[]::new));
    }

    private long insertLimit(long makerId, String status, Long checkerId, String note, Instant createdAt,
            Instant decidedAt, Instant appliedAt) {
        return insertRow("LIMIT_CHANGE", "CUSTOMER", "2001", limitPayload(), status, makerId, checkerId, note,
                "adm-q-" + UUID.randomUUID(), createdAt, decidedAt, appliedAt);
    }

    private long insertRole(long makerId, String status, Long checkerId, String note, Instant createdAt,
            Instant decidedAt, Instant appliedAt) {
        return insertRow("ROLE_CHANGE", "APP_USER", Long.toString(target),
                rolePayload("ASSIGN_ROLE", target, "OPERATIONS", null, 3L, "role change"), status, makerId,
                checkerId, note, "adm-q-" + UUID.randomUUID(), createdAt, decidedAt, appliedAt);
    }

    private long insertRow(String requestType, String targetType, String targetId, String payload, String status,
            long makerId, Long checkerId, String note, String correlationId, Instant createdAt, Instant decidedAt,
            Instant appliedAt) {
        return tx.execute(statusHolder -> jdbc.queryForObject("""
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, status, maker_id, checker_id,
                     decision_note, correlation_id, created_at, decided_at, applied_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING approval_id
                """, Long.class, requestType, targetType, targetId, payload, status, makerId, checkerId, note,
                correlationId, timestamp(createdAt), timestamp(decidedAt), timestamp(appliedAt)));
    }

    private static java.sql.Timestamp timestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private Instant createdAt(long approvalId) {
        return jdbc.queryForObject("SELECT created_at FROM banking.approval_request WHERE approval_id=?",
                Instant.class, approvalId);
    }

    private static String rolePayload(String operation, long targetUserId, String roleCode, String privilegeCode,
            long expectedVersion, String reason) {
        return """
                {"operation":"%s","targetUserId":"%d","roleCode":%s,"privilegeCode":%s,
                 "expectedVersion":%d,"reason":"%s"}
                """.formatted(operation, targetUserId, jsonString(roleCode), jsonString(privilegeCode),
                expectedVersion, reason);
    }

    private static String limitPayload() {
        return """
                {"customerId":"2001","accountId":null,"perTransactionLimit":"5000000.00",
                 "dailyLimit":"20000000.00","currency":"VND","effectiveFrom":null,"reason":"limit"}
                """;
    }

    private static String jsonString(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private long user(String username) {
        return jdbc.queryForObject("""
                INSERT INTO banking.app_user (username, password_hash, user_type, status)
                VALUES (?, 'not-a-real-hash', 'STAFF', 'ACTIVE') RETURNING user_id
                """, Long.class, username);
    }
}
