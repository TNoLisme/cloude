package com.simulatedbank.banking.transfer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate;
import com.simulatedbank.banking.transfer.application.TransferNotifications;
import com.simulatedbank.banking.transfer.application.TransferTelemetry;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.AttemptFence;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Context;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Failure;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Flag;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.FraudResult;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.Outcome;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.NotificationPorts;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL transaction tests using deliberately synthetic, test-only providers.
 * They are not evidence that real provider implementations join the transaction.
 */
@Testcontainers
class TransferSettlementCandidatePostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("settlement_fixture").withUsername("postgres").withPassword("test");
    private static final UUID TOKEN = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final AttemptFence CLAIM = new AttemptFence(1, TOKEN);
    private static final Instant CREATED = Instant.parse("2000-01-01T00:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("30.00");
    private JdbcTemplate jdbc;
    private DataSource dataSource;

    @BeforeEach
    void setup() {
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        source.setDriverClassName("org.postgresql.Driver");
        dataSource = source;
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP SCHEMA IF EXISTS transfer_t2_fixture CASCADE");
        jdbc.execute("CREATE SCHEMA transfer_t2_fixture");
        jdbc.execute("CREATE TABLE transfer_t2_fixture.customer_guard (customer_id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE transfer_t2_fixture.transfer (id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL, "
                + "created_at TIMESTAMPTZ NOT NULL, status TEXT NOT NULL, epoch BIGINT NOT NULL, token UUID NOT NULL)");
        jdbc.execute("CREATE TABLE transfer_t2_fixture.account (id BIGINT PRIMARY KEY, balance NUMERIC(19,2) NOT NULL CHECK (balance >= 0))");
        jdbc.execute("CREATE TABLE transfer_t2_fixture.usage (customer_id BIGINT PRIMARY KEY, amount NUMERIC(19,2) NOT NULL)");
        jdbc.execute("CREATE TABLE transfer_t2_fixture.reservation (transfer_id BIGINT PRIMARY KEY, status TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE transfer_t2_fixture.idempotency (transfer_id BIGINT PRIMARY KEY, status TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE transfer_t2_fixture.ledger (transfer_id BIGINT NOT NULL, seq INT NOT NULL, "
                + "account_id BIGINT NOT NULL, amount NUMERIC(19,2) NOT NULL, PRIMARY KEY(transfer_id, seq))");
        jdbc.execute("CREATE TABLE transfer_t2_fixture.outbox (transfer_id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE transfer_t2_fixture.audit (transfer_id BIGINT PRIMARY KEY, outcome TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE transfer_t2_fixture.caller_work (id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE transfer_t2_fixture.fraud_flag (id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO transfer_t2_fixture.customer_guard VALUES (7)");
        jdbc.update("INSERT INTO transfer_t2_fixture.transfer VALUES (3,7,?,'PENDING',1,?)", Timestamp.from(CREATED), TOKEN);
        jdbc.update("INSERT INTO transfer_t2_fixture.account VALUES (10,100.00),(20,40.00)");
        jdbc.update("INSERT INTO transfer_t2_fixture.usage VALUES (7,0.00)");
        jdbc.update("INSERT INTO transfer_t2_fixture.reservation VALUES (3,'PENDING')");
        jdbc.update("INSERT INTO transfer_t2_fixture.idempotency VALUES (3,'RECEIVED')");
    }

    @Test
    void successCommitsMoneyBalancedLedgerUsageTerminalAuditAndOutboxTogether() {
        Outcome outcome = candidate(new FakePorts(jdbc, FailurePoint.NONE)).settle(3, CLAIM);
        assertThat(outcome).isEqualTo(Outcome.completed());
        assertMoney("70.00", "70.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_t2_fixture.ledger", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM transfer_t2_fixture.ledger", BigDecimal.class))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbc.queryForObject("SELECT amount FROM transfer_t2_fixture.ledger WHERE transfer_id=3 AND seq=1 AND account_id=10", BigDecimal.class))
                .isEqualByComparingTo("-30.00");
        assertThat(jdbc.queryForObject("SELECT amount FROM transfer_t2_fixture.ledger WHERE transfer_id=3 AND seq=2 AND account_id=20", BigDecimal.class))
                .isEqualByComparingTo("30.00");
        assertThat(jdbc.queryForObject("SELECT amount FROM transfer_t2_fixture.usage WHERE customer_id=7", BigDecimal.class))
                .isEqualByComparingTo(AMOUNT);
        assertThat(value("transfer", "status")).isEqualTo("COMPLETED");
        assertThat(value("idempotency", "status")).isEqualTo("COMPLETED");
        assertThat(value("reservation", "status")).isEqualTo("SETTLED");
        assertThat(count("audit")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void terminalMetricFollowsOuterCommitAndAuditFailureSurvivesRollback() {
        var manager = new DataSourceTransactionManager(dataSource);
        var meters = new SimpleMeterRegistry();
        var telemetry = new TransferTelemetry(meters);
        var failing = new TransferSettlementCandidate(manager, new FakePorts(jdbc, FailurePoint.AFTER_AUDIT),
                ZoneId.of("Asia/Ho_Chi_Minh"), telemetry);
        assertThatThrownBy(() -> failing.settle(3, CLAIM)).isInstanceOf(IllegalStateException.class);
        assertThat(meters.get("transfer.audit.required.failure").tag("class", "C1").counter().count()).isEqualTo(1);
        assertThat(meters.find("banking.transfer").counter()).isNull();
        assertUnchanged();

        var successful = new TransferSettlementCandidate(manager, new FakePorts(jdbc, FailurePoint.NONE),
                ZoneId.of("Asia/Ho_Chi_Minh"), telemetry);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            successful.settleInRecoveryTransaction(new Context(3, 7, CREATED, "intent-correlation"), CLAIM);
            assertThat(meters.find("banking.transfer").counter()).isNull();
        });
        assertThat(meters.get("banking.transfer").tags("status", "COMPLETED", "failure_code", "NONE")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get("transfer.t2.duration").tag("outcome", "COMMITTED").timer().count()).isEqualTo(1);
        assertThat(meters.get("transfer.t2.duration").tag("outcome", "ROLLED_BACK").timer().count()).isEqualTo(1);
    }

    @Test
    void fraudEngineFailureRollsBackPartialFlagBeforeContinuing() {
        assertThat(candidate(new FakePorts(jdbc, FailurePoint.FRAUD_ENGINE)).settle(3, CLAIM))
                .isEqualTo(Outcome.completed());
        assertThat(count("fraud_flag")).isZero();
        assertMoney("70.00", "70.00");
        assertThat(count("ledger")).isEqualTo(2);
        assertThat(value("transfer", "status")).isEqualTo("COMPLETED");
    }

    @Test
    void technicalFailureAfterEachMoneyPhaseRollsBackEveryWrite() {
        for (FailurePoint point : List.of(FailurePoint.AFTER_DEBIT, FailurePoint.AFTER_CREDIT,
                FailurePoint.AFTER_LEDGER, FailurePoint.AFTER_OUTBOX, FailurePoint.AFTER_AUDIT)) {
            assertThatThrownBy(() -> candidate(new FakePorts(jdbc, point)).settle(3, CLAIM))
                    .as("failure at %s", point).isInstanceOf(IllegalStateException.class);
            assertUnchanged();
        }
    }

    @Test
    void recoveryFailureMarksSharedTransactionRollbackOnlyEvenWhenCallerCatchesIt() {
        for (FailurePoint point : List.of(FailurePoint.AFTER_CREDIT, FailurePoint.AFTER_AUDIT)) {
            var manager = new DataSourceTransactionManager(dataSource);
            var recovery = new TransferSettlementCandidate(manager, new FakePorts(jdbc, point),
                    ZoneId.of("Asia/Ho_Chi_Minh"));
            var caller = new TransactionTemplate(manager);
            assertThatThrownBy(() -> caller.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO transfer_t2_fixture.caller_work VALUES (1)");
                assertThatThrownBy(() -> recovery.settleInRecoveryTransaction(new Context(3, 7, CREATED, "intent-correlation"), CLAIM))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(status.isRollbackOnly()).isTrue();
                jdbc.update("INSERT INTO transfer_t2_fixture.caller_work VALUES (2)");
            })).as("recovery failure at %s", point).isInstanceOf(UnexpectedRollbackException.class);
            assertUnchanged();
            assertThat(count("caller_work")).isZero();
        }
    }

    @Test
    void insufficientBalanceCommitsFailureWithoutMoneyLedgerOrUsage() {
        jdbc.update("UPDATE transfer_t2_fixture.account SET balance=20.00 WHERE id=10");
        Outcome outcome = candidate(new FakePorts(jdbc, FailurePoint.NONE)).settle(3, CLAIM);
        assertThat(outcome).isEqualTo(Outcome.failed(Failure.INSUFFICIENT_BALANCE));
        assertMoney("20.00", "40.00");
        assertThat(value("transfer", "status")).isEqualTo("FAILED");
        assertThat(value("idempotency", "status")).isEqualTo("FAILED");
        assertThat(value("reservation", "status")).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT amount FROM transfer_t2_fixture.usage WHERE customer_id=7", BigDecimal.class))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(count("ledger")).isZero();
        assertThat(count("audit")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void staleTokenCannotWriteAndConcurrentAttemptsDebitOnlyOnce() throws Exception {
        assertThatThrownBy(() -> candidate(new FakePorts(jdbc, FailurePoint.NONE))
                .settle(3, new AttemptFence(1, UUID.fromString("00000000-0000-4000-8000-000000000002"))))
                .isInstanceOf(IllegalStateException.class);
        assertUnchanged();

        CountDownLatch firstHasGuard = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        FakePorts ports = new FakePorts(jdbc, FailurePoint.NONE, firstHasGuard, secondStarted, releaseFirst);
        TransferSettlementCandidate candidate = candidate(ports);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> settleOrReject(candidate));
            assertThat(firstHasGuard.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> second = pool.submit(() -> settleOrReject(candidate));
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            releaseFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
        assertMoney("70.00", "70.00");
        assertThat(count("ledger")).isEqualTo(2);
        assertThat(count("audit")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT amount FROM transfer_t2_fixture.usage WHERE customer_id=7", BigDecimal.class))
                .isEqualByComparingTo(AMOUNT);
    }

    private boolean settleOrReject(TransferSettlementCandidate candidate) {
        try {
            return candidate.settle(3, CLAIM).equals(Outcome.completed());
        } catch (IllegalStateException rejected) {
            return false;
        }
    }

    private TransferSettlementCandidate candidate(FakePorts ports) {
        return new TransferSettlementCandidate(new DataSourceTransactionManager(dataSource), ports,
                ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    private void assertUnchanged() {
        assertMoney("100.00", "40.00");
        assertThat(value("transfer", "status")).isEqualTo("PENDING");
        assertThat(value("reservation", "status")).isEqualTo("PENDING");
        assertThat(value("idempotency", "status")).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT amount FROM transfer_t2_fixture.usage WHERE customer_id=7", BigDecimal.class))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(count("ledger")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(count("audit")).isZero();
    }

    private void assertMoney(String source, String destination) {
        assertThat(jdbc.queryForObject("SELECT balance FROM transfer_t2_fixture.account WHERE id=10", BigDecimal.class))
                .isEqualByComparingTo(source);
        assertThat(jdbc.queryForObject("SELECT balance FROM transfer_t2_fixture.account WHERE id=20", BigDecimal.class))
                .isEqualByComparingTo(destination);
    }

    private String value(String table, String column) {
        // Both arguments are compile-time constants in this test.
        return jdbc.queryForObject("SELECT " + column + " FROM transfer_t2_fixture." + table, String.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM transfer_t2_fixture." + table, Integer.class);
    }

    private enum FailurePoint { NONE, AFTER_DEBIT, AFTER_CREDIT, AFTER_LEDGER, AFTER_OUTBOX, AFTER_AUDIT, FRAUD_ENGINE }

    /** All fake writes intentionally use the same JdbcTemplate/DataSource as the transaction manager. */
    private static final class FakePorts implements NotificationPorts {
        private final JdbcTemplate jdbc;
        private final FailurePoint failure;
        private final CountDownLatch firstHasGuard;
        private final CountDownLatch secondStarted;
        private final CountDownLatch releaseFirst;
        private final AtomicBoolean firstGuardCall = new AtomicBoolean(true);

        FakePorts(JdbcTemplate jdbc, FailurePoint failure) {
            this(jdbc, failure, null, null, null);
        }
        FakePorts(JdbcTemplate jdbc, FailurePoint failure, CountDownLatch firstHasGuard,
                  CountDownLatch secondStarted, CountDownLatch releaseFirst) {
            this.jdbc = jdbc;
            this.failure = failure;
            this.firstHasGuard = firstHasGuard;
            this.secondStarted = secondStarted;
            this.releaseFirst = releaseFirst;
        }
        @Override public Context readContextOnPrimary(long id) {
            return jdbc.queryForObject("SELECT id, customer_id, created_at FROM transfer_t2_fixture.transfer WHERE id=?",
                    (rs, row) -> new Context(rs.getLong(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class).toInstant(), "intent-correlation"), id);
        }
        @Override public void lockStepUpGuard(long customerId) {
            boolean first = firstGuardCall.getAndSet(false);
            if (!first && secondStarted != null) secondStarted.countDown();
            jdbc.queryForObject("SELECT customer_id FROM transfer_t2_fixture.customer_guard WHERE customer_id=? FOR UPDATE",
                    Long.class, customerId);
            if (first && firstHasGuard != null) {
                firstHasGuard.countDown();
                try {
                    if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test interrupted", e);
                }
            }
        }
        @Override public AttemptFence lockPendingAndReadFence(Context context) {
            return jdbc.queryForObject("SELECT customer_id, created_at, status, epoch, token FROM transfer_t2_fixture.transfer "
                            + "WHERE id=? FOR UPDATE", (rs, row) -> {
                if (rs.getLong(1) != context.customerId() || !rs.getObject(2, OffsetDateTime.class).toInstant().equals(context.createdAt())
                        || !"PENDING".equals(rs.getString(3))) throw new IllegalStateException("not pending or context changed");
                return new AttemptFence(rs.getLong(4), rs.getObject(5, UUID.class));
            }, context.transferId());
        }
        @Override public void lockAccountsAscending(Context context) {
            jdbc.query("SELECT id FROM transfer_t2_fixture.account WHERE id IN (10,20) ORDER BY id FOR UPDATE", rs -> { });
        }
        @Override public Instant primaryClockTimestamp() {
            return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
        }
        @Override public void lockUsageCustomerThenAccount(Context context, Instant terminalAt, LocalDate date) {
            jdbc.queryForObject("SELECT customer_id FROM transfer_t2_fixture.usage WHERE customer_id=? FOR UPDATE", Long.class, context.customerId());
        }
        @Override public Optional<Failure> validateLockedState(Context context, LocalDate date) { return Optional.empty(); }
        @Override public boolean fraudSavepointSupported() { return failure == FailurePoint.FRAUD_ENGINE; }
        @Override public boolean fraudContextUsableAfterRollback() {
            return jdbc.queryForObject("SELECT 1", Integer.class) == 1;
        }
        @Override public void recordFraudFailOpen() { }
        @Override public FraudResult evaluateFraud(Context context, Instant terminalAt) {
            if (failure == FailurePoint.FRAUD_ENGINE) {
                jdbc.update("INSERT INTO transfer_t2_fixture.fraud_flag VALUES (1)");
                throw new TransferSettlementCandidate.FraudEngineException("synthetic engine failure");
            }
            return new FraudResult(false, List.of());
        }
        @Override public boolean debitConditional(Context context) {
            int updated = jdbc.update("UPDATE transfer_t2_fixture.account SET balance=balance-? WHERE id=10 AND balance>=?", AMOUNT, AMOUNT);
            if (updated == 1 && failure == FailurePoint.AFTER_DEBIT) throw new IllegalStateException("after debit");
            return updated == 1;
        }
        @Override public void credit(Context context) {
            jdbc.update("UPDATE transfer_t2_fixture.account SET balance=balance+? WHERE id=20", AMOUNT);
            if (failure == FailurePoint.AFTER_CREDIT) throw new IllegalStateException("after credit");
        }
        @Override public void postAndVerifyBalancedLedger(Context context) {
            jdbc.update("INSERT INTO transfer_t2_fixture.ledger VALUES (?,1,10,?),(?,2,20,?)", context.transferId(), AMOUNT.negate(), context.transferId(), AMOUNT);
            if (failure == FailurePoint.AFTER_LEDGER) throw new IllegalStateException("after ledger");
            BigDecimal sum = jdbc.queryForObject("SELECT sum(amount) FROM transfer_t2_fixture.ledger WHERE transfer_id=?", BigDecimal.class, context.transferId());
            if (sum == null || sum.signum() != 0) throw new IllegalStateException("unbalanced");
        }
        @Override public void settleCompletedReservation(Context context, Instant at, LocalDate date) {
            if (jdbc.update("UPDATE transfer_t2_fixture.reservation SET status='SETTLED' WHERE transfer_id=? AND status='PENDING'", context.transferId()) != 1)
                throw new IllegalStateException("reservation not pending");
            jdbc.update("UPDATE transfer_t2_fixture.usage SET amount=amount+? WHERE customer_id=?", AMOUNT, context.customerId());
        }
        @Override public void releaseFailedReservation(Context context, Instant at, LocalDate date) {
            if (jdbc.update("UPDATE transfer_t2_fixture.reservation SET status='RELEASED' WHERE transfer_id=? AND status='PENDING'", context.transferId()) != 1)
                throw new IllegalStateException("reservation not pending");
        }
        @Override public TransferNotifications.Facts writeTerminal(Context context, Outcome outcome, Instant at) {
            String status = outcome.failure().isPresent() ? "FAILED" : "COMPLETED";
            if (jdbc.update("UPDATE transfer_t2_fixture.transfer SET status=? WHERE id=? AND status='PENDING'", status, context.transferId()) != 1)
                throw new IllegalStateException("transfer not pending");
            jdbc.update("UPDATE transfer_t2_fixture.idempotency SET status=? WHERE transfer_id=?", status, context.transferId());
            return new TransferNotifications.Facts(context.transferId(), "TXN20261117A1B2C3D4",
                    context.customerId(), context.customerId(), new TransferAmount(AMOUNT, "VND"),
                    "**** **** 0010", "**** **** 0020", context.correlationId());
        }
        @Override public TransferNotifications notifications() {
            var sender = new TransferNotifications.Recipient(7, TransferNotifications.Channel.IN_APP, "OUTCOME_SENDER");
            return new TransferNotifications((facts, outcome) -> List.of(sender, sender), notices -> {
                for (var notice : notices) {
                    jdbc.update("INSERT INTO transfer_t2_fixture.outbox VALUES (?)", notice.transferId());
                }
                if (failure == FailurePoint.AFTER_OUTBOX) throw new IllegalStateException("after outbox");
            });
        }
        @Override public void flushBusinessWrites() { }
        @Override public void recordFraudFlagAudit(Context context, Flag flag, Instant terminalAt) { throw new AssertionError("no flags in fixture"); }
        @Override public void recordTerminalAudit(Context context, Outcome outcome, Instant terminalAt) {
            jdbc.update("INSERT INTO transfer_t2_fixture.audit VALUES (?,?)", context.transferId(), outcome.failure().isPresent() ? "FAILED" : "COMPLETED");
            if (failure == FailurePoint.AFTER_AUDIT) throw new IllegalStateException("after audit");
        }
    }
}
