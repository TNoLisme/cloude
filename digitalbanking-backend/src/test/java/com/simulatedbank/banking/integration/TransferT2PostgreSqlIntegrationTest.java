package com.simulatedbank.banking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.simulatedbank.banking.account.api.AccountBalance;
import com.simulatedbank.banking.audit.adapter.out.AuditChainJdbcVerifier;
import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.audit.application.AuditDetailProjection;
import com.simulatedbank.banking.audit.verification.AuditChainVerifier;
import com.simulatedbank.banking.common.config.SafeJsonLogEncoder;
import com.simulatedbank.banking.common.context.SecurityAuditFallbackLog;
import com.simulatedbank.banking.fraud.adapter.out.JdbcFraudEvaluation;
import com.simulatedbank.banking.fraud.domain.FraudRuleEvaluator;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import com.simulatedbank.banking.account.application.AccountNumberProtection;
import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.ledger.config.LedgerPostingConfiguration;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort.ApprovedLimitChange;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort.LimitChangeRequest;
import com.simulatedbank.banking.notification.api.OtpGateway;
import com.simulatedbank.banking.notification.api.NotificationOutboxPort;
import com.simulatedbank.banking.notification.domain.NotificationChannel;
import com.simulatedbank.banking.notification.domain.TransferNotice;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import com.simulatedbank.banking.transfer.adapter.out.integration.TransferCreateAdapters.PostCommitSettlement;
import com.simulatedbank.banking.transfer.adapter.out.integration.TransferSettlementHandoff;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferSettlementStore;
import com.simulatedbank.banking.transfer.application.AccountOwnershipPort;
import com.simulatedbank.banking.transfer.application.FraudFlagReadPort;
import com.simulatedbank.banking.transfer.application.TransferNotifications;
import com.simulatedbank.banking.transfer.application.TransferReadPort;
import com.simulatedbank.banking.transfer.application.TransferSettlementCandidate.AttemptFence;
import com.simulatedbank.banking.fraud.api.FraudEvaluation;
import com.simulatedbank.banking.transfer.application.TransferStaffScope;
import com.simulatedbank.transfercandidate.web.TransferCreateIntegrationConfiguration;
import com.simulatedbank.transfercandidate.web.TransferHttpConfiguration;
import com.simulatedbank.transfercandidate.web.TransferSettlementIntegrationConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=24",
        "app.security.step-up.single-amount-vnd=11000000", "app.security.step-up.daily-amount-vnd=50000000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import({TransferHttpConfiguration.class, TransferCreateIntegrationConfiguration.class,
        TransferSettlementIntegrationConfiguration.class, LedgerPostingConfiguration.class,
        TransferT2PostgreSqlIntegrationTest.Fixtures.class})
class TransferT2PostgreSqlIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("limit/init-roles.sql")
            .withCommand("postgres", "-c", "log_statement=all", "-c", "log_parameter_max_length=0",
                    "-c", "log_parameter_max_length_on_error=0", "-c", "log_line_prefix=%p ");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "banking_app");
        registry.add("spring.datasource.password", () -> "test_app");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "banking_migrator");
        registry.add("spring.flyway.password", () -> "test_migrator");
    }

    @Autowired MockMvc mvc;
    @Autowired DataSource source;
    @Autowired Flyway flyway;
    @Autowired AccountNumberProtection numbers;
    @Autowired TransferSettlementHandoff handoff;
    @Autowired LimitConfigMutationPort adminLimits;
    @Autowired JdbcTemplate jdbc;
    @Autowired Boundaries seams;
    @MockitoSpyBean AccountBalance accounts;
    @MockitoSpyBean FraudEvaluation fraud;
    @MockitoSpyBean FraudRuleEvaluator fraudRules;
    @MockitoSpyBean MeterRegistry meters;
    @MockitoSpyBean AuditModuleApi audit;
    @MockitoSpyBean TransferSettlementStore store;
    @MockitoSpyBean AppProperties properties;
    @MockitoSpyBean PlatformTransactionManager transactions;
    private JdbcTemplate observer;
    private static final AtomicInteger RULE_VERSIONS = new AtomicInteger();
    private static final AtomicInteger NUMBERS = new AtomicInteger(1000);

    @BeforeEach
    void setUp() {
        PostgreSqlIntegrationTestSupport.assertContainerDataSource(source, POSTGRES);
        observer = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "banking_migrator", "test_migrator"));
        seams.afterIntent = id -> { };
        seams.beforeMoney = id -> { };
        FraudEvaluation target = AopTestUtils.getUltimateTargetObject(fraud);
        doAnswer(call -> {
            FraudEvaluation.Facts facts = call.getArgument(0);
            seams.beforeMoney.accept(facts.transferId());
            return call.callRealMethod();
        }).when(target).evaluateTransfer(any(FraudEvaluation.Facts.class));
    }

    @Test
    void commitsMoneyLedgerBothScopesAuditAndOutboxThenReplaysExactBody() throws Exception {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("21");
        Actor a = actor();
        Actor b = actor();
        addAccountConfig(a, a.account, "2000.00", "2500.00", "2020-01-01T00:00:00Z");
        observer.update("UPDATE banking.account SET ledger_balance=90010000 WHERE account_id=?", a.account);
        observer.update("UPDATE banking.account SET ledger_balance=90020000 WHERE account_id=?", b.account);
        Order order = order(a, b, "2000.00");
        var result = mvc.perform(post(a, order, "original-t2"))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.amount").value("2000.00"))
                .andExpect(jsonPath("$.sourceAccount.accountNumberMasked").exists())
                .andExpect(jsonPath("$.correlationId").value("original-t2"))
                .andReturn().getResponse();
        long id = id(order);
        assertBalance(a.account, "89998000.00", "90008000.00");
        assertBalance(b.account, "90002000.00", "90022000.00");
        assertThat(observer.queryForList("""
                SELECT entry_seq || ':' || entry_type || ':' || account_id || ':' || amount || ':' || balance_after
                FROM banking.ledger_entry WHERE transfer_id=? ORDER BY entry_seq
                """, String.class, id)).containsExactly("1:DEBIT:" + a.account + ":2000.00:90008000.00",
                "2:CREDIT:" + b.account + ":2000.00:90022000.00");
        assertThat(observer.queryForList("""
                SELECT scope || ':' || config_version || ':' || amount_delta || ':' || count_delta
                FROM banking.limit_transfer_usage_effect WHERE transfer_id=? ORDER BY scope
                """, String.class, id)).containsExactly("ACCOUNT:1:2000.00:1", "CUSTOMER:1:2000.00:1");
        assertTerminal(id, true, 2);
        assertThat(flagCodes(id)).isEmpty();
        mvc.perform(post(a, order, "replayed-t2")).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("X-Correlation-ID", "replayed-t2"))
                .andExpect(jsonPath("$.correlationId").value("original-t2"))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).isEqualTo(result.getContentAsString()));
        assertTerminal(id, true, 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNT_INACTIVE", "ACCOUNT_NOT_FOUND", "INSUFFICIENT_BALANCE", "LIMIT_EXCEEDED"})
    void commitsBusinessRejectionAfterT1(String failure) throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order order = order(a, b, "2000.01");
        seams.afterIntent = id -> {
            assertPending(id, "IDLE");
            switch (failure) {
                case "ACCOUNT_INACTIVE" -> observer.update("UPDATE banking.account SET status='LOCKED' WHERE account_id=?", b.account);
                case "ACCOUNT_NOT_FOUND" -> {
                    AccountBalance target = AopTestUtils.getUltimateTargetObject(accounts);
                    doReturn(Optional.empty()).when(target).findAndLockTransferAccounts(a.account, b.account);
                }
                case "INSUFFICIENT_BALANCE" -> balance(a.account, "2000.00");
                case "LIMIT_EXCEEDED" -> addAccountConfig(a, a.account, "2000.00", "3000.00", "2020-01-01T00:00:00Z");
                default -> throw new AssertionError(failure);
            }
        };
        var body = mvc.perform(post(a, order, "failed-t2")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.transferStatus").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value(failure))
                .andExpect(jsonPath("$.code").value(failure.startsWith("ACCOUNT_") ? "TRANSFER_FAILED" : failure))
                .andReturn().getResponse().getContentAsString();
        assertBalance(a.account, failure.equals("INSUFFICIENT_BALANCE") ? "2000.00" : "90000000.00");
        assertBalance(b.account, "90000000.00");
        assertTerminal(id(order), false, 0);
        mvc.perform(post(a, order, "replayed-failed")).andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).isEqualTo(body));
        assertTerminal(id(order), false, 0);
    }

    @Test
    void creditZeroRowsRollsBackMoneySavepointAndCommitsProcessingFailure() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order order = order(a, b, "10000000.00");
        // BEFORE UPDATE skips only the credit statement. Debit was already applied on the same connection.
        try (var fault = trigger("account", "BEFORE UPDATE", "NEW.account_id=" + b.account,
                """
                IF (SELECT available_balance FROM banking.account WHERE account_id=%d) <> 80000000.00 THEN
                    RAISE EXCEPTION 'Expected debit before credit';
                END IF;
                RETURN NULL;
                """.formatted(a.account))) {
            mvc.perform(post(a, order, "credit-zero")).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("TRANSFER_FAILED"))
                    .andExpect(jsonPath("$.failureCode").value("PROCESSING_ERROR"));
        }
        assertBalance(a.account, "90000000.00");
        assertBalance(b.account, "90000000.00");
        assertTerminal(id(order), false, 0);
        assertThat(flagCodes(id(order))).containsExactly("LARGE_VALUE_TRANSFER:1");
        assertAuditGroup(id(order), "TRANSFER_FAILED", "PROCESSING_ERROR");
        assertNoUsage(a);
    }

    @ParameterizedTest
    @ValueSource(strings = {"credit", "ledgerDuplicate", "ledgerReadback", "usage", "terminal", "outbox", "audit"})
    void rollsBackAllT2WritesAfterInjectedFailure(String phase) throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order order = order(a, b, "2500.00");
        String reject = "RAISE EXCEPTION 'INT06 injected failure' USING ERRCODE='23514';";
        try (var fault = switch (phase) {
            case "credit" -> trigger("account", "BEFORE UPDATE", "NEW.account_id=" + b.account, reject);
            case "ledgerDuplicate" -> trigger("ledger_entry", "BEFORE INSERT", "NEW.entry_seq=2",
                    "NEW.entry_seq := 1;");
            case "ledgerReadback" -> trigger("ledger_entry", "BEFORE INSERT", "NEW.entry_seq=2",
                    "NEW.balance_after := NEW.balance_after + 1;");
            case "usage" -> trigger("limit_transfer_usage_effect", "BEFORE INSERT", "true", reject);
            case "terminal" -> trigger("idempotency_record", "BEFORE UPDATE", "NEW.status='COMPLETED'", reject);
            case "outbox" -> trigger("notification", "BEFORE INSERT", "NEW.notification_type='TRANSFER_RESULT'", reject);
            case "audit" -> trigger("audit_log", "BEFORE INSERT", "NEW.action='TRANSFER_COMPLETED'", reject);
            default -> throw new AssertionError(phase);
        }) {
            mvc.perform(post(a, order, "rollback-" + phase)).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(phase.equals("audit") ? "AUDIT_WRITE_FAILED" : "INTERNAL_ERROR"));
        }
        assertBalance(a.account, "90000000.00");
        assertBalance(b.account, "90000000.00");
        assertPending(id(order), "CLAIMED");
        assertNoUsage(a);
        mvc.perform(post(a, order, "retry-rollback")).andExpect(status().isConflict())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void terminalUsesOneClockAcrossCutoverAndRejectsClockRegression() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Instant midnight = observer.queryForObject("""
                SELECT (date_trunc('day', clock_timestamp() AT TIME ZONE 'Asia/Ho_Chi_Minh') + interval '2 days')
                    AT TIME ZONE 'Asia/Ho_Chi_Minh'
                """, OffsetDateTime.class).toInstant();
        Order before = order(a, b, "2000.00");
        Order after = order(a, b, "2000.00");
        seams.afterIntent = id -> {
            approveAccountCutover(a, midnight);
            seams.afterIntent = ignored -> { };
        };
        doReturn(midnight.minusNanos(1000), midnight, Instant.EPOCH).when(store).primaryTime();
        mvc.perform(post(a, before, "time-before")).andExpect(status().isCreated());
        assertTerminal(id(before), true, 1);
        mvc.perform(post(a, after, "time-after")).andExpect(status().isCreated());
        assertTerminal(id(after), true, 2);
        assertThat(observer.queryForList("""
                SELECT terminal_date FROM banking.limit_transfer_reservation WHERE transfer_id IN (?,?)
                ORDER BY terminal_at
                """, java.time.LocalDate.class, id(before), id(after)))
                .containsExactly(midnight.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().minusDays(1),
                        midnight.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate());
        var backwards = order(a, b, "2000.00");
        mvc.perform(post(a, backwards, "time-backwards")).andExpect(status().isInternalServerError());
        assertPending(id(backwards), "CLAIMED");
        assertBalance(a.account, "89996000.00");
        assertBalance(b.account, "90004000.00");
        org.mockito.Mockito.verify(store, org.mockito.Mockito.times(3)).primaryTime();
    }

    @Test
    void invalidTerminalTtlRollsBackAndSmallerTtlRetainsInitialExpiry() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order invalid = order(a, b, "2000.00");
        var config = properties.transfer();
        doReturn(withTtl(config, 23)).when(properties).transfer();
        mvc.perform(post(a, invalid, "ttl-invalid")).andExpect(status().isInternalServerError());
        assertPending(id(invalid), "CLAIMED");
        assertNoUsage(a);
        assertBalance(a.account, "90000000.00");

        doReturn(withTtl(config, 24)).when(properties).transfer();
        seams.afterIntent = id -> observer.update("""
                UPDATE banking.idempotency_record SET expires_at=created_at+interval '48 hours',
                    ttl_t1_seconds=172800, ttl_t1_config_version='fixture-48h' WHERE transfer_id=?
                """, id);
        Order decreased = order(a, b, "2000.00");
        mvc.perform(post(a, decreased, "ttl-decreased")).andExpect(status().isCreated());
        assertTerminal(id(decreased), true, 1);
        assertThat(count("""
                SELECT count(*) FROM banking.idempotency_record WHERE transfer_id=?
                    AND expires_at=created_at+interval '48 hours' AND ttl_terminal_seconds=86400
                    AND ttl_terminal_config_version='transfer-ttl-hours-24'
                """, id(decreased))).isEqualTo(1);
    }

    private static AppProperties.Transfer withTtl(AppProperties.Transfer c, int ttl) {
        return new AppProperties.Transfer(c.minAmountVnd(), c.maxAmountVnd(), c.pinOtpThresholdVnd(), c.dailyCapVnd(),
                ttl, c.t2LockTimeoutMs(), c.t2StatementTimeoutMs(), c.fraudEvalTimeoutMs());
    }

    @Test
    void retriesWholeTransactionOnlyAfterConfirmedDeadlockRollback() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order order = order(a, b, "2000.00");
        var attempts = new AtomicInteger();
        seams.beforeMoney = id -> attempts.incrementAndGet();
        observer.execute("CREATE SEQUENCE banking.int06_attempt");
        observer.execute("GRANT USAGE, SELECT ON SEQUENCE banking.int06_attempt TO banking_app");
        try (var fault = trigger("account", "BEFORE UPDATE", "NEW.account_id=" + b.account, """
                IF nextval('banking.int06_attempt') = 1 THEN
                    RAISE EXCEPTION 'INT06 injected deadlock' USING ERRCODE='40P01';
                END IF;
                """)) {
            mvc.perform(post(a, order, "deadlock-retry")).andExpect(status().isCreated());
        } finally {
            observer.execute("DROP SEQUENCE banking.int06_attempt");
        }
        assertThat(attempts).hasValue(2);
        assertBalance(a.account, "89998000.00");
        assertBalance(b.account, "90002000.00");
        assertTerminal(id(order), true, 1);
    }

    @Test
    void accountLockTimeoutIsBoundedAndLeavesNonterminalIntent() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order order = order(a, b, "2000.00");
        long transfer = prepare(a, order);
        try (var connection = observer.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT account_id FROM banking.account WHERE account_id=? FOR UPDATE")) {
                lock.setLong(1, b.account);
                lock.executeQuery().close();
            }
            Instant started = Instant.now();
            assertThatThrownBy(() -> handoff.settle(transfer)).isInstanceOfSatisfying(BankingBusinessException.class,
                    ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
            assertThat(Duration.between(started, Instant.now())).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(8));
            connection.rollback();
        }
        assertPending(transfer, "CLAIMED");
        assertBalance(a.account, "90000000.00");
        assertNoUsage(a);
    }

    @Test
    void readsExactDurableResponseAfterCommitAcknowledgementFails() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order order = order(a, b, "2000.00");
        var loseAcknowledgement = new java.util.concurrent.atomic.AtomicBoolean();
        seams.beforeMoney = id -> loseAcknowledgement.set(true);
        doAnswer(invocation -> {
            boolean lose = invocation.<TransactionStatus>getArgument(0).isNewTransaction()
                    && loseAcknowledgement.getAndSet(false);
            Object result = invocation.callRealMethod();
            if (lose) throw new TransactionSystemException("INT06 commit acknowledgement lost");
            return result;
        }).when(transactions).commit(any());
        var first = mvc.perform(post(a, order, "unknown-commit")).andExpect(status().isCreated()).andReturn();
        assertTerminal(id(order), true, 1);
        mvc.perform(post(a, order, "unknown-replay")).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));
        assertBalance(a.account, "89998000.00");
        assertBalance(b.account, "90002000.00");
        assertTerminal(id(order), true, 1);
    }

    @Test
    void connectionLostBeforeCommitLeavesNoOutcomeEvidenceAndDoesNotRetry() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order order = order(a, b, "2000.00");
        var backend = new AtomicInteger();
        var attempts = new AtomicInteger();
        seams.beforeMoney = id -> {
            attempts.incrementAndGet();
            backend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
        };
        var control = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        doAnswer(invocation -> {
            if (invocation.<TransactionStatus>getArgument(0).isNewTransaction()) {
                int pid = backend.getAndSet(0);
                if (pid != 0) assertThat(control.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, pid)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(transactions).commit(any());
        mvc.perform(post(a, order, "connection-lost")).andExpect(status().isServiceUnavailable());
        assertPending(id(order), "CLAIMED");
        assertThat(attempts).hasValue(1);
        assertBalance(a.account, "90000000.00");
        assertBalance(b.account, "90000000.00");
        assertNoUsage(a);
        mvc.perform(post(a, order, "connection-lost-retry")).andExpect(status().isConflict());
    }

    @Test
    void refusesToClaimBusyTransferRow() throws Exception {
        Actor a = actor();
        Actor b = actor();
        long id = prepare(a, order(a, b, "2000.00"));
        try (var connection = observer.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT transfer_id FROM banking.transfer WHERE transfer_id=? FOR UPDATE")) {
                lock.setLong(1, id);
                lock.executeQuery().close();
            }
            assertThatThrownBy(() -> handoff.settle(id)).isInstanceOfSatisfying(BankingBusinessException.class,
                    ex -> assertThat(ex.code()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS));
            assertBalance(a.account, "90000000.00");
            assertPending(id, "IDLE");
            connection.rollback();
        }
        assertThat(handoff.settle(id).committed().response().httpStatus()).isEqualTo(201);
        assertTerminal(id, true, 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 20})
    void twentyExecutorsObserveAuthoritativeBalanceUnderLock(int funded) throws Exception {
        Actor a = actor();
        Actor b = actor();
        var pending = new ArrayList<Long>();
        for (int index = 0; index < 20; index++) pending.add(prepare(a, order(a, b, "100000.00")));
        balance(a.account, Integer.toString(funded * 100_000));
        var outcomes = race(pending);
        assertThat(outcomes.stream().filter(s -> s == 201).count()).isEqualTo(funded);
        assertThat(outcomes.stream().filter(s -> s == 422).count()).isEqualTo(20 - funded);
        assertBalance(a.account, "0.00");
        assertBalance(b.account, new BigDecimal("90000000.00").add(BigDecimal.valueOf(funded * 100_000L)).toPlainString());
        assertThat(count("""
                SELECT count(*) FROM banking.transfer WHERE customer_id=? AND
                     (status='COMPLETED' OR (status='FAILED' AND failure_code='INSUFFICIENT_BALANCE'))
                """, a.customer)).isEqualTo(20);
        assertThat(count("""
                SELECT count(*) FROM banking.ledger_entry WHERE transfer_id IN
                    (SELECT transfer_id FROM banking.transfer WHERE customer_id=?)
                """, a.customer)).isEqualTo(2L * funded);
        assertThat(count("""
                SELECT transfer_count FROM banking.limit_usage WHERE customer_id=? AND account_id IS NULL
                """, a.customer)).isEqualTo(funded);
        assertThat(count("""
                SELECT count(*) FROM (SELECT transfer_id FROM banking.ledger_entry WHERE transfer_id IN
                    (SELECT transfer_id FROM banking.transfer WHERE customer_id=?) GROUP BY transfer_id
                    HAVING SUM(CASE entry_type WHEN 'DEBIT' THEN -amount ELSE amount END) <> 0) unbalanced
                """, a.customer)).isZero();
    }

    @Test
    void oppositePairsAndIndependentTransfersSettleTogether() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Actor c = actor();
        Actor d = actor();
        List<Long> transfers = List.of(prepare(a, order(a, b, "2000.00")),
                prepare(b, order(b, a, "3000.00")), prepare(c, order(c, d, "4000.00")));
        assertThat(race(transfers)).containsExactly(201, 201, 201);
        transfers.forEach(id -> assertTerminal(id, true, 1));
        assertBalance(a.account, "90001000.00");
        assertBalance(b.account, "89999000.00");
        assertBalance(c.account, "89996000.00");
        assertBalance(d.account, "90004000.00");
    }

    @Test
    void differentSourceAccountsCompeteForSharedCustomerDailyBudget() throws Exception {
        Actor a = actor();
        Actor second = account(a.user, a.customer, a.checker, a.token);
        Actor b = actor();
        long first = prepare(a, order(a, b, "2000.00"));
        long other = prepare(second, order(second, b, "2000.01"));
        observer.update("""
                UPDATE banking.limit_config SET per_transaction_limit=3000, daily_limit=4000
                WHERE customer_id=? AND account_id IS NULL
                """, a.customer);
        var result = race(List.of(first, other));
        assertThat(result).containsExactlyInAnyOrder(201, 422);
        assertTerminal(first, result.get(0) == 201, result.get(0) == 201 ? 1 : 0);
        assertTerminal(other, result.get(1) == 201, result.get(1) == 201 ? 1 : 0);
        assertThat(count("""
                SELECT transfer_count FROM banking.limit_usage WHERE customer_id=? AND account_id IS NULL
                """, a.customer)).isEqualTo(1);
    }

    @Test
    void duplicateExecutorAndHttpRetryNeverWriteTwice() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order order = order(a, b, "2000.00");
        long id = prepare(a, order);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        seams.beforeMoney = ignored -> {
            locked.countDown();
            try {
                assertThat(resume.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> handoff.settle(id));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertPending(id, "CLAIMED");
                mvc.perform(post(a, order, "while-pending")).andExpect(status().isConflict())
                        .andExpect(header().exists("Retry-After"));
                var second = pool.submit(() -> handoff.settle(id));
                await().atMost(Duration.ofSeconds(1)).until(() -> count("""
                        SELECT count(*) FROM pg_stat_activity WHERE usename='banking_app'
                          AND cardinality(pg_blocking_pids(pid)) > 0
                        """) >= 1);
                resume.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS).committed().response().httpStatus()).isEqualTo(201);
                assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                        .cause().isInstanceOfSatisfying(BankingBusinessException.class,
                                ex -> assertThat(ex.code()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS));
            } finally {
                resume.countDown();
            }
        }
        assertTerminal(id, true, 1);
    }

    @Test
    void staleFenceAndExpiredLeaseAreRejectedAndRecoveryJoinsCaller() throws Exception {
        Actor a = actor();
        Actor b = actor();
        long id = prepare(a, order(a, b, "2000.00"));
        UUID token = UUID.randomUUID();
        observer.update("""
                UPDATE banking.transfer SET attempt_state='CLAIMED', attempt_epoch=1, attempt_token=?,
                       attempt_owner='TEST', attempt_lease_expires_at=clock_timestamp()-interval '1 second'
                WHERE transfer_id=?
                """, token, id);
        var context = store.read(id).context();
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.execute(s -> handoff.settleInRecoveryTransaction(context, new AttemptFence(1, token))))
                .isInstanceOf(IllegalStateException.class);
        observer.update("""
                UPDATE banking.transfer SET attempt_lease_expires_at=clock_timestamp()+interval '1 minute'
                WHERE transfer_id=?
                """, id);
        for (var stale : List.of(new AttemptFence(2, token), new AttemptFence(1, UUID.randomUUID()))) {
            assertThatThrownBy(() -> tx.execute(s -> handoff.settleInRecoveryTransaction(context, stale)))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertPending(id, "CLAIMED");
        tx.executeWithoutResult(s -> {
            assertThat(handoff.settleInRecoveryTransaction(context, new AttemptFence(1, token)).failure()).isEmpty();
            s.setRollbackOnly();
        });
        assertPending(id, "CLAIMED");
        assertBalance(a.account, "90000000.00");
        assertNoUsage(a);
        tx.execute(s -> handoff.settleInRecoveryTransaction(context, new AttemptFence(1, token)));
        assertTerminal(id, true, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9999999.99", "10000000.00", "10000000.01"})
    void fraudLargeValueUsesInclusiveDecimalThreshold(String amount) throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order order = order(a, b, amount);
        String body = mvc.perform(post(a, order, "fraud-large")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = id(order);
        assertTerminal(id, true, 1);
        assertThat(flagCodes(id)).containsExactlyElementsOf(amount.equals("9999999.99")
                ? List.of() : List.of("LARGE_VALUE_TRANSFER:1"));
        if (!amount.equals("9999999.99")) {
            String state = observer.queryForObject("""
                    SELECT after_state::text FROM banking.audit_log l JOIN banking.fraud_flag f
                        ON l.entity_id=f.flag_id::text WHERE l.action='FRAUD_FLAG_CREATED' AND f.transfer_id=?
                    """, String.class, id);
            assertThat(AuditDetailProjection.projectSnapshot("FRAUD_FLAG", state))
                    .containsEntry("ruleCode", "LARGE_VALUE_TRANSFER")
                    .containsEntry("ruleVersion", 1L);
        }
        assertAuditGroup(id, "TRANSFER_COMPLETED", null);
        seams.beforeMoney = ignored -> { throw new AssertionError("Terminal replay evaluated Fraud again"); };
        mvc.perform(post(a, order, "fraud-large-replay")).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo(body));
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 5, 6})
    void fraudFrequencyCountsCommittedStatusesAndExactWindowBounds(int observed) throws Exception {
        Actor a = actor();
        Actor b = actor();
        Actor other = actor();
        Order order = order(a, b, "2000.00");
        seams.afterIntent = id -> {
            Instant created = store.read(id).intent().createdAt();
            Instant end = created.plusSeconds(1);
            history(a, b, end.minusSeconds(600), "PENDING");
            history(a, b, end.plusSeconds(1), "PENDING");
            history(other, b, end, "COMPLETED");
            history(a, b, end, "PENDING");
            history(a, b, end.minusSeconds(599), "FAILED");
            history(a, b, end.minusSeconds(2), "COMPLETED");
            for (int ix = 4; ix < observed; ix++) history(a, b, end.minusSeconds(3 + ix), "PENDING");
            doReturn(end).when(store).primaryTime();
        };
        mvc.perform(post(a, order, "fraud-window")).andExpect(status().isCreated());
        long id = id(order);
        assertThat(flagCodes(id)).containsExactlyElementsOf(observed == 6
                ? List.of("HIGH_FREQUENCY_TRANSFER:1") : List.of());
        if (observed == 6) {
            assertThat(count("""
                    SELECT count(*) FROM banking.fraud_flag WHERE transfer_id=?
                      AND matched_value->>'count'='6' AND matched_value->>'thresholdCount'='5'
                      AND matched_value->>'windowSeconds'='600'
                      AND (matched_value->>'windowEndInclusive')::timestamptz=created_at
                      AND (matched_value->>'windowStartExclusive')::timestamptz=created_at-interval '600 seconds'
                    """, id)).isEqualTo(1);
        }
        assertAuditGroup(id, "TRANSFER_COMPLETED", null);
    }

    @Test
    void fraudCutoverUsesRuleVersionAndWindowAcrossVietnamMidnight() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order order = order(a, b, "2000.00");
        long id = prepare(a, order);
        Instant created = store.read(id).intent().createdAt();
        Instant end = created.atOffset(java.time.ZoneOffset.ofHours(7)).toLocalDate().plusDays(2)
                .atStartOfDay().toInstant(java.time.ZoneOffset.ofHours(7)).plusSeconds(5);
        for (int offset : List.of(1, 20, 30, 100, 300, 590)) history(a, b, end.minusSeconds(offset), "PENDING");
        history(a, b, end.minusSeconds(601), "PENDING");
        int version = RULE_VERSIONS.incrementAndGet();
        Instant cutover = end.minusSeconds(30);
        String code = "UNUSUAL_PATTERN_TRANSFER";
        insertRule(code, version, "UNUSUAL_PATTERN", "2000.00", 1, 10, false, cutover);
        observer.update("UPDATE banking.fraud_rule_config SET effective_to=? WHERE rule_code=? AND version=?",
                utc(end), code, version);
        insertRule(code, version + 100, "UNUSUAL_PATTERN", "2000.00", 1, 60, false, end);
        doReturn(end).when(store).primaryTime();
        try {
            assertThat(handoff.settle(id).committed().response().httpStatus()).isEqualTo(201);
            assertThat(flagCodes(id)).containsExactly("HIGH_FREQUENCY_TRANSFER:1", code + ":" + (version + 100));
            assertThat(observer.queryForList("""
                    SELECT matched_value->>'count' FROM banking.fraud_flag WHERE transfer_id=? ORDER BY flag_id
                    """, String.class, id)).containsExactly("6", "3");
            assertAuditGroup(id, "TRANSFER_COMPLETED", null);
        } finally {
            disableRule(code, version);
            disableRule(code, version + 100);
        }
    }

    @Test
    void fraudOverlapFailsClosedAndLeavesDurableIntentPending() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order order = order(a, b, "2000.00");
        int version = RULE_VERSIONS.incrementAndGet();
        String code = "UNUSUAL_PATTERN_TRANSFER";
        Instant start = Instant.now().minusSeconds(60);
        insertRule(code, version, "UNUSUAL_PATTERN", "2000", 1, 60, false, start);
        observer.update("UPDATE banking.fraud_rule_config SET effective_to=? WHERE rule_code=? AND version=?",
                utc(start.plusSeconds(180)), code, version);
        insertRule(code, version + 100, "UNUSUAL_PATTERN", "2000", 1, 60, false, start);
        try {
            mvc.perform(post(a, order, "fraud-overlap")).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
            assertPending(id(order), "CLAIMED");
            assertThat(flagCodes(id(order))).isEmpty();
        } finally {
            disableRule(code, version);
            disableRule(code, version + 100);
        }
    }

    @Test
    void fraudExplicitBlockReleasesReservationAndReplays422() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order order = order(a, b, "2000.00");
        history(a, b, Instant.now(), "PENDING");
        int version = RULE_VERSIONS.incrementAndGet();
        insertRule("UNUSUAL_PATTERN_TRANSFER", version, "UNUSUAL_PATTERN", "2000", 1, 600, true,
                Instant.now().minusSeconds(60));
        try {
            var body = mvc.perform(post(a, order, "fraud-block")).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("FRAUD_BLOCKED"))
                    .andExpect(jsonPath("$.failureCode").value("FRAUD_BLOCKED"))
                    .andExpect(jsonPath("$.transferStatus").value("FAILED"))
                    .andExpect(jsonPath("$.reference").isString()).andReturn().getResponse().getContentAsString();
            long id = id(order);
            assertBalance(a.account, "90000000.00");
            assertBalance(b.account, "90000000.00");
            assertNoUsage(a);
            assertTerminal(id, false, 0);
            assertThat(flagCodes(id)).containsExactly("UNUSUAL_PATTERN_TRANSFER:" + version);
            assertAuditGroup(id, "TRANSFER_FAILED", "FRAUD_BLOCKED");
            seams.beforeMoney = ignored -> { throw new AssertionError("BLOCK replay evaluated Fraud again"); };
            mvc.perform(post(a, order, "fraud-block-replay")).andExpect(status().isUnprocessableEntity())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo(body));
            assertAuditGroup(id, "TRANSFER_FAILED", "FRAUD_BLOCKED");
        } finally {
            disableRule("UNUSUAL_PATTERN_TRANSFER", version);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fraudSqlErrorAfterFirstFlagRecoversEvenIfMetricsFail(boolean metricsFail) throws Exception {
        Actor a = actor();
        Actor b = actor();
        fiveRecent(a, b);
        Order order = order(a, b, "10000000.00");
        var counter = meters.counter("banking.fraud.engine.error");
        double before = counter.count();
        if (metricsFail) doAnswer(call -> { throw new IllegalStateException("Metrics unavailable"); })
                .when(meters).counter("banking.fraud.engine.error");
        Logger logger = (Logger) LoggerFactory.getLogger(JdbcFraudEvaluation.class);
        var logs = capture(logger);
        try (var fault = trigger("fraud_flag", "BEFORE INSERT", "NEW.rule_code='LARGE_VALUE_TRANSFER'", """
                IF NOT EXISTS (SELECT 1 FROM banking.fraud_flag WHERE transfer_id=NEW.transfer_id) THEN
                    RAISE EXCEPTION 'Expected first Fraud flag' USING ERRCODE='23514';
                END IF;
                RAISE EXCEPTION 'Synthetic engine error' USING ERRCODE='P0001';
                """)) {
            mvc.perform(post(a, order, "fraud-recovered")).andExpect(status().isCreated());
            long id = id(order);
            assertThat(flagCodes(id)).isEmpty();
            assertTerminal(id, true, 1);
            assertBalance(a.account, "80000000.00");
            assertBalance(b.account, "100000000.00");
            assertAuditGroup(id, "TRANSFER_COMPLETED", null);
            assertThat(counter.count() - before).isEqualTo(metricsFail ? 0.0 : 1.0);
            assertThat(encoded(logs)).singleElement().satisfies(log -> assertThat(log)
                    .contains("FRAUD_ENGINE_ERROR", "\"category\":\"ENGINE_SQL\"", "\"fallback\":\"ALLOW\"",
                            "\"correlation_id\":\"fraud-recovered\"")
                    .doesNotContain("Synthetic engine error", order.key, a.token));
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void fraudBudgetExpirationRollsBackFlagsWhileT2StillHasTime() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order order = order(a, b, "10000000.00");
        Logger logger = (Logger) LoggerFactory.getLogger(JdbcFraudEvaluation.class);
        var logs = capture(logger);
        try (var fault = trigger("fraud_flag", "BEFORE INSERT", "true", "PERFORM pg_sleep(1.2);")) {
            mvc.perform(post(a, order, "fraud-budget")).andExpect(status().isCreated());
            assertThat(flagCodes(id(order))).isEmpty();
            assertTerminal(id(order), true, 1);
            assertAuditGroup(id(order), "TRANSFER_COMPLETED", null);
            assertThat(encoded(logs)).singleElement().asString().contains("EVALUATION_BUDGET", "FRAUD_ENGINE_ERROR");
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void fraudCountAndT1UseTheSameCustomerGuardAcrossOverlappingTransactions() throws Exception {
        Actor a = actor();
        Actor b = actor();
        for (int i = 1; i <= 4; i++) history(a, b, Instant.now().minusSeconds(i), "PENDING");
        Order first = order(a, b, "2000.00");
        long firstId = prepare(a, first);
        Order second = order(a, b, "2000.00");
        var resume = new CountDownLatch(1);
        AtomicInteger holdingBackend = new AtomicInteger();
        seams.beforeMoney = id -> {
            if (id == firstId) {
                holdingBackend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                await().atMost(Duration.ofSeconds(3)).until(() -> resume.getCount() == 0);
            }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var firstResult = pool.submit(() -> handoff.settle(firstId).committed().response().httpStatus());
            try {
                await().atMost(Duration.ofSeconds(3)).until(() -> holdingBackend.get() > 0);
                var secondResult = pool.submit(() ->
                        mvc.perform(post(a, second, "fraud-guard-race")).andReturn().getResponse().getStatus());
                await().atMost(Duration.ofMillis(1500)).until(() -> count("""
                        SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted
                          AND ? = ANY(pg_blocking_pids(pid))
                        """, holdingBackend.get()) > 0);
                assertThat(count("""
                        SELECT count(*) FROM banking.fraud_transfer_intent_history WHERE customer_id=?
                        """, a.customer)).isEqualTo(5);
                resume.countDown();
                assertThat(secondResult.get(8, TimeUnit.SECONDS)).isEqualTo(201);
                assertThat(firstResult.get(8, TimeUnit.SECONDS)).isEqualTo(201);
            } finally {
                resume.countDown();
            }
        }
        assertThat(flagCodes(firstId)).isEmpty();
        assertThat(flagCodes(id(second))).containsExactly("HIGH_FREQUENCY_TRANSFER:1");
        assertTerminal(firstId, true, 1);
        assertTerminal(id(second), true, 1);
        assertChain();
    }

    @Test
    void fraudRollbackOnlyCannotFailOpen() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order order = order(a, b, "2000.00");
        doAnswer(call -> {
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw new org.springframework.jdbc.UncategorizedSQLException("fixture", null,
                    new java.sql.SQLException("recoverable SQL but doomed transaction", "P0001"));
        }).when(fraudRules).evaluateRule(any(), any(), any());
        mvc.perform(post(a, order, "fraud-doomed")).andExpect(status().isInternalServerError());
        assertPending(id(order), "CLAIMED");
        assertThat(flagCodes(id(order))).isEmpty();
        assertBalance(a.account, "90000000.00");
        assertBalance(b.account, "90000000.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"C2", "C1", "BLOCK"})
    void fraudAuditFailureRollsBackFlagsChainAndAllT2Writes(String phase) throws Exception {
        Actor a = actor();
        Actor b = actor();
        boolean block = phase.equals("BLOCK");
        fiveRecent(a, b);
        Order order = order(a, b, "10000000.00");
        int version = RULE_VERSIONS.incrementAndGet();
        if (block) insertRule("UNUSUAL_PATTERN_TRANSFER", version, "UNUSUAL_PATTERN", "2000", 1, 600, true,
                Instant.now().minusSeconds(60));
        String condition = phase.equals("C2")
                ? "NEW.action='FRAUD_FLAG_CREATED' AND NEW.after_state->>'ruleCode'='LARGE_VALUE_TRANSFER'"
                : "NEW.action='" + (block ? "TRANSFER_FAILED" : "TRANSFER_COMPLETED") + "'";
        observer.execute("""
                CREATE SEQUENCE banking.int07_audit_order_probe;
                GRANT USAGE ON SEQUENCE banking.int07_audit_order_probe TO banking_app;
                """);
        try (var fault = trigger("audit_log", "BEFORE INSERT", condition, """
                IF (SELECT count(*) FROM banking.audit_log
                    WHERE action='FRAUD_FLAG_CREATED' AND correlation_id=NEW.correlation_id) <> %d THEN
                    RAISE EXCEPTION 'Prior C2 append missing' USING ERRCODE='23514';
                END IF;
                PERFORM nextval('banking.int07_audit_order_probe');
                RAISE EXCEPTION 'Synthetic required audit failure' USING ERRCODE='22012';
                """.formatted(block ? 3 : phase.equals("C2") ? 1 : 2))) {
            mvc.perform(post(a, order, "fraud-audit-" + phase)).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("AUDIT_WRITE_FAILED"));
            assertThat(observer.queryForObject("SELECT is_called FROM banking.int07_audit_order_probe",
                    Boolean.class)).isTrue();
            long id = id(order);
            assertPending(id, "CLAIMED");
            assertThat(flagCodes(id)).isEmpty();
            assertThat(count("SELECT count(*) FROM banking.audit_log WHERE correlation_id=?", "fraud-audit-" + phase))
                    .isEqualTo(1);
            assertBalance(a.account, "90000000.00");
            assertBalance(b.account, "90000000.00");
            assertNoUsage(a);
            assertChain();
        } finally {
            observer.execute("DROP SEQUENCE banking.int07_audit_order_probe");
            if (block) disableRule("UNUSUAL_PATTERN_TRANSFER", version);
        }
    }

    @Test
    void fraudRaceSerializesT1AndT2AndKeepsAuditGroupsContiguous() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Actor c = actor();
        Actor d = actor();
        for (int i = 1; i <= 4; i++) history(a, b, Instant.now().minusSeconds(i), "PENDING");
        fiveRecent(c, d);
        Order first = order(a, b, "10000000.00");
        Order second = order(a, b, "10000000.00");
        Order independent = order(c, d, "10000000.00");
        long third = prepare(c, independent);
        var atFinalAudit = new CyclicBarrier(2);
        var entrants = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        AuditModuleApi target = AopTestUtils.getUltimateTargetObject(audit);
        doAnswer(call -> {
            com.simulatedbank.banking.audit.api.AuditEvent event = call.getArgument(0);
            if (event.action() == com.simulatedbank.banking.audit.api.AuditAction.FRAUD_FLAG_CREATED) {
                String customerGroup = event.correlationId().equals("prepare-t1") ? "independent" : "shared";
                if (entrants.add(customerGroup)) atFinalAudit.await(5, TimeUnit.SECONDS);
            }
            return call.callRealMethod();
        }).when(target).recordInTransaction(any());
        var t1Committed = new CountDownLatch(2);
        seams.afterIntent = id -> {
            t1Committed.countDown();
            await().atMost(Duration.ofSeconds(8)).until(() -> t1Committed.getCount() == 0);
        };
        try (var pool = Executors.newFixedThreadPool(3)) {
            var t1 = pool.submit(() -> mvc.perform(post(a, first, "fraud-race-one")).andReturn().getResponse().getStatus());
            var t2 = pool.submit(() -> mvc.perform(post(a, second, "fraud-race-two")).andReturn().getResponse().getStatus());
            var t3 = pool.submit(() -> {
                await().atMost(Duration.ofSeconds(8)).until(() -> t1Committed.getCount() == 0);
                return handoff.settle(third).committed().response().httpStatus();
            });
            for (var result : List.of(t1, t2, t3)) assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo(201);
        }
        for (long id : List.of(id(first), id(second), third)) {
            assertTerminal(id, true, 1);
            assertThat(flagCodes(id)).containsExactly("HIGH_FREQUENCY_TRANSFER:1", "LARGE_VALUE_TRANSFER:1");
            assertThat(count("""
                    SELECT count(*) FROM banking.fraud_flag
                    WHERE transfer_id=? AND rule_code='HIGH_FREQUENCY_TRANSFER' AND matched_value->>'count'='6'
                    """, id)).isEqualTo(1);
            assertAuditGroup(id, "TRANSFER_COMPLETED", null);
        }
        assertBalance(a.account, "70000000.00");
        assertBalance(b.account, "110000000.00");
        assertBalance(c.account, "80000000.00");
        assertBalance(d.account, "100000000.00");
        assertChain();
        seams.beforeMoney = ignored -> { throw new AssertionError("Race replay evaluated Fraud again"); };
        mvc.perform(post(a, first, "fraud-race-replay")).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void fraudIsMandatoryAndAuditLockIsTheFinalSqlPhase() throws Exception {
        assertThatThrownBy(() -> fraud.evaluateTransfer(new FraudEvaluation.Facts(1, 1, 1, 2,
                BigDecimal.ONE, "VND", Instant.now(), "fraud-mandatory")))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        Actor a = actor();
        Actor b = actor();
        fiveRecent(a, b);
        Order order = order(a, b, "10000000.00");
        var pid = new AtomicInteger();
        seams.beforeMoney = ignored -> pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
        mvc.perform(post(a, order, "fraud-sql")).andExpect(status().isCreated());
        assertAuditGroup(id(order), "TRANSFER_COMPLETED", null);
        assertChain();
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
            var statement = java.util.regex.Pattern.compile("^" + pid.get()
                    + "\\s+LOG:\\s+(?:statement|execute [^:]+):\\s+(.*)$");
            var sql = POSTGRES.getLogs().lines().map(statement::matcher)
                    .filter(java.util.regex.Matcher::matches).map(match -> match.group(1).trim()).toList();
            int begin = sql.lastIndexOf("SELECT pg_backend_pid()");
            assertThat(begin).isNotNegative();
            var t2 = sql.subList(begin + 1, sql.size());
            int lock = java.util.stream.IntStream.range(0, t2.size())
                    .filter(i -> t2.get(i).contains("pg_advisory_xact_lock")).findFirst().orElseThrow();
            assertThat(String.join(" ", t2.subList(0, lock))).contains("fraud_flag", "account", "ledger_entry",
                    "limit_transfer_usage_effect", "idempotency_record", "notification");
            var auditSql = t2.subList(lock, t2.size());
            assertThat(auditSql.stream().filter(row -> row.startsWith("INSERT INTO banking.audit_log"))).hasSize(3);
            assertThat(auditSql.stream().takeWhile(row -> !row.equals("COMMIT")).toList())
                    .allMatch(row -> row.contains("pg_advisory_xact_lock") || row.contains("banking.audit_log")
                            || row.equalsIgnoreCase("SHOW transaction_isolation"));
            assertThat(auditSql).contains("COMMIT");
        });
        assertThat(count("""
                SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace
                  WHERE n.nspname='banking' AND c.condeferrable
                """)).isZero();
        assertThat(count("""
                SELECT count(*) FROM pg_trigger t JOIN pg_class c ON t.tgrelid=c.oid
                  JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='banking' AND t.tgdeferrable
                """)).isZero();
        System.out.println("INT07 SQL: Fraud/account/ledger/Limit/replay/outbox precede the audit lock; three audit appends precede COMMIT; zero deferred constraints/triggers.");
    }

    @Test
    void fraudC3DenyAndConflictKeepResponseUnderAuditFailure() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Order accepted = order(a, b, "2000.00");
        mvc.perform(post(a, accepted, "c3-original")).andExpect(status().isCreated());
        Order forbidden = order(b, a, "2000.00");
        Order conflictActor = new Order(accepted.key, order(b, a, "2000.00").body);
        Order conflictHash = new Order(accepted.key, order(a, b, "2000.01").body);
        assertC3Responses(a, b, forbidden, conflictActor, conflictHash, "durable");
        assertThat(count("""
                SELECT count(*) FROM banking.audit_log WHERE outcome='REJECTED' AND
                    ((correlation_id='int07-durable-deny' AND action='SECURITY_ACCESS_DENIED')
                        OR (correlation_id IN ('int07-durable-actor','int07-durable-hash')
                            AND action='SECURITY_IDEMPOTENCY_CONFLICT' AND entity_id='UNKNOWN'
                            AND after_state='{"reasonCode":"IDEMPOTENCY_CONFLICT"}'::jsonb))
                """)).isEqualTo(3);
        var before = List.of("transfer", "fraud_flag", "ledger_entry", "notification").stream()
                .map(table -> count("SELECT count(*) FROM banking." + table)).toList();
        Logger logger = (Logger) LoggerFactory.getLogger(SecurityAuditFallbackLog.class);
        var logs = capture(logger);
        var counter = meters.counter("transfer.security.deny.fallback");
        double baseline = counter.count();
        try {
            try (var fault = trigger("audit_log", "BEFORE INSERT", "NEW.outcome='REJECTED'",
                    "RAISE EXCEPTION 'Synthetic C3 append failure';")) {
                assertC3Responses(a, b, forbidden, conflictActor, conflictHash, "append");
            }
            try (var fault = trigger("audit_log", "AFTER INSERT DEFERRABLE INITIALLY DEFERRED", "NEW.outcome='REJECTED'",
                    "RAISE EXCEPTION 'Synthetic C3 commit failure';")) {
                assertC3Responses(a, b, forbidden, conflictActor, conflictHash, "commit");
            }
            doAnswer(call -> { throw new org.springframework.transaction.CannotCreateTransactionException("Synthetic C3 begin failure"); })
                    .when(transactions).getTransaction(org.mockito.ArgumentMatchers.argThat(definition ->
                            definition != null && definition.getPropagationBehavior()
                                    == org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));
            assertC3Responses(a, b, forbidden, conflictActor, conflictHash, "begin");
            AuditModuleApi target = AopTestUtils.getUltimateTargetObject(audit);
            doAnswer(call -> { throw new IllegalStateException("Synthetic C3 provider exception"); })
                    .when(target).recordStandalone(any());
            assertC3Responses(a, b, forbidden, conflictActor, conflictHash, "throws");
            assertThat(counter.count() - baseline).isEqualTo(12);
            assertThat(encoded(logs)).hasSize(12).allSatisfy(line -> assertThat(line)
                    .contains("SECURITY_AUDIT_FALLBACK", "\"outcome\":\"REJECTED\"", "sha256:", "\"request_id\":\"")
                    .doesNotContain(accepted.key, a.token, b.token, "Synthetic"));
        } finally {
            logger.detachAppender(logs);
        }
        assertThat(List.of("transfer", "fraud_flag", "ledger_entry", "notification").stream()
                .map(table -> count("SELECT count(*) FROM banking." + table)).toList()).isEqualTo(before);
        assertThat(count("""
                SELECT count(*) FROM banking.audit_log WHERE action IN ('SECURITY_ACCESS_DENIED','SECURITY_IDEMPOTENCY_CONFLICT')
                  AND correlation_id ~ '^int07-(append|commit|begin|throws)-'
                """)).isZero();
        assertChain();
    }

    private void assertC3Responses(Actor a, Actor b, Order forbidden, Order conflictActor, Order conflictHash,
                                   String phase) throws Exception {
        mvc.perform(post(a, forbidden, "int07-" + phase + "-deny")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
                .andExpect(jsonPath("$.reference").doesNotExist());
        mvc.perform(post(b, conflictActor, "int07-" + phase + "-actor")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.reference").doesNotExist());
        mvc.perform(post(a, conflictHash, "int07-" + phase + "-hash")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.reference").doesNotExist());
    }

    private static ListAppender<ILoggingEvent> capture(Logger logger) {
        var appender = new ListAppender<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static List<String> encoded(ListAppender<ILoggingEvent> captured) {
        var encoder = new SafeJsonLogEncoder();
        return captured.list.stream().map(encoder::encode)
                .map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).toList();
    }

    private void assertAuditGroup(long id, String terminalAction, String failureCode) {
        List<String> flags = observer.queryForList("""
                SELECT 'FRAUD_FLAG_CREATED:' || flag_id
                FROM banking.fraud_flag WHERE transfer_id=? ORDER BY flag_id
                """, String.class, id);
        String reference = store.read(id).reference();
        var expected = new ArrayList<>(flags);
        expected.add(terminalAction + ":" + reference);
        assertThat(observer.queryForList("""
                WITH group_rows AS (
                    SELECT audit_id FROM banking.audit_log WHERE
                        (action='FRAUD_FLAG_CREATED' AND after_state->>'transferReference'=?)
                        OR (action=? AND entity_id=? AND after_state->>'failureCode' IS NOT DISTINCT FROM ?)
                ) SELECT action || ':' || entity_id FROM banking.audit_log
                WHERE audit_id BETWEEN (SELECT min(audit_id) FROM group_rows) AND (SELECT max(audit_id) FROM group_rows)
                ORDER BY audit_id
                """, String.class, reference, terminalAction, reference, failureCode)).containsExactlyElementsOf(expected);
        assertThat(count("""
                SELECT count(*) FROM banking.audit_log l JOIN banking.fraud_flag f
                    ON l.entity_id=f.flag_id::text JOIN banking.transfer t USING(transfer_id)
                WHERE t.transfer_id=? AND l.action='FRAUD_FLAG_CREATED' AND l.actor_type='SYSTEM'
                  AND l.actor_user_id IS NULL AND l.entity_type='FRAUD_FLAG' AND l.outcome='SUCCESS'
                  AND l.correlation_id=t.correlation_id AND l.occurred_at=f.created_at
                  AND l.request_id IS NOT NULL AND l.request_id = (SELECT terminal.request_id
                      FROM banking.audit_log terminal WHERE terminal.entity_id=t.reference_no AND terminal.action=?)
                  AND l.after_state=jsonb_build_object(
                    'transferReference',t.reference_no, 'ruleCode',f.rule_code, 'ruleVersion',f.rule_version,
                    'severity',f.severity, 'status','OPEN')
                """, id, terminalAction)).isEqualTo(flags.size());
    }

    private void assertChain() {
        assertThat(new AuditChainJdbcVerifier(observer).verify().status()).isEqualTo(AuditChainVerifier.Status.PASS);
    }

    private List<String> flagCodes(long id) {
        return observer.queryForList("""
                SELECT rule_code || ':' || rule_version FROM banking.fraud_flag
                WHERE transfer_id=? ORDER BY flag_id
                """, String.class, id);
    }

    private void fiveRecent(Actor from, Actor to) {
        for (int i = 1; i <= 5; i++) history(from, to, Instant.now().minusSeconds(i), "PENDING");
    }

    private void history(Actor from, Actor to, Instant created, String state) {
        observer.update("""
                INSERT INTO banking.transfer (reference_no, customer_id, initiated_by_user_id, source_account_id,
                    destination_account_id, amount, correlation_id, created_at, status, completed_at,
                    failed_at, failure_code)
                VALUES (?, ?, ?, ?, ?, 2000, 'int07-history', ?, ?, ?, ?, ?)
                """, "H" + UUID.randomUUID().toString().replace("-", "").substring(0, 28),
                from.customer, from.user, from.account, to.account, utc(created), state,
                state.equals("COMPLETED") ? utc(created) : null, state.equals("FAILED") ? utc(created) : null,
                state.equals("FAILED") ? "PROCESSING_ERROR" : null);
    }

    private void insertRule(String code, int version, String type, String amount, int count, int seconds,
                            boolean block, Instant effectiveFrom) {
        observer.update("""
                INSERT INTO banking.fraud_rule_config (rule_code, version, rule_name, rule_type, threshold_amount,
                    threshold_count, window_seconds, severity, effective_from, auto_block, created_by)
                SELECT ?, ?, ?, ?, ?::numeric, ?, ?, 'HIGH', ?, ?, user_id
                FROM banking.app_user WHERE username='__fraud_rule_bootstrap__'
                """, code, version, code, type, amount, count, seconds, utc(effectiveFrom), block);
    }

    private void disableRule(String code, int version) {
        observer.update("UPDATE banking.fraud_rule_config SET enabled=FALSE WHERE rule_code=? AND version=?", code, version);
    }

    private static OffsetDateTime utc(Instant timestamp) {
        return timestamp.atOffset(java.time.ZoneOffset.UTC);
    }

    private List<Integer> race(List<Long> ids) throws Exception {
        var barrier = new CyclicBarrier(ids.size());
        try (var pool = Executors.newFixedThreadPool(ids.size())) {
            var futures = ids.stream().map(id -> pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return handoff.settle(id).committed().response().httpStatus();
            })).toList();
            var result = new ArrayList<Integer>();
            for (var future : futures) result.add(future.get(20, TimeUnit.SECONDS));
            return result;
        }
    }

    private long prepare(Actor actor, Order order) throws Exception {
        try {
            seams.afterIntent = id -> { throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE); };
            mvc.perform(post(actor, order, "prepare-t1")).andExpect(status().isServiceUnavailable());
            long id = id(order);
            assertPending(id, "IDLE");
            return id;
        } finally {
            seams.afterIntent = id -> { };
        }
    }

    private AutoCloseable trigger(String table, String timing, String condition, String effect) {
        observer.execute("""
                CREATE FUNCTION banking.int06_fault() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF %s THEN %s END IF;
                    RETURN NEW;
                END $$;
                %s
                """.formatted(condition, effect, timing.endsWith("INITIALLY DEFERRED")
                    ? "CREATE CONSTRAINT TRIGGER int06_fault AFTER INSERT ON banking." + table
                        + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.int06_fault();"
                    : "CREATE TRIGGER int06_fault " + timing + " ON banking." + table
                        + " FOR EACH ROW EXECUTE FUNCTION banking.int06_fault();"));
        return () -> {
            observer.execute("DROP TRIGGER int06_fault ON banking." + table);
            observer.execute("DROP FUNCTION banking.int06_fault()");
        };
    }

    private void assertNoUsage(Actor a) {
        assertThat(count("SELECT count(*) FROM banking.limit_usage WHERE customer_id=? AND transfer_count<>0", a.customer))
                .isZero();
    }

    private MockHttpServletRequestBuilder post(Actor a, Order order, String correlation) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/transfers")
                .header("Authorization", "Bearer " + a.token).header("Idempotency-Key", order.key)
                .header("X-Correlation-ID", correlation).contentType("application/json").content(order.body);
    }

    private Actor actor() {
        long user = observer.queryForObject("""
                INSERT INTO banking.app_user(username,password_hash,user_type,status)
                VALUES (?, 'fixture','CUSTOMER','ACTIVE') RETURNING user_id
                """, Long.class, key());
        long customer = observer.queryForObject("""
                INSERT INTO banking.customer(user_id,full_name_enc)
                VALUES (?, decode('00','hex')) RETURNING customer_id
                """, Long.class, user);
        long checker = observer.queryForObject("""
                INSERT INTO banking.app_user(username,password_hash,user_type,status)
                VALUES (?, 'fixture','STAFF','ACTIVE') RETURNING user_id
                """, Long.class, key());
        var a = account(user, customer, checker, key());
        observer.update("""
                INSERT INTO banking.limit_config(customer_id,per_transaction_limit,daily_limit,effective_from,status,
                    version,created_by,approved_by,approved_at)
                VALUES (?, 40000000, 90000000, '2020-01-01', 'ACTIVE', 1, ?, ?, clock_timestamp())
                """, customer, user, checker);
        observer.update("""
                INSERT INTO banking.user_role(user_id,role_id)
                SELECT ?, role_id FROM banking.role WHERE role_code='CUSTOMER'
                """, user);
        observer.update("""
                INSERT INTO banking.session(session_token_hash,csrf_token_hash,user_id,idle_expires_at,absolute_expires_at)
                VALUES (?, ?, ?, clock_timestamp()+interval '20 minutes', clock_timestamp()+interval '1 hour')
                """, HmacService.sha256Hex(a.token), HmacService.sha256Hex("int06-fixture-csrf"), user);
        return a;
    }

    private Actor account(long user, long customer, long checker, String token) {
        String number = "001600" + NUMBERS.incrementAndGet();
        var protectedNumber = numbers.protect(number);
        long account = observer.queryForObject("""
                INSERT INTO banking.account(customer_id,account_no_enc,account_no_hash,account_no_last4,
                                            available_balance,ledger_balance)
                VALUES (?, ?, ?, ?, 90000000, 90000000) RETURNING account_id
                """, Long.class, customer, protectedNumber.encrypted(), protectedNumber.blindIndex(),
                protectedNumber.accountNumberLast4());
        return new Actor(user, customer, checker, account, token);
    }


    private void approveAccountCutover(Actor a, Instant cutover) {
        long approval = count("""
                INSERT INTO banking.approval_request(request_type,target_type,target_id,payload,maker_id,correlation_id)
                VALUES ('LIMIT_CHANGE','ACCOUNT',?,'{}',?,'int06-cutover') RETURNING approval_id
                """, Long.toString(a.account), a.user);
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> adminLimits.reservePendingChange(new LimitChangeRequest(
                approval, a.user, a.customer, a.account, new BigDecimal("2000.00"), new BigDecimal("4000.00"),
                "VND", cutover, 0)));
        tx.executeWithoutResult(status -> adminLimits.applyApprovedLimitChange(
                new ApprovedLimitChange(approval, a.checker, 0, "int06-cutover")));
    }

    private void addAccountConfig(Actor a, long account, String per, String daily, String effectiveFrom) {
        observer.update("""
                INSERT INTO banking.limit_config(customer_id,account_id,per_transaction_limit,daily_limit,
                    effective_from,status,version,created_by,approved_by,approved_at)
                VALUES (?, ?, ?::numeric, ?::numeric, ?::timestamptz, 'ACTIVE', 1, ?, ?, clock_timestamp())
                """, a.customer, account, per, daily, effectiveFrom, a.user, a.checker);
    }

    private void balance(long account, String amount) {
        observer.update("UPDATE banking.account SET available_balance=?::numeric, ledger_balance=?::numeric WHERE account_id=?",
                amount, amount, account);
    }

    private void assertBalance(long account, String value) {
        assertBalance(account, value, value);
    }

    private void assertBalance(long account, String available, String ledger) {
        List<BigDecimal> balances = observer.queryForObject("""
                SELECT available_balance, ledger_balance FROM banking.account WHERE account_id=?
                """, (rs, index) -> List.of(rs.getBigDecimal(1), rs.getBigDecimal(2)), account);
        assertThat(balances).containsExactly(new BigDecimal(available), new BigDecimal(ledger));
    }

    private long count(String sql, Object... args) {
        return observer.queryForObject(sql, Long.class, args);
    }

    private long id(Order order) {
        return count("SELECT transfer_id FROM banking.idempotency_record WHERE idempotency_key=?", order.key);
    }

    private void assertTerminal(long id, boolean completed, int effects) {
        String state = completed ? "COMPLETED" : "FAILED";
        assertThat(count("""
                SELECT count(*) FROM banking.transfer t JOIN banking.idempotency_record i USING(transfer_id)
                JOIN banking.limit_transfer_reservation r USING(transfer_id)
                JOIN banking.audit_log l ON l.entity_id=t.reference_no AND l.action=?
                JOIN banking.notification n ON n.transfer_id=t.transfer_id AND n.correlation_id=t.correlation_id
                WHERE t.transfer_id=? AND t.status=? AND i.status=t.status AND i.response_status=?
                  AND t.attempt_state='DONE' AND t.attempt_epoch=1 AND t.attempt_token IS NULL
                  AND r.state=? AND r.effect_count=? AND i.completed_at=r.terminal_at
                  AND i.completed_at=COALESCE(t.completed_at,t.failed_at) AND i.completed_at=l.occurred_at
                  AND l.correlation_id=t.correlation_id AND l.amount=t.amount AND l.actor_user_id=t.initiated_by_user_id
                  AND i.expires_at>=i.completed_at+interval '24 hours' AND i.ttl_terminal_seconds>=86400
                  AND i.response_body->>'correlationId'=t.correlation_id AND n.status='PENDING'
                  AND n.payload_masked->>'outcome'=t.status AND n.payload_masked->>'maskedAccount' LIKE '**** **** %'
                """, "TRANSFER_" + state, id, state, completed ? 201 : 422, completed ? "SETTLED" : "RELEASED",
                effects)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM banking.ledger_entry WHERE transfer_id=?", id)).isEqualTo(completed ? 2 : 0);
        assertThat(count("SELECT count(*) FROM banking.limit_transfer_usage_effect WHERE transfer_id=?", id)).isEqualTo(effects);
    }

    private void assertPending(long id, String attempt) {
        assertThat(count("""
                SELECT count(*) FROM banking.transfer t JOIN banking.idempotency_record i USING(transfer_id)
                JOIN banking.limit_transfer_reservation r USING(transfer_id)
                JOIN banking.audit_log l ON l.entity_id=t.reference_no AND l.action='TRANSFER_CREATED'
                WHERE t.transfer_id=? AND t.status='PENDING' AND t.attempt_state=?
                  AND r.state='PENDING' AND i.status='RECEIVED' AND i.response_body IS NULL
                  AND NOT EXISTS (SELECT 1 FROM banking.audit_log x WHERE x.entity_id=t.reference_no
                      AND x.action IN ('TRANSFER_COMPLETED', 'TRANSFER_FAILED'))
                  AND NOT EXISTS (SELECT 1 FROM banking.notification WHERE transfer_id=t.transfer_id)
                  AND NOT EXISTS (SELECT 1 FROM banking.limit_transfer_usage_effect WHERE transfer_id=t.transfer_id)
                  AND NOT EXISTS (SELECT 1 FROM banking.ledger_entry WHERE transfer_id=t.transfer_id)
                """, id, attempt)).isEqualTo(1);
    }

    private static String key() { return "int06-" + UUID.randomUUID(); }

    private static Order order(Actor source, Actor destination, String amount) {
        return new Order(key(), """
                {"sourceAccountId":"%d","destination":{"accountId":"%d"},"amount":"%s"}
                """.formatted(source.account, destination.account, amount));
    }

    private record Actor(long user, long customer, long checker, long account, String token) { }
    private record Order(String key, String body) { }

    static class Boundaries {
        volatile LongConsumer afterIntent;
        volatile LongConsumer beforeMoney;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean Boundaries seams() { return new Boundaries(); }

        @Bean @Primary PostCommitSettlement testHandoff(TransferSettlementHandoff real, Boundaries seams) {
            return id -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                seams.afterIntent.accept(id);
                return real.settle(id);
            };
        }

        @Bean TransferNotifications transferNotifications(NotificationOutboxPort outbox) {
            return new TransferNotifications((facts, outcome) -> List.of(new TransferNotifications.Recipient(
                    facts.senderCustomerId(), TransferNotifications.Channel.IN_APP, "TRANSFER_RESULT")),
                    notices -> outbox.enqueueTransferNotificationsInT2(notices.stream().map(notice ->
                            new TransferNotice(notice.recipient().customerId(), notice.transferId(), notice.reference(),
                                    NotificationChannel.IN_APP, notice.recipient().templateCode(), Map.of(
                                    "amount", notice.amount().value(), "currency", notice.amount().currency(),
                                    "maskedAccount", notice.maskedDestination(), "outcome",
                                    notice.outcome().failure().isPresent() ? "FAILED" : "COMPLETED"),
                                    notice.correlationId())).toList()));
        }

        @Bean OtpGateway unusedOtp() {
            return (ref, user, channel, otp, expires, correlation) -> { throw new AssertionError("Unexpected OTP"); };
        }
        @Bean TransferReadPort unusedRead() { return ref -> Optional.empty(); }
        @Bean FraudFlagReadPort unusedFraud() { return id -> List.of(); }
        @Bean AccountOwnershipPort unusedOwnership() { return (account, customer) -> AccountOwnershipPort.Ownership.NOT_FOUND; }
        @Bean TransferStaffScope unusedStaffScope() { return (actor, transfer) -> false; }
    }
}
