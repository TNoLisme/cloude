package com.simulatedbank.banking.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort.ApprovedLimitChange;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort.LimitChangeRequest;
import com.simulatedbank.banking.limit.api.TransferLimits;
import com.simulatedbank.banking.limit.api.TransferLimits.Allowed;
import com.simulatedbank.banking.limit.api.TransferLimits.Denial;
import com.simulatedbank.banking.limit.api.TransferLimits.Guard;
import com.simulatedbank.banking.limit.api.TransferLimits.Mismatch;
import com.simulatedbank.banking.limit.api.TransferLimits.Missing;
import com.simulatedbank.banking.limit.api.TransferLimits.Recorded;
import com.simulatedbank.banking.limit.api.TransferLimits.RecoveryActor;
import com.simulatedbank.banking.limit.api.TransferLimits.Reservation;
import com.simulatedbank.banking.limit.api.TransferLimits.ReservationState;
import com.simulatedbank.banking.limit.api.TransferLimits.Scope;
import com.simulatedbank.banking.limit.api.TransferLimits.ScopeUsage;
import com.simulatedbank.banking.limit.api.TransferLimits.StepUpPolicy;
import com.simulatedbank.banking.limit.api.TransferLimits.TransferLink;
import com.simulatedbank.banking.support.IntegratedLimitReferences;
import com.simulatedbank.banking.transfer.adapter.out.limit.TransferLimitAdapter;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferPersistence;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferPersistence.PendingInsert;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferPersistence.PendingTransfer;
import com.simulatedbank.banking.transfer.application.TransferIntentCandidate;
import com.simulatedbank.banking.transfer.application.TransferRecoveryEvidence;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate;
import com.simulatedbank.banking.transfer.domain.IdempotencyDecision;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferIdentity;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(TransferLimitPostgreSqlIntegrationTest.CandidatePersistence.class)
@Timeout(60)
class TransferLimitPostgreSqlIntegrationTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final long CUSTOMER = 11;
    private static final long OTHER = 12;
    private static final long SOURCE = 21;
    private static final long SECOND_SOURCE = 23;
    private static final long DESTINATION = 22;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("limit/init-roles.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "banking_app");
        registry.add("spring.datasource.password", () -> "test_app");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "banking_migrator");
        registry.add("spring.flyway.password", () -> "test_migrator");
        registry.add("app.limit.policy.max-per-transaction", () -> "100000000");
        registry.add("app.limit.policy.max-daily", () -> "100000000");
    }

    @Autowired TransferLimits limits;
    @Autowired TransferLimitAdapter adapter;
    @Autowired TransferPersistence transfers;
    @Autowired LimitConfigMutationPort admin;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    private JdbcTemplate observer;

    @BeforeEach
    void seed() throws Exception {
        observer = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), "banking_app", "test_app"));
        try (var connection = migrator(); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM banking.limit_transfer_usage_effect");
            statement.execute("DELETE FROM banking.limit_transfer_reservation");
            statement.execute("DELETE FROM banking.idempotency_record");
            statement.execute("DELETE FROM banking.transfer");
            statement.execute("DELETE FROM banking.limit_config");
            statement.execute("DELETE FROM banking.limit_usage");
            IntegratedLimitReferences.seed(connection, true);
            statement.execute("""
                    INSERT INTO banking.account(account_id, customer_id, account_no_enc, account_no_hash, account_no_last4)
                    OVERRIDING SYSTEM VALUE VALUES (23, 11, decode('00','hex'), lpad('17',64,'0'), '0023')
                    ON CONFLICT (account_id) DO NOTHING
                    """);
        }
    }

    @Test
    void readUsesRealSessionOwnershipAndDoesNotCreateUsage() throws Exception {
        config(CUSTOMER, null, "100", "100");
        config(CUSTOMER, SOURCE, "90", "100");
        String token = "limit-it-" + UUID.randomUUID();
        observer.update("""
                INSERT INTO banking.user_role(user_id, role_id)
                SELECT ?, role_id FROM banking.role WHERE role_code = 'CUSTOMER'
                ON CONFLICT DO NOTHING
                """, CUSTOMER);
        observer.update("""
                INSERT INTO banking.session(session_token_hash, csrf_token_hash, user_id, idle_expires_at, absolute_expires_at)
                VALUES (?, ?, ?, now() + interval '20 minutes', now() + interval '1 hour')
                """, HmacService.sha256Hex(token), "a".repeat(64), CUSTOMER);
        String authorization = "Bearer " + token;

        mvc.perform(get("/api/v1/limits").param("accountId", "21").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.effectivePerTransactionLimit").value("90.00"));
        mvc.perform(get("/api/v1/limits").param("accountId", "22").header("Authorization", authorization))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/limits").param("customerId", "12").header("Authorization", authorization))
                .andExpect(status().isForbidden());

        Instant now = dbNow();
        LocalDate today = date(now);
        assertThat(limits.getUsageSnapshot(CUSTOMER, SOURCE, now, today).scopes())
                .extracting(ScopeUsage::configVersion).containsExactly(1, 1);
        assertThat(count("limit_usage")).isZero();
        observer.update("INSERT INTO banking.limit_usage(customer_id, usage_date, used_amount, transfer_count)"
                + " VALUES (?, ?, 120, 1)", CUSTOMER, today);
        mvc.perform(get("/api/v1/limits").param("accountId", "21").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.effectiveRemainingDaily").value("0.00"));

        setSelectPrivilege("account", false);
        try {
            mvc.perform(get("/api/v1/limits").param("accountId", "21").header("Authorization", authorization))
                    .andExpect(status().is5xxServerError());
        } finally {
            setSelectPrivilege("account", true);
        }

        try (var connection = migrator(); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM banking.limit_config");
        }
        mvc.perform(get("/api/v1/limits").param("accountId", "21").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.effectiveRemainingDaily").isEmpty());
        assertThat(count("limit_usage")).isEqualTo(1);
    }

    @Test
    void guardSerializesDifferentAccountsAndIncludesPendingFromYesterday() throws Exception {
        config(CUSTOMER, null, "30000000", "50000000");
        config(OTHER, null, "30000000", "50000000");
        reserveYesterday("8000000");
        LocalDate today = date(dbNow());
        observer.update("INSERT INTO banking.limit_usage(customer_id, usage_date, used_amount, transfer_count)"
                + " VALUES (?, ?, 3000000, 1)", CUSTOMER, today);
        var command = command(CUSTOMER, SOURCE, DESTINATION, "9000000");
        var firstReady = new CompletableFuture<Integer>();
        var secondReady = new CompletableFuture<Integer>();
        var commit = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var first = pool.submit(() -> tx.executeWithoutResult(status -> {
                Guard guard = adapter.lockCustomer(CUSTOMER);
                var assessment = adapter.assess(guard, command, StepUpPolicy.DEFAULT);
                assertThat(assessment.decision().projectedAmount()).isEqualByComparingTo("20000000");
                assertThat(assessment.candidateView().requiresChallenge()).isFalse();
                adapter.reserve(guard, command, insert(command), assessment);
                firstReady.complete(pid());
                waitFor(commit);
            }));
            try {
                int blocker = firstReady.get(10, TimeUnit.SECONDS);
                var second = pool.submit(() -> tx.execute(status -> {
                    secondReady.complete(pid());
                    var guard = adapter.lockCustomer(CUSTOMER);
                    return limits.evaluateStepUpUnderGuard(guard, SECOND_SOURCE, money("0.01"), "VND", StepUpPolicy.DEFAULT);
                }));
                waitForBlocked(secondReady.get(10, TimeUnit.SECONDS), blocker);
                var other = pool.submit(() -> tx.execute(status -> {
                    var guard = adapter.lockCustomer(OTHER);
                    return limits.evaluateStepUpUnderGuard(guard, DESTINATION, money("10000000"), "VND", StepUpPolicy.DEFAULT);
                }));
                assertThat(other.get(10, TimeUnit.SECONDS).required()).isTrue();
                assertThat(second.isDone()).isFalse();
                commit.countDown();
                first.get(10, TimeUnit.SECONDS);
                var assessed = second.get(10, TimeUnit.SECONDS);
                assertThat(assessed.required()).isTrue();
                assertThat(assessed.projectedAmount()).isEqualByComparingTo("20000000.01");
            } finally {
                commit.countDown();
            }
        }
        assertThat(count("limit_transfer_reservation")).isEqualTo(2);
        assertThat(count("idempotency_record")).isEqualTo(1);
    }

    @Test
    void lockTimeoutRollsBackTheWaitingTransactionAndGuardIsReusable() throws Exception {
        config(CUSTOMER, null, "100", "100");
        try (var pool = Executors.newSingleThreadExecutor()) {
            var held = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var owner = pool.submit(() -> tx.executeWithoutResult(status -> {
                limits.lockStepUpGuard(CUSTOMER);
                held.countDown();
                waitFor(release);
            }));
            try {
                waitFor(held);
                assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    jdbc.update("INSERT INTO limit_test.business_probe(id) VALUES (99)");
                    jdbc.execute("SET LOCAL lock_timeout = '100ms'");
                    limits.lockStepUpGuard(CUSTOMER);
                })).isInstanceOf(DataAccessException.class)
                        .hasRootCauseInstanceOf(java.sql.SQLException.class)
                        .rootCause().extracting(ex -> ((java.sql.SQLException) ex).getSQLState()).isEqualTo("55P03");
                assertThat(observer.queryForObject("SELECT count(*) FROM limit_test.business_probe WHERE id = 99",
                        Integer.class)).isZero();
            } finally {
                release.countDown();
            }
            owner.get(10, TimeUnit.SECONDS);
        }
        assertThat(create(command(CUSTOMER, SOURCE, DESTINATION, "10"))).isNotNull();
    }

    @Test
    void candidateChallengeAndAuditFailureLeaveNoBusinessRows() {
        config(CUSTOMER, null, "30000000", "50000000");
        var candidate = new TransferIntentCandidate(manager, new IntentPorts());
        assertThat(candidate.create(command(CUSTOMER, SOURCE, DESTINATION, "10000000")))
                .isInstanceOf(TransferIntentCandidate.ChallengeRequired.class);
        assertThatThrownBy(() -> candidate.create(command(CUSTOMER, SOURCE, DESTINATION, "100")))
                .isInstanceOf(TransferIntentCandidate.AuditWriteException.class);
        assertThat(count("transfer")).isZero();
        assertThat(count("idempotency_record")).isZero();
        assertThat(count("limit_transfer_reservation")).isZero();
        assertThat(count("limit_usage")).isZero();
        assertThat(count("ledger_entry")).isZero();
    }

    @Test
    void duplicateKeyAndMismatchedTransferLinkRollBackTogether() {
        config(CUSTOMER, null, "1000", "3000");
        var original = command(CUSTOMER, SOURCE, DESTINATION, "100");
        create(original);
        var identity = new TransferIdentity("duplicate-key", CUSTOMER, CUSTOMER, SECOND_SOURCE, DESTINATION, null);
        var duplicate = new TransferIntentCandidate.Command(identity, original.amount(), null, "duplicate-key",
                original.idempotencyKey(), original.requestHash(), null);
        assertThatThrownBy(() -> create(duplicate)).isInstanceOf(TransferPersistence.DuplicateIdempotencyKeyException.class);

        var wrong = command(CUSTOMER, SOURCE, DESTINATION, "105");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            var guard = limits.lockStepUpGuard(CUSTOMER);
            var assessment = limits.evaluateStepUpUnderGuard(guard, SOURCE, money("106"), "VND", StepUpPolicy.DEFAULT);
            var pending = insert(wrong);
            limits.reserveForPendingTransfer(guard, new TransferLink(pending.id(), CUSTOMER, SOURCE,
                    money("106"), "VND", pending.createdAt()), assessment);
        })).isInstanceOf(DataAccessException.class);
        assertThat(count("transfer")).isEqualTo(1);
        assertThat(count("idempotency_record")).isEqualTo(1);
        assertThat(count("limit_transfer_reservation")).isEqualTo(1);
    }

    @Test
    void checkAtDayBoundaryUsesFixedInstantAndRejectsBackwardClock() {
        config(CUSTOMER, null, "100", "100");
        config(CUSTOMER, SOURCE, "100", "100");
        var first = create(command(CUSTOMER, SOURCE, DESTINATION, "100"));
        var second = create(command(CUSTOMER, SOURCE, DESTINATION, "100"));
        Instant midnight = date(dbNow()).plusDays(1).atStartOfDay(ZONE).toInstant();
        Instant before = midnight.minusNanos(1000);

        assertThatThrownBy(() -> complete(first, first.transfer().createdAt().minusSeconds(1)))
                .hasMessageContaining("clock");
        assertThat(count("limit_usage")).isZero();

        var primaryClock = new java.util.concurrent.atomic.AtomicReference<>(before);
        tx.executeWithoutResult(status -> {
            var guard = adapter.lockCustomer(CUSTOMER);
            Instant terminalAt = primaryClock.get();
            var check = adapter.check(guard, first, terminalAt, date(terminalAt));
            primaryClock.set(midnight);
            adapter.finish(guard, first, TransferSettlementCandidate.Outcome.completed(), terminalAt,
                    date(terminalAt), check);
        });
        complete(second, primaryClock.get());

        assertUsage(null, date(before), "100", 1);
        assertUsage(SOURCE, date(before), "100", 1);
        assertUsage(null, date(midnight), "100", 1);
        assertUsage(SOURCE, date(midnight), "100", 1);
        assertThat(evidence(first).terminalAt()).contains(before);
        assertThat(evidence(second).terminalAt()).contains(midnight);
    }

    @Test
    void settlesExactlyOnceAcrossAccountsAndReleasesOverLimit() {
        config(CUSTOMER, null, "100", "100");
        config(CUSTOMER, SOURCE, "100", "100");
        var first = create(command(CUSTOMER, SOURCE, DESTINATION, "100"));
        var second = create(command(CUSTOMER, SECOND_SOURCE, DESTINATION, "0.01"));
        var failedBeforeUsageCheck = create(command(CUSTOMER, SECOND_SOURCE, DESTINATION, "25"));
        Instant terminalAt = dbNow();
        LocalDate date = date(terminalAt);
        complete(first, terminalAt);
        assertThat(evidence(first).effects()).extracting(TransferLimits.ScopeEffect::scope)
                .containsExactly(Scope.CUSTOMER, Scope.ACCOUNT);
        assertThatThrownBy(() -> complete(first, terminalAt)).hasMessageContaining("pending");
        tx.executeWithoutResult(status -> {
            var guard = adapter.lockCustomer(CUSTOMER);
            var check = adapter.check(guard, second, terminalAt, date);
            assertThat(check.result()).isEqualTo(Denial.LIMIT_EXCEEDED);
            assertThat(check.failure()).contains(TransferSettlementCandidate.Failure.LIMIT_EXCEEDED);
            adapter.finish(guard, second, TransferSettlementCandidate.Outcome.failed(
                    TransferSettlementCandidate.Failure.LIMIT_EXCEEDED), terminalAt, date, check);
        });
        assertThat(evidence(second).state()).isEqualTo(ReservationState.RELEASED);
        assertThat(evidence(second).effects()).isEmpty();
        tx.executeWithoutResult(status -> adapter.finish(adapter.lockCustomer(CUSTOMER), failedBeforeUsageCheck,
                TransferSettlementCandidate.Outcome.failed(TransferSettlementCandidate.Failure.ACCOUNT_INACTIVE),
                terminalAt, date, null));
        assertThat(evidence(failedBeforeUsageCheck).state()).isEqualTo(ReservationState.RELEASED);
        assertUsage(null, date, "100", 1);
        assertUsage(SOURCE, date, "100", 1);
        assertThat(count("limit_transfer_usage_effect")).isEqualTo(2);
    }

    @Test
    void rejectsForeignTransactionRefsAndRollsBackSettlementOnDownstreamFailure() {
        config(CUSTOMER, null, "100", "100");
        config(CUSTOMER, SOURCE, "100", "100");
        var reservation = create(command(CUSTOMER, SOURCE, DESTINATION, "40"));
        Instant terminalAt = dbNow();
        LocalDate date = date(terminalAt);
        var checked = tx.execute(status -> {
            var guard = limits.lockStepUpGuard(CUSTOMER);
            return new Object[]{guard, limits.lockAndCheck(guard, reservation, terminalAt, date)};
        });
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> limits.lockAndCheck(
                (Guard) checked[0], reservation, terminalAt, date))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> limits.settleOrReleaseReservation(
                limits.lockStepUpGuard(CUSTOMER), reservation, TransferLimits.Outcome.COMPLETED,
                terminalAt, date, (Allowed) checked[1]))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limits.lockStepUpGuard(CUSTOMER)).isInstanceOf(IllegalTransactionStateException.class);
        var readOnly = new TransactionTemplate(manager);
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.execute(status -> limits.lockStepUpGuard(CUSTOMER)))
                .isInstanceOf(IllegalTransactionStateException.class);
        var repeatable = new TransactionTemplate(manager);
        repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> repeatable.execute(status -> limits.lockStepUpGuard(CUSTOMER)))
                .hasMessageContaining("READ COMMITTED");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            complete(reservation, terminalAt);
            jdbc.update("INSERT INTO limit_test.business_probe(id) VALUES (42), (42)");
        })).isInstanceOf(DataAccessException.class);
        assertThat(evidence(reservation).state()).isEqualTo(ReservationState.PENDING);
        assertThat(count("limit_transfer_usage_effect")).isZero();
        assertUsage(null, date, "0", 0);
        assertUsage(SOURCE, date, "0", 0);
        complete(reservation, terminalAt);
        assertUsage(null, date, "40", 1);
        assertUsage(SOURCE, date, "40", 1);
    }

    @Test
    void scheduledReplacementUsesOldConfigUpToCutoverAndPreservesUsage() {
        config(CUSTOMER, null, "200", "500");
        config(CUSTOMER, SOURCE, "100", "300");
        var before = create(command(CUSTOMER, SOURCE, DESTINATION, "90"));
        var after = create(command(CUSTOMER, SOURCE, DESTINATION, "40"));
        Instant cutover = dbNow().plusSeconds(60);
        LocalDate date = date(cutover);
        propose(1, SOURCE, "50", "100", cutover, 1);
        assertThat(limits.getUsageSnapshot(CUSTOMER, SOURCE, cutover, date).scopes().getLast().configVersion()).isEqualTo(1);
        apply(1, 1);
        propose(2, SOURCE, "75", "150", cutover.plusSeconds(60), 2);
        assertThatThrownBy(() -> apply(2, 2)).isInstanceOfSatisfying(BankingBusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ROLE_CONFLICT));
        Instant old = cutover.minusNanos(1000);
        assertThat(limits.getUsageSnapshot(CUSTOMER, SOURCE, old, date).scopes().getLast().configVersion()).isEqualTo(1);
        assertThat(limits.getUsageSnapshot(CUSTOMER, SOURCE, cutover, date).scopes().getLast().configVersion()).isEqualTo(2);
        complete(before, old);
        assertThat(evidence(before).effects().getLast().configVersion()).isEqualTo(1);
        tx.executeWithoutResult(status -> {
            var guard = limits.lockStepUpGuard(CUSTOMER);
            assertThat(limits.lockAndCheck(guard, after, cutover, date)).isEqualTo(Denial.LIMIT_EXCEEDED);
        });
        assertUsage(null, date, "90", 1);
        assertUsage(SOURCE, date, "90", 1);
    }

    @Test
    void firstAccountConfigurationStartsCountingAtActivationAndNeverResetsCustomerUsage() {
        config(CUSTOMER, null, "200", "500");
        var before = create(command(CUSTOMER, SOURCE, DESTINATION, "90"));
        var after = create(command(CUSTOMER, SOURCE, DESTINATION, "70"));
        Instant cutover = dbNow().plusSeconds(60);
        LocalDate date = date(cutover);
        propose(1, SOURCE, "80", "80", cutover, 0);
        apply(1, 0);
        complete(before, cutover.minusNanos(1000));
        assertThat(evidence(before).effects()).extracting(TransferLimits.ScopeEffect::scope).containsExactly(Scope.CUSTOMER);
        assertThat(count("limit_usage")).isEqualTo(1);
        complete(after, cutover);
        assertUsage(null, date, "160", 2);
        assertUsage(SOURCE, date, "70", 1);
        assertThat(evidence(after).effects()).extracting(TransferLimits.ScopeEffect::scope)
                .containsExactly(Scope.CUSTOMER, Scope.ACCOUNT);
    }

    @Test
    void adminApprovalWaitsForT2AndSnapshotRemainsCoherent() throws Exception {
        config(CUSTOMER, null, "100", "200");
        config(CUSTOMER, SOURCE, "100", "200");
        var reservation = create(command(CUSTOMER, SOURCE, DESTINATION, "70"));
        propose(1, SOURCE, "90", "160", null, 1);
        var t2Ready = new CompletableFuture<Integer>();
        var adminReady = new CompletableFuture<Integer>();
        var commitT2 = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var t2 = pool.submit(() -> tx.executeWithoutResult(status -> {
                var guard = adapter.lockCustomer(CUSTOMER);
                Instant now = dbNow();
                var checked = adapter.check(guard, reservation, now, date(now));
                assertThat(((Allowed) checked.result()).scopes()).extracting(ScopeUsage::configVersion)
                        .containsExactly(1, 1);
                t2Ready.complete(pid());
                waitFor(commitT2);
                adapter.finish(guard, reservation, TransferSettlementCandidate.Outcome.completed(), now, date(now), checked);
            }));
            try {
                int blocker = t2Ready.get(10, TimeUnit.SECONDS);
                var decision = pool.submit(() -> tx.executeWithoutResult(status -> {
                    adminReady.complete(pid());
                    apply(1, 1);
                }));
                waitForBlocked(adminReady.get(10, TimeUnit.SECONDS), blocker);
                commitT2.countDown();
                t2.get(10, TimeUnit.SECONDS);
                decision.get(10, TimeUnit.SECONDS);
            } finally {
                commitT2.countDown();
            }
        }
        var proof = evidence(reservation);
        assertThat(proof.effects()).extracting(TransferLimits.ScopeEffect::configVersion).containsExactly(1, 1);
        Instant now = dbNow();
        assertThat(limits.getUsageSnapshot(CUSTOMER, SOURCE, now, date(now)).scopes())
                .extracting(ScopeUsage::configVersion).containsExactly(1, 2);
        assertUsage(SOURCE, proof.terminalDate().orElseThrow(), "70", 1);
    }

    @Test
    void recoveryDistinguishesAbsentPendingMismatchPartialAndDatabaseFailure() throws Exception {
        config(CUSTOMER, null, "100", "200");
        config(CUSTOMER, SOURCE, "100", "200");
        var reservation = create(command(CUSTOMER, SOURCE, DESTINATION, "70"));
        var transfer = reservation.transfer();
        var absent = new Reservation(new TransferLink(Long.MAX_VALUE, CUSTOMER, SOURCE, transfer.amount(),
                "VND", transfer.createdAt()), reservation.t1Date(), reservation.policyVersion());
        assertThat(readEvidence(absent)).isInstanceOf(Missing.class);
        assertThat(evidence(reservation).state()).isEqualTo(ReservationState.PENDING);
        assertThat(readEvidence(new Reservation(reservation.transfer(), reservation.t1Date(), "unknown")))
                .isInstanceOf(Mismatch.class);
        assertThat(readEvidence(new Reservation(new TransferLink(transfer.transferId(), CUSTOMER, SECOND_SOURCE,
                transfer.amount(), "VND", transfer.createdAt()), reservation.t1Date(), reservation.policyVersion())))
                .isInstanceOf(Mismatch.class);
        var intent = new TransferRecoveryEvidence.Intent(transfer.transferId(), CUSTOMER, SOURCE, DESTINATION,
                transfer.amount(), "VND", "recovery-it", transfer.createdAt());
        Optional<TransferLimits.Evidence> legacy = tx.execute(status -> adapter.readEvidence(adapter.lockCustomer(CUSTOMER), intent,
                new TransferRecoveryEvidence.Basis(TransferRecoveryEvidence.Mode.LEGACY,
                        reservation.policyVersion(), reservation.t1Date())));
        assertThat(legacy).isEmpty();
        Optional<TransferLimits.Evidence> supported = tx.execute(status ->
                adapter.readEvidence(adapter.lockCustomer(CUSTOMER), intent, new TransferRecoveryEvidence.Basis(
                        TransferRecoveryEvidence.Mode.RESERVATION_REQUIRED, reservation.policyVersion(), reservation.t1Date())));
        assertThat(supported).contains(evidence(reservation));
        assertThatThrownBy(() -> tx.execute(status -> limits.findSettlementEvidence(limits.lockStepUpGuard(CUSTOMER),
                reservation, null))).hasMessageContaining("SYSTEM");

        Instant at = dbNow();
        complete(reservation, at);
        assertThat(evidence(reservation).state()).isEqualTo(ReservationState.SETTLED);
        assertThatThrownBy(() -> jdbc.update("UPDATE banking.limit_transfer_reservation SET state = 'RELEASED'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE banking.limit_transfer_reservation SET policy_version = 'edited'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM banking.limit_transfer_usage_effect"))
                .isInstanceOf(DataAccessException.class);
        try (var connection = migrator(); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM banking.limit_transfer_usage_effect WHERE scope = 'ACCOUNT'");
        }
        assertThat(readEvidence(reservation)).isInstanceOf(Mismatch.class);
        assertUsage(null, date(at), "70", 1);

        setSelectPrivilege("limit_transfer_reservation", false);
        try {
            assertThatThrownBy(() -> readEvidence(reservation)).isInstanceOf(DataAccessException.class);
        } finally {
            setSelectPrivilege("limit_transfer_reservation", true);
        }
    }

    @Test
    void missingCustomerConfigurationAndGappedAccountHistoryFailClosed() {
        assertThatThrownBy(() -> create(command(CUSTOMER, SOURCE, DESTINATION, "10")))
                .hasMessageContaining("CUSTOMER");
        config(CUSTOMER, null, "100", "200");
        config(CUSTOMER, SOURCE, "100", "200");
        observer.update("UPDATE banking.limit_config SET status = 'SUPERSEDED' WHERE account_id = ?", SOURCE);
        assertThatThrownBy(() -> create(command(CUSTOMER, SOURCE, DESTINATION, "10")))
                .hasMessageContaining("ACCOUNT");
        assertThat(count("transfer")).isZero();
    }

    private void reserveYesterday(String amount) {
        Instant yesterday = dbNow().minusSeconds(86_400);
        long transferId = observer.queryForObject("""
                INSERT INTO banking.transfer (reference_no, customer_id, initiated_by_user_id, source_account_id,
                    destination_account_id, amount, correlation_id, created_at)
                VALUES ('yesterday', 11, 11, 21, 22, ?, 'yesterday', ?) RETURNING transfer_id
                """, Long.class, money(amount), timestamp(yesterday));
        observer.update("""
                INSERT INTO banking.limit_transfer_reservation
                    (transfer_id, customer_id, source_account_id, amount, currency, transfer_created_at,
                    gate_at, t1_date, policy_version)
                VALUES (?, 11, 21, ?, 'VND', ?, ?, ?, ?)
                """, transferId, money(amount), timestamp(yesterday), timestamp(yesterday), date(yesterday),
                StepUpPolicy.DEFAULT.version());
    }

    private void config(long customer, Long account, String perTransaction, String daily) {
        observer.update("""
                INSERT INTO banking.limit_config(customer_id, account_id, per_transaction_limit, daily_limit,
                    effective_from, status, version, created_by, approved_by, approved_at)
                VALUES (?, ?, ?, ?, '2020-01-01T00:00:00Z', 'ACTIVE', 1, 1, 2, clock_timestamp())
                """, customer, account, money(perTransaction), money(daily));
    }

    private void propose(long id, Long account, String perTransaction, String daily, Instant cutover, long version) {
        tx.executeWithoutResult(status -> admin.reservePendingChange(new LimitChangeRequest(
                id, 1, CUSTOMER, account, money(perTransaction), money(daily), "VND", cutover, version)));
    }

    private void apply(long id, long version) {
        tx.executeWithoutResult(status -> admin.applyApprovedLimitChange(new ApprovedLimitChange(id, 2, version, "limit-it")));
    }

    private Reservation create(TransferIntentCandidate.Command command) {
        return tx.execute(status -> {
            var guard = adapter.lockCustomer(command.identity().customerId());
            var assessment = adapter.assess(guard, command, StepUpPolicy.DEFAULT);
            return adapter.reserve(guard, command, insert(command), assessment);
        });
    }

    private PendingTransfer insert(TransferIntentCandidate.Command command) {
        return transfers.insertPending(new PendingInsert(command.identity(), command.amount(), command.description(),
                command.correlationId(), command.idempotencyKey(), command.requestHash(), 86_400, "it-v1"));
    }

    private void complete(Reservation reservation, Instant terminalAt) {
        tx.executeWithoutResult(status -> {
            var guard = adapter.lockCustomer(reservation.transfer().customerId());
            var check = adapter.check(guard, reservation, terminalAt, date(terminalAt));
            assertThat(check.failure()).isEmpty();
            adapter.finish(guard, reservation, TransferSettlementCandidate.Outcome.completed(),
                    terminalAt, date(terminalAt), check);
        });
    }

    private TransferLimits.Evidence readEvidence(Reservation expected) {
        return tx.execute(status -> limits.findSettlementEvidence(adapter.lockCustomer(expected.transfer().customerId()),
                expected, RecoveryActor.SYSTEM));
    }

    private Recorded evidence(Reservation expected) {
        return (Recorded) readEvidence(expected);
    }

    private void assertUsage(Long accountId, LocalDate date, String amount, int count) {
        var row = observer.queryForMap("""
                SELECT used_amount, transfer_count FROM banking.limit_usage WHERE customer_id = ?
                  AND account_id IS NOT DISTINCT FROM CAST(? AS bigint) AND usage_date = ?
                """, CUSTOMER, accountId, date);
        assertThat((BigDecimal) row.get("used_amount")).isEqualByComparingTo(amount);
        assertThat(row.get("transfer_count")).isEqualTo(count);
    }

    private int count(String table) {
        return observer.queryForObject("SELECT count(*) FROM banking." + table, Integer.class);
    }

    private int pid() { return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class); }

    private void waitForBlocked(int waiter, int blocker) {
        await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(observer.queryForObject(
                "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, blocker, waiter)));
    }

    private static void waitFor(CountDownLatch barrier) {
        try {
            if (!barrier.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Barrier interrupted", interrupted);
        }
    }

    private Connection migrator() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
    }

    private void setSelectPrivilege(String table, boolean allowed) throws Exception {
        try (var connection = migrator(); var statement = connection.createStatement()) {
            statement.execute((allowed ? "GRANT SELECT ON banking." : "REVOKE SELECT ON banking.")
                    + table + (allowed ? " TO banking_app" : " FROM banking_app"));
        }
    }

    private Instant dbNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
    private static LocalDate date(Instant instant) { return instant.atZone(ZONE).toLocalDate(); }
    private static BigDecimal money(String amount) { return new BigDecimal(amount); }

    private static TransferIntentCandidate.Command command(long customer, long source, long destination, String amount) {
        String key = UUID.randomUUID().toString().replace("-", "");
        return new TransferIntentCandidate.Command(new TransferIdentity(key, customer, customer, source, destination, null),
                new TransferAmount(money(amount), "VND"), null, key, key, "a".repeat(64), null);
    }

    private final class IntentPorts implements TransferIntentCandidate.Ports {
        private Guard guard;
        private TransferLimitAdapter.Assessment assessment;
        private PendingTransfer pending;

        @Override public void lockStepUpGuard(long customerId) { guard = adapter.lockCustomer(customerId); }
        @Override public Optional<IdempotencyDecision.Existing> findExistingOnPrimary(String key) { return Optional.empty(); }
        @Override public TransferIntentCandidate.Assessment evaluateStepUpUnderGuard(TransferIntentCandidate.Command command) {
            assessment = adapter.assess(guard, command, StepUpPolicy.DEFAULT);
            return assessment.candidateView();
        }
        @Override public long insertPendingAndKey(TransferIntentCandidate.Command command) {
            pending = insert(command);
            return pending.id();
        }
        @Override public void reserveForPendingTransfer(TransferIntentCandidate.Command command,
                TransferIntentCandidate.Assessment ignored, long transferId) {
            adapter.reserve(guard, command, pending, assessment);
        }
        @Override public long consumeVerified(TransferIntentCandidate.Command command, UUID challengeRef) {
            throw new AssertionError("No challenge expected");
        }
        @Override public void attachChallenge(long transferId, long challengeId) {
            throw new AssertionError("No challenge expected");
        }
        @Override public void flushBusinessWrites() { }
        @Override public void recordCreated(TransferIdentity identity, TransferAmount amount, String correlationId,
                long transferId) {
            throw new TransferIntentCandidate.AuditWriteException();
        }
    }

    @TestConfiguration
    static class CandidatePersistence {
        @Bean TransferPersistence transferPersistence(JdbcTemplate jdbc) { return new TransferPersistence(jdbc); }
    }
}
