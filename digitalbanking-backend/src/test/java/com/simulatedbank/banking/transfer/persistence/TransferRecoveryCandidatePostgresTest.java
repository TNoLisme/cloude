package com.simulatedbank.banking.transfer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferRecoveryStore;
import com.simulatedbank.banking.transfer.application.IdempotencyRetention;
import com.simulatedbank.banking.transfer.application.TransferRecoveryCandidate;
import com.simulatedbank.banking.transfer.application.TransferRecoveryCandidate.Incident;
import com.simulatedbank.banking.transfer.application.TransferRecoveryCandidate.Ports;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Basis;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Completion;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Entry;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Limit;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.LimitState;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Mode;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Movement;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Replay;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.ReplayProof;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence.Side;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.AttemptFence;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Context;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Outcome;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real candidate Transfer tables and transactions with synthetic providers. */
@Testcontainers
class TransferRecoveryCandidatePostgresTest {
    @TempDir
    static Path schemaDirectory;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transfer_recovery").withUsername("postgres").withPassword("test");

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant CREATED = Instant.parse("2026-01-01T14:00:00Z");
    private static final Instant TERMINAL = Instant.parse("2026-01-01T17:00:01Z");
    private static final LocalDate T1_DATE = LocalDate.of(2026, 1, 1);
    private static final LocalDate TERMINAL_DATE = LocalDate.of(2026, 1, 2);
    private static final BigDecimal AMOUNT = new BigDecimal("30.00");
    private static final BigDecimal AFTER = new BigDecimal("70.00");
    private static final Basis BASIS = new Basis(Mode.RESERVATION_REQUIRED, "policy-1", T1_DATE);
    private static final Context CONTEXT = new Context(10, 1, CREATED, "corr-10");
    private static final IdempotencyRetention.Snapshot TTL = new IdempotencyRetention.Snapshot(86400, "ttl-1");
    private static final Replay ORIGINAL = new Replay(10, TERMINAL, ReplayProof.ORIGINAL_MASKED,
            JsonNodeFactory.instance.objectNode().put("reference", "synthetic-ref").put("status", "COMPLETED"));

    private JdbcTemplate jdbc;
    private CommitFaultManager manager;
    private TransferRecoveryStore store;
    private Ports ports;
    private TransferSettlementCandidate settlement;
    private TransferRecoveryCandidate recovery;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP SCHEMA IF EXISTS banking CASCADE");
        TransferCandidateFlyway.locations(Flyway.configure().dataSource(source)
                .schemas("banking").defaultSchema("banking"), schemaDirectory).load().migrate();
        jdbc.execute("CREATE TABLE banking.recovery_guard_fixture (id BIGINT PRIMARY KEY)");
        jdbc.execute("INSERT INTO banking.recovery_guard_fixture VALUES (1)");
        jdbc.execute("CREATE TABLE banking.recovery_effect_fixture (kind TEXT PRIMARY KEY)");
        insertPending(10);
        manager = new CommitFaultManager(source);
        store = new TransferRecoveryStore(jdbc);
        settlement = mock(TransferSettlementCandidate.class);
        ports = mock(Ports.class);
        when(ports.tryLockCustomerGuard(1)).thenAnswer(call -> !jdbc.queryForList("""
                SELECT id FROM banking.recovery_guard_fixture WHERE id = 1 FOR UPDATE SKIP LOCKED
                """).isEmpty());
        doAnswer(call -> {
            assertThat(jdbc.queryForList("SELECT id FROM banking.recovery_guard_fixture WHERE id = 1 FOR UPDATE"))
                    .hasSize(1);
            return null;
        }).when(ports).lockCustomerGuard(1);
        when(ports.findTransferBasis(any())).thenReturn(Optional.of(BASIS));
        when(ports.limits(10)).thenReturn(List.of(limit(LimitState.PENDING)));
        when(ports.terminalRetention()).thenReturn(TTL);
        when(ports.findSafeReplay(any())).thenReturn(Optional.of(ORIGINAL));
        doAnswer(call -> {
            jdbc.update("INSERT INTO banking.recovery_effect_fixture VALUES ('SETTLE_RESERVATION')");
            return null;
        }).when(ports).settleForReconciliation(any(), any(), eq(TERMINAL), eq(TERMINAL_DATE));
        doAnswer(call -> {
            jdbc.update("INSERT INTO banking.recovery_effect_fixture VALUES ('AUDIT')");
            return null;
        }).when(ports).recordReconciled(any(), any());
        recovery = worker("worker-a");
    }

    @Test
    void crashAfterT1ResumesOnlyAfterClaimCommitsAndRestartDoesNotRunAgain() throws Exception {
        when(settlement.settleInRecoveryTransaction(eq(CONTEXT), any())).thenAnswer(call -> {
            AttemptFence claim = call.getArgument(1);
            try (var connection = manager.getDataSource().getConnection();
                 var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT attempt_epoch, attempt_token FROM banking.transfer WHERE transfer_id = 10")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(claim.epoch());
                assertThat(rows.getObject(2)).isEqualTo(claim.token());
            }
            assertThat(store.lockUsableFence(CONTEXT)).isEqualTo(claim);
            jdbc.update("INSERT INTO banking.recovery_effect_fixture VALUES ('T2')");
            store.complete(store.lock(CONTEXT).orElseThrow(), ORIGINAL, store.now(), TTL);
            return Outcome.completed();
        });

        recovery.recoverBatch();
        worker("restarted").recoverBatch();

        assertThat(field("status")).isEqualTo("COMPLETED");
        assertThat(field("attempt_state")).isEqualTo("DONE");
        assertThat(field("recovery_attempt_count")).isEqualTo("1");
        assertThat(effects()).containsExactly("T2");
        verify(settlement).settleInRecoveryTransaction(eq(CONTEXT), any());
    }

    @Test
    void failureBeforeClaimCommitLeavesNoDurableAttempt() {
        manager.arm(CommitFault.BEFORE_COMMIT);
        recovery.recoverBatch();
        assertThat(field("attempt_state")).isEqualTo("IDLE");
        assertThat(field("attempt_epoch")).isEqualTo("0");
        assertThat(field("recovery_attempt_count")).isEqualTo("0");
        verify(settlement, never()).settleInRecoveryTransaction(any(), any());
        verify(ports).alert(10, Incident.CLAIM_UNCERTAIN);
    }

    @Test
    void expiredDirectClaimFencesDelayedWorkerAndNeedsManualReview() {
        AttemptFence original = recovery.claimDirect(CONTEXT).orElseThrow();
        assertThat(worker("worker-b").claimDirect(CONTEXT)).isEmpty();
        recovery.recoverBatch();
        assertThat(field("attempt_state")).isEqualTo("CLAIMED");

        jdbc.update("UPDATE banking.transfer SET attempt_lease_expires_at = clock_timestamp() - interval '1 second'");
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
            assertThat(ports.tryLockCustomerGuard(1)).isTrue();
            return store.lockUsableFence(CONTEXT);
        })).isInstanceOf(IllegalStateException.class);
        worker("restarted").recoverBatch();

        assertThat(field("status")).isEqualTo("PENDING");
        assertThat(field("attempt_state")).isEqualTo("MANUAL");
        assertThat(field("attempt_token")).isNull();
        assertThat(field("attempt_epoch")).isEqualTo(Long.toString(original.epoch()));
        verify(ports).alert(10, Incident.FENCE_UNSAFE);
        verify(settlement, never()).settleInRecoveryTransaction(any(), any());
    }

    @Test
    void staleEpochOrTokenCannotMatchNewClaimAfterConfirmedRollback() {
        when(settlement.settleInRecoveryTransaction(eq(CONTEXT), any()))
                .thenThrow(new CannotAcquireLockException("fixture lock timeout"));
        recovery.recoverBatch();
        long oldEpoch = Long.parseLong(field("attempt_epoch"));
        assertThat(field("attempt_state")).isEqualTo("IDLE");
        assertThat(field("recovery_attempt_count")).isEqualTo("1");
        var seconds = jdbc.queryForObject("""
                SELECT extract(epoch FROM recovery_next_attempt_at - recovery_first_attempt_at)::int
                FROM banking.transfer WHERE transfer_id = 10
                """, Integer.class);
        assertThat(seconds).isBetween(30, 35);
        recovery.recoverBatch();
        assertThat(field("recovery_attempt_count")).isEqualTo("1");

        dueNow();
        doAnswer(call -> {
            AttemptFence claim = call.getArgument(1);
            var locked = store.lock(CONTEXT).orElseThrow();
            assertThat(locked.matches(new AttemptFence(oldEpoch, claim.token()))).isFalse();
            assertThat(locked.matches(new AttemptFence(claim.epoch(), java.util.UUID.randomUUID()))).isFalse();
            throw new CannotAcquireLockException("fixture lock timeout");
        }).when(settlement).settleInRecoveryTransaction(eq(CONTEXT), any());
        worker("restarted").recoverBatch();
        assertThat(field("attempt_epoch")).isEqualTo("2");
        assertThat(field("recovery_attempt_count")).isEqualTo("2");
    }

    @Test
    void retryStopsAtFiveAttemptsOrFifteenMinutesAndWritesManualState() {
        when(settlement.settleInRecoveryTransaction(eq(CONTEXT), any()))
                .thenThrow(new CannotAcquireLockException("fixture lock timeout"));
        for (int attempt = 1; attempt <= 5; attempt++) {
            recovery.recoverBatch();
            assertThat(field("recovery_attempt_count")).isEqualTo(Integer.toString(attempt));
            if (attempt < 5) dueNow();
        }
        assertThat(field("attempt_state")).isEqualTo("MANUAL");
        verify(ports).alert(10, Incident.RETRY_EXHAUSTED);

        insertPending(11);
        jdbc.update("""
                UPDATE banking.transfer SET attempt_epoch = 1, recovery_attempt_count = 1,
                    recovery_first_attempt_at = clock_timestamp() - interval '15 minutes',
                    recovery_next_attempt_at = clock_timestamp() + interval '1 minute'
                WHERE transfer_id = 11
                """);
        recovery.recoverBatch();
        assertThat(jdbc.queryForObject("SELECT attempt_state FROM banking.transfer WHERE transfer_id = 11", String.class))
                .isEqualTo("MANUAL");
        verify(ports).alert(11, Incident.RETRY_EXHAUSTED);
    }

    @Test
    void busyCustomerGuardAndTransferRowAreSkippedWithoutCountingAnAttempt() throws Exception {
        try (var held = manager.getDataSource().getConnection()) {
            held.setAutoCommit(false);
            try (var sql = held.createStatement()) {
                sql.execute("SELECT id FROM banking.recovery_guard_fixture WHERE id = 1 FOR UPDATE");
                recovery.recoverBatch();
                held.rollback();
                sql.execute("SELECT transfer_id FROM banking.transfer WHERE transfer_id = 10 FOR UPDATE");
                recovery.recoverBatch();
                held.rollback();
            }
        }
        assertThat(field("attempt_state")).isEqualTo("IDLE");
        assertThat(field("recovery_attempt_count")).isEqualTo("0");
        verify(settlement, never()).settleInRecoveryTransaction(any(), any());
    }

    @Test
    void concurrentWorkerSkipsEvenWhenActiveWorkerLeaseExpiresUnderLock() throws Exception {
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        manager.onNextCommit(() -> {
            try (var source = manager.getDataSource().getConnection(); var query = source.createStatement()) {
                query.executeUpdate("UPDATE banking.transfer SET attempt_lease_expires_at = clock_timestamp() + interval '3 seconds'");
            } catch (java.sql.SQLException error) {
                throw new IllegalStateException(error);
            }
        });
        when(settlement.settleInRecoveryTransaction(eq(CONTEXT), any())).thenAnswer(call -> {
            entered.countDown();
            assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
            store.complete(store.lock(CONTEXT).orElseThrow(), ORIGINAL, store.now(), TTL);
            return Outcome.completed();
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(recovery::recoverBatch);
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                jdbc.execute("SELECT pg_sleep(3)");
                assertThat(jdbc.queryForObject("""
                        SELECT attempt_lease_expires_at <= clock_timestamp() FROM banking.transfer
                        """, Boolean.class)).isTrue();
                worker("worker-b").recoverBatch();
                assertThat(field("recovery_attempt_count")).isEqualTo("1");
                assertThat(field("status")).isEqualTo("PENDING");
            } finally {
                proceed.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(field("status")).isEqualTo("COMPLETED");
        verify(settlement).settleInRecoveryTransaction(eq(CONTEXT), any());
    }

    @Test
    void reconcilesMatchedIncidentWithOriginalResponseAndSnapshotWithoutReposting() {
        fullEvidence(LimitState.PENDING);
        jdbc.update("UPDATE banking.idempotency_record SET expires_at = clock_timestamp() + interval '2 days'");
        Instant previousExpiry = idemTime("expires_at");
        recovery.recoverBatch();

        assertThat(field("status")).isEqualTo("COMPLETED");
        assertThat(idemTime("expires_at")).isEqualTo(previousExpiry);
        assertThat(idemTime("completed_at")).isAfter(TERMINAL);
        assertThat(jdbc.queryForObject("SELECT response_status FROM banking.idempotency_record", Integer.class)).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT response_body ->> 'reference' FROM banking.idempotency_record", String.class))
                .isEqualTo("synthetic-ref");
        assertThat(effects()).containsExactly("AUDIT", "SETTLE_RESERVATION");
        verify(settlement, never()).settleInRecoveryTransaction(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = LimitState.class, names = {"SETTLED", "LEGACY_USAGE"})
    void settledAndLegacyProofFinishWithoutIncrementingUsage(LimitState state) {
        fullEvidence(state);
        if (state == LimitState.LEGACY_USAGE) {
            when(ports.findTransferBasis(any())).thenReturn(Optional.of(new Basis(Mode.LEGACY, "policy-1", T1_DATE)));
        }
        recovery.recoverBatch();
        assertThat(field("status")).isEqualTo("COMPLETED");
        assertThat(effects()).containsExactly("AUDIT");
        verify(ports, never()).settleForReconciliation(any(), any(), any(), any());
        assertThat(idemTime("expires_at")).isEqualTo(idemTime("completed_at").plusSeconds(86400));
    }

    @ParameterizedTest
    @EnumSource(Gap.class)
    void incompleteEvidenceKeepsBusinessStatePending(Gap gap) {
        fullEvidence(LimitState.SETTLED);
        switch (gap) {
            case MOVEMENT -> when(ports.movements(10)).thenReturn(List.of());
            case DUPLICATE_LEDGER -> when(ports.entries(10)).thenReturn(List.of(debit(), debit()));
            case LIMIT_RELEASED -> when(ports.limits(10)).thenReturn(List.of(limit(LimitState.RELEASED)));
            case AUDIT -> when(ports.completions(10)).thenReturn(List.of());
            case MODE -> when(ports.findTransferBasis(any())).thenReturn(Optional.empty());
            case RESPONSE -> when(ports.findSafeReplay(any())).thenReturn(Optional.empty());
            case RESPONSE_MISMATCH -> when(ports.findSafeReplay(any())).thenReturn(Optional.of(
                    new Replay(10, TERMINAL, ReplayProof.ORIGINAL_MASKED,
                            JsonNodeFactory.instance.objectNode().put("status", "FAILED"))));
            case READ_ERROR -> when(ports.entries(10)).thenThrow(new CannotAcquireLockException("fixture evidence error"));
        }
        recovery.recoverBatch();
        assertManual();
        verify(settlement, never()).settleInRecoveryTransaction(any(), any());
        verify(ports).alert(10, gap == Gap.READ_ERROR ? Incident.OUTCOME_UNCERTAIN : Incident.EVIDENCE_UNSAFE);
    }

    @Test
    void auditFailureRollsBackTransferResponseAndReservation() {
        fullEvidence(LimitState.PENDING);
        doThrow(new IllegalStateException("fixture C1 failure")).when(ports).recordReconciled(any(), any());
        recovery.recoverBatch();
        assertManual();
        verify(ports).alert(10, Incident.OUTCOME_UNCERTAIN);
    }

    @ParameterizedTest
    @EnumSource(CommitFault.class)
    void ambiguousCommitIsRecheckedOnPrimaryAndNeverReposted(CommitFault fault) {
        fullEvidence(LimitState.SETTLED);
        doAnswer(call -> {
            jdbc.update("INSERT INTO banking.recovery_effect_fixture VALUES ('AUDIT')");
            manager.arm(fault);
            return null;
        }).when(ports).recordReconciled(any(), any());

        recovery.recoverBatch();
        worker("restarted").recoverBatch();

        if (fault == CommitFault.AFTER_COMMIT) {
            assertThat(field("status")).isEqualTo("COMPLETED");
            assertThat(effects()).containsExactly("AUDIT");
            verify(ports, never()).alert(anyLong(), any());
        } else {
            assertManual();
            verify(ports).alert(10, Incident.OUTCOME_UNCERTAIN);
        }
        verify(settlement, never()).settleInRecoveryTransaction(any(), any());
    }

    @Test
    void purgeSkipsLockedRetryAndPendingRowsAndAllowsKeyReuseOnlyAfterDelete() throws Exception {
        insertPending(11);
        insertPending(12);
        insertPending(13);
        expire(10, "COMPLETED");
        expire(11, "FAILED");
        // Incident: terminal idempotency and a PENDING transfer must remain visible for investigation.
        expireIdempotency(12, "COMPLETED", true);
        insertPending(14);
        jdbc.update("DELETE FROM banking.idempotency_record WHERE transfer_id = 14");
        try (var held = manager.getDataSource().getConnection()) {
            held.setAutoCommit(false);
            try (var sql = held.createStatement()) {
                sql.execute("SELECT transfer_id FROM banking.idempotency_record WHERE transfer_id = 10 FOR UPDATE");
                assertThat(recovery.purgeBatch()).isEqualTo(1);
                assertThatThrownBy(() -> insertKey(14, "key-10")).isInstanceOf(DuplicateKeyException.class);
                held.rollback();
            }
        }

        assertThat(recovery.purgeBatch()).isEqualTo(1);
        insertKey(14, "key-10");
        assertThat(jdbc.queryForList("SELECT transfer_id FROM banking.idempotency_record ORDER BY transfer_id", Long.class))
                .containsExactly(12L, 13L, 14L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.transfer", Integer.class)).isEqualTo(5);
    }

    @Test
    void cursorAdvancesPastBusyRowsInsteadOfRestartingAtTheOldest() {
        for (long id = 11; id <= 110; id++) insertPending(id);
        when(ports.tryLockCustomerGuard(1)).thenReturn(false);
        recovery.recoverBatch();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.transfer WHERE recovery_attempt_count > 0", Integer.class)).isZero();
        doAnswer(call -> !jdbc.queryForList(
                "SELECT id FROM banking.recovery_guard_fixture WHERE id = 1 FOR UPDATE SKIP LOCKED").isEmpty())
                .when(ports).tryLockCustomerGuard(1);
        recovery.recoverBatch();
        assertThat(jdbc.queryForList(
                "SELECT transfer_id FROM banking.transfer WHERE recovery_attempt_count > 0", Long.class))
                .containsExactly(110L);
    }

    @Test
    void recoveryBindsEachRecordAndRestoresOuterContextEvenOnFailure() {
        insertPending(11);
        var seen = new java.util.ArrayList<RequestContext>();
        when(settlement.settleInRecoveryTransaction(any(), any())).thenAnswer(call -> {
            Context context = call.getArgument(0);
            var current = RequestContextHolder.current().orElseThrow();
            seen.add(current);
            assertThat(current.correlationId()).isEqualTo(context.correlationId());
            assertThat(org.slf4j.MDC.get("requestId")).isEqualTo(current.requestId());
            if (context.transferId() == 10) throw new IllegalStateException("synthetic processing failure");
            return TransferSettlementCandidate.Outcome.completed();
        });
        when(ports.limits(11)).thenReturn(List.of(new Limit(11, 1, AMOUNT, "VND",
                LimitState.PENDING, "policy-1", T1_DATE, null, null, false, true)));
        var outer = new RequestContext("job", UUID.randomUUID().toString(), "192.0.2.1", "fixture");
        try (var scope = RequestContextHolder.bind(outer)) {
            recovery.recoverBatch();
            assertThat(RequestContextHolder.current()).contains(outer);
            assertThat(org.slf4j.MDC.get("correlationId")).isEqualTo("job");
        }
        assertThat(RequestContextHolder.current()).isEmpty();
        assertThat(org.slf4j.MDC.get("requestId")).isNull();
        assertThat(seen).extracting(RequestContext::correlationId).containsExactly("corr-10", "corr-11");
        assertThat(seen).extracting(RequestContext::requestId).doesNotHaveDuplicates().doesNotContain(outer.requestId());
        assertThat(seen).allSatisfy(context -> {
            assertThat(context.clientIp()).isNull();
            assertThat(context.userAgent()).isNull();
        });
    }

    @Test
    void pendingSnapshotIncludesManualRowsAndUsesPrimaryClock() {
        insertPending(11);
        jdbc.update("UPDATE banking.transfer SET attempt_state = 'MANUAL' WHERE transfer_id = 11");
        var snapshot = new TransactionTemplate(manager).execute(tx -> store.pendingSnapshot());
        assertThat(snapshot.count()).isEqualTo(2);
        assertThat(snapshot.oldestAgeSeconds()).isGreaterThan(300);
    }

    @Test
    void jobRequiresTrustedSystemContext() {
        doThrow(new SecurityException("fixture non-SYSTEM")).when(ports).requireSystemRecovery();
        assertThatThrownBy(recovery::recoverBatch).isInstanceOf(SecurityException.class);
        assertThatThrownBy(recovery::purgeBatch).isInstanceOf(SecurityException.class);
        assertThat(field("recovery_attempt_count")).isEqualTo("0");
    }

    private TransferRecoveryCandidate worker(String owner) {
        return new TransferRecoveryCandidate(manager, store, settlement, ports, ZONE, owner);
    }

    private void fullEvidence(LimitState limitState) {
        when(ports.movements(10)).thenReturn(List.of(new Movement(30, 10, 3, 4, AMOUNT, "VND",
                true, true, AFTER, AFTER, AFTER, AFTER)));
        when(ports.entries(10)).thenReturn(List.of(debit(), new Entry(42, 10, 2, Side.CREDIT, 4, AMOUNT, "VND", AFTER)));
        when(ports.completions(10)).thenReturn(List.of(new Completion(50, 10, "corr-10",
                "TRANSFER_COMPLETED", "SUCCESS", TERMINAL)));
        when(ports.limits(10)).thenReturn(List.of(limit(limitState)));
    }

    private static Entry debit() {
        return new Entry(41, 10, 1, Side.DEBIT, 3, AMOUNT, "VND", AFTER);
    }

    private static Limit limit(LimitState state) {
        boolean posted = state == LimitState.SETTLED || state == LimitState.LEGACY_USAGE;
        return new Limit(10, 1, AMOUNT, "VND", state, "policy-1", T1_DATE, posted ? TERMINAL_DATE : null,
                posted ? "policy-1" : null, posted, state == LimitState.PENDING);
    }

    private void dueNow() {
        jdbc.update("UPDATE banking.transfer SET recovery_next_attempt_at = clock_timestamp() WHERE transfer_id = 10");
    }

    private void assertManual() {
        assertThat(field("status")).isEqualTo("PENDING");
        assertThat(field("attempt_state")).isEqualTo("MANUAL");
        assertThat(jdbc.queryForObject("SELECT status FROM banking.idempotency_record", String.class)).isEqualTo("RECEIVED");
        assertThat(effects()).isEmpty();
    }

    private void insertPending(long id) {
        jdbc.update("""
                INSERT INTO banking.transfer (transfer_id, reference_no, customer_id, initiated_by_user_id,
                    source_account_id, destination_account_id, amount, correlation_id, created_at)
                VALUES (?, ?, 1, 2, 3, 4, 30, ?, ?)
                """, id, "ref-" + id, "corr-" + id, CREATED.atOffset(ZoneOffset.UTC));
        insertKey(id, "key-" + id);
    }

    private void insertKey(long id, String key) {
        jdbc.update("""
                INSERT INTO banking.idempotency_record (idempotency_key, transfer_id, request_hash, created_at,
                    expires_at, ttl_t1_seconds, ttl_t1_config_version)
                VALUES (?, ?, repeat('a',64), ?, ?, 86400, 'ttl-1')
                """, key, id, CREATED.atOffset(ZoneOffset.UTC), CREATED.plusSeconds(86400).atOffset(ZoneOffset.UTC));
    }

    private void expire(long id, String outcome) {
        boolean success = "COMPLETED".equals(outcome);
        var at = TERMINAL.atOffset(ZoneOffset.UTC);
        jdbc.update("UPDATE banking.transfer SET status = ?, completed_at = ?, failed_at = ?, failure_code = ? WHERE transfer_id = ?",
                outcome, success ? at : null, success ? null : at, success ? null : "INSUFFICIENT_BALANCE", id);
        expireIdempotency(id, outcome, success);
    }

    private void expireIdempotency(long id, String outcome, boolean success) {
        jdbc.update("""
                UPDATE banking.idempotency_record SET status = ?, completed_at = ?, response_status = ?,
                    ttl_terminal_seconds = 86400, ttl_terminal_config_version = 'ttl-1'
                WHERE transfer_id = ?
                """, outcome, TERMINAL.atOffset(ZoneOffset.UTC), success ? 201 : 422, id);
    }

    private String field(String field) {
        return jdbc.queryForObject("SELECT " + field + "::text FROM banking.transfer WHERE transfer_id = 10", String.class);
    }

    private Instant idemTime(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM banking.idempotency_record", OffsetDateTime.class).toInstant();
    }

    private List<String> effects() {
        return jdbc.queryForList("SELECT kind FROM banking.recovery_effect_fixture ORDER BY kind", String.class);
    }

    enum Gap { MOVEMENT, DUPLICATE_LEDGER, LIMIT_RELEASED, AUDIT, MODE, RESPONSE, RESPONSE_MISMATCH, READ_ERROR }
    enum CommitFault { BEFORE_COMMIT, AFTER_COMMIT }

    static final class CommitFaultManager extends DataSourceTransactionManager {
        private CommitFault fault;
        private Runnable afterNextCommit;

        CommitFaultManager(DriverManagerDataSource source) {
            super(source);
            setRollbackOnCommitFailure(true);
        }

        void arm(CommitFault next) { fault = next; }
        void onNextCommit(Runnable hook) { afterNextCommit = hook; }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            CommitFault next = fault;
            fault = null;
            if (next == CommitFault.BEFORE_COMMIT) throw new TransactionSystemException("fixture commit exception");
            super.doCommit(status);
            Runnable hook = afterNextCommit;
            afterNextCommit = null;
            if (hook != null) hook.run();
            if (next == CommitFault.AFTER_COMMIT) throw new TransactionSystemException("fixture lost commit acknowledgement");
        }
    }
}
