package com.simulatedbank.banking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.account.application.AccountNumberProtection;
import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.TransferBinding;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.limit.api.TransferLimits;
import com.simulatedbank.banking.notification.api.OtpGateway;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import com.simulatedbank.banking.transfer.adapter.out.integration.TransferAuditAdapter;
import com.simulatedbank.banking.transfer.adapter.out.integration.TransferCreateAdapters.PostCommitSettlement;
import com.simulatedbank.banking.transfer.adapter.out.integration.TransferCreateAdapters;
import com.simulatedbank.banking.transfer.adapter.out.integration.TransferIdentityAdapter;
import com.simulatedbank.banking.transfer.adapter.out.limit.TransferLimitAdapter;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferPersistence;
import com.simulatedbank.banking.transfer.application.AccountOwnershipPort;
import com.simulatedbank.banking.transfer.application.FraudFlagReadPort;
import com.simulatedbank.banking.transfer.application.TransferIntentCandidate;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.Settlement;
import com.simulatedbank.banking.transfer.application.TransferReadPort;
import com.simulatedbank.banking.transfer.application.TransferRequestHash.Destination;
import com.simulatedbank.banking.transfer.application.TransferRequestHash;
import com.simulatedbank.banking.transfer.application.TransferStaffScope;
import com.simulatedbank.banking.transfer.application.TransferTelemetry;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferIdentity;
import com.simulatedbank.transfercandidate.web.TransferCreateIntegrationConfiguration;
import com.simulatedbank.transfercandidate.web.TransferHttpConfiguration;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import({TransferHttpConfiguration.class, TransferCreateIntegrationConfiguration.class,
        TransferT0T1PostgreSqlIntegrationTest.Fixtures.class})
class TransferT0T1PostgreSqlIntegrationTest {
    private static final String TRANSFERS = "/api/v1/transfers";
    private static final String VERIFY = "/api/v1/auth/step-up/verify";
    private static final String CSRF = "int05-fixture-csrf";
    private static final String STEP_UP_AMOUNT = "10000000.00";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("limit/init-roles.sql");

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
    @Autowired ObjectMapper json;
    @Autowired JcsCanonicalizer canonicalizer;
    @Autowired DataSource source;
    @Autowired ApplicationContext context;
    @Autowired Flyway flyway;
    @Autowired AccountNumberProtection numbers;
    @Autowired StepUpChallengePort identity;
    @Autowired Gateway gateway;
    @Autowired Handoff handoff;
    private JdbcTemplate observer;

    @BeforeEach
    void connectObserver() {
        PostgreSqlIntegrationTestSupport.assertContainerDataSource(source, POSTGRES);
        observer = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "banking_migrator", "test_migrator"));
        gateway.reset();
        handoff.calls.set(0);
    }

    @Test
    void httpAuthorizationOwnershipAndTtlBeforeHandoff() throws Exception {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("25");
        Actor owner = actor();
        Actor other = actor();
        String key = key();
        var request = order(owner, other, "2000.00", key);
        mvc.perform(post(TRANSFERS).contentType("application/json").content(request.body()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(TRANSFERS).cookie(new Cookie("DB_SESSION", owner.token()))
                .header("Idempotency-Key", key).contentType("application/json").content(request.body()))
                .andExpect(status().isForbidden());
        mvc.perform(post(TRANSFERS).header("Authorization", owner.bearer())
                .contentType("application/json").content(request.body())).andExpect(status().isBadRequest());
        mvc.perform(create(other, request)).andExpect(status().isForbidden());
        assertNoIntent(owner, key);
        assertThat(count("SELECT count(*) FROM banking.audit_log WHERE action='SECURITY_ACCESS_DENIED' "
                + "AND actor_user_id=? AND entity_type='ACCOUNT'", other.userId())).isEqualTo(1);
        mvc.perform(post(TRANSFERS).cookie(new Cookie("DB_SESSION", owner.token())).header("X-CSRF-Token", CSRF)
                .header("Idempotency-Key", key).contentType("application/json").content(request.body()))
                .andExpect(status().isServiceUnavailable());
        assertIntent(owner, key, null);
        assertThat(observer.queryForMap("""
                SELECT ttl_t1_seconds, ttl_t1_config_version, expires_at = created_at + interval '24 hours' AS exact
                FROM banking.idempotency_record WHERE idempotency_key=?
                """, key)).containsEntry("ttl_t1_seconds", 86400L)
                .containsEntry("ttl_t1_config_version", "transfer-ttl-hours-24").containsEntry("exact", true);
        mvc.perform(create(owner, request)).andExpect(status().isConflict()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
        assertThat(handoff.calls).hasValue(1);
    }

    @Test
    void replayPrecedesCurrentEligibilityAndReturnsStored201And422AfterTtl() throws Exception {
        Actor owner = actor();
        Actor destination = actor();
        var success = order(owner, destination, "2300.00", key());
        var failure = order(owner, destination, "2400.00", key());
        for (Order order : new Order[] {success, failure}) {
            mvc.perform(create(owner, order)).andExpect(status().isServiceUnavailable());
        }
        String completedBody = "{\"status\":\"COMPLETED\",\"correlationId\":\"historic-success\"}";
        String failedBody = "{\"code\":\"INSUFFICIENT_BALANCE\",\"correlationId\":\"historic-failure\"}";
        terminal(success.key(), 201, completedBody);
        terminal(failure.key(), 422, failedBody);
        observer.update("UPDATE banking.account SET status='LOCKED' WHERE account_id=?", destination.account());
        observer.update("UPDATE banking.customer SET status='INACTIVE' WHERE customer_id=?", owner.customer());
        for (var entry : Map.of(success, 201, failure, 422).entrySet()) {
            var reply = mvc.perform(create(owner, entry.getKey())).andExpect(status().is(entry.getValue()))
                    .andExpect(header().string("Idempotent-Replayed", "true")).andReturn();
            assertThat(json.readTree(reply.getResponse().getContentAsString()))
                    .isEqualTo(json.readTree(entry.getValue() == 201 ? completedBody : failedBody));
        }
        var newOrder = order(owner, destination, "2300.00", key());
        mvc.perform(create(owner, newOrder)).andExpect(status().isForbidden());
        assertThat(count("SELECT count(*) FROM banking.idempotency_record WHERE idempotency_key=?", newOrder.key()))
                .isZero();
        assertThat(handoff.calls).hasValue(2);
    }

    @Test
    void t0WaitsForDeliveryPersistsAuditAndVerifiedProofIsAttachedOnce() throws Exception {
        Actor owner = actor();
        var order = order(owner, actor(), STEP_UP_AMOUNT, key());
        UUID ref = issue(owner, order);
        assertThat(observer.queryForObject("SELECT status FROM banking.auth_challenge WHERE challenge_ref=?",
                String.class, ref)).isEqualTo("SENT");
        assertNoIntent(owner, order.key());
        var binding = binding(owner, order);
        assertThat(identity.findVerifiedBindingIfPresent(binding)).isEmpty();
        mvc.perform(verify(actor(), ref, gateway.peek(ref))).andExpect(status().isNotFound());
        mvc.perform(verify(owner, ref, "123")).andExpect(status().isBadRequest());
        String otp = gateway.peek(ref);
        String wrongOtp = (otp.startsWith("0") ? "1" : "0") + otp.substring(1);
        mvc.perform(verify(owner, ref, wrongOtp)).andExpect(status().isUnauthorized());
        mvc.perform(verify(owner, ref, otp)).andExpect(status().isOk());
        assertThat(identity.findVerifiedBindingIfPresent(binding)).get()
                .extracting(StepUpChallengePort.VerifiedChallenge::challengeRef).isEqualTo(ref.toString());
        assertThat(identity.findVerifiedBindingIfPresent(new TransferBinding(owner.userId(), owner.customer(),
                "e".repeat(64), order.key()))).isEmpty();
        assertThat(identity.findVerifiedBindingIfPresent(new TransferBinding(owner.userId(), owner.customer(),
                binding.requestHash(), key()))).isEmpty();
        assertThatThrownBy(() -> identity.findVerifiedBindingIfPresent(new TransferBinding(owner.userId(),
                owner.customer() + 1000, binding.requestHash(), order.key()))).isInstanceOf(BankingBusinessException.class);
        mvc.perform(create(owner, order)).andExpect(status().isServiceUnavailable());
        assertIntent(owner, order.key(), ref);
        assertThat(identity.findVerifiedBindingIfPresent(binding)).isEmpty();
        mvc.perform(create(owner, order)).andExpect(status().isConflict());
        assertThat(handoff.calls).hasValue(1);
        assertThat(observer.queryForList("SELECT action FROM banking.audit_log WHERE entity_id=? ORDER BY audit_id",
                String.class, ref.toString())).containsExactly("AUTH_CHALLENGE_CREATED",
                        "AUTH_STEP_UP_FAILED", "AUTH_STEP_UP_VERIFIED");
    }

    @Test
    void rejectedTimedOutAndLostSentCasStayQuarantinedAndReturn503() throws Exception {
        for (var failure : new OtpGateway.Result[] {
                new OtpGateway.Result(OtpGateway.Outcome.FAILED, OtpGateway.Failure.REJECTED),
                new OtpGateway.Result(OtpGateway.Outcome.RETRYABLE, OtpGateway.Failure.TIMEOUT),
                Gateway.ACK}) {
            Actor owner = actor();
            var order = order(owner, actor(), STEP_UP_AMOUNT, key());
            gateway.callback.set(ref -> {
                if (failure == Gateway.ACK) {
                    observer.update("UPDATE banking.auth_challenge SET expires_at=clock_timestamp() "
                            + "WHERE challenge_ref=?", UUID.fromString(ref));
                }
                return failure;
            });
            mvc.perform(create(owner, order)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("OTP_DISPATCH_FAILED"))
                    .andExpect(jsonPath("$.challenge").doesNotExist());
            assertNoIntent(owner, order.key());
            assertThat(count("SELECT count(*) FROM banking.auth_challenge WHERE user_id=? AND status=?",
                    owner.userId(), failure == Gateway.ACK ? "EXPIRED" : "CANCELLED")).isEqualTo(1);
        }
        assertThat(handoff.calls).hasValue(0);
    }

    @Test
    void reissueExpiresOldBindingAndUsesANewUuid() throws Exception {
        Actor owner = actor();
        var order = order(owner, actor(), STEP_UP_AMOUNT, key());
        UUID first = issue(owner, order);
        mvc.perform(create(owner, order)).andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.challenge.challengeId").value(first.toString()));
        assertThat(gateway.calls).hasValue(1);
        mvc.perform(verify(owner, first, gateway.peek(first))).andExpect(status().isOk());
        observer.update("UPDATE banking.auth_challenge SET expires_at=clock_timestamp() WHERE challenge_ref=?", first);
        assertThat(identity.findVerifiedBindingIfPresent(binding(owner, order))).isEmpty();
        UUID second = issue(owner, order);
        assertThat(second).isNotEqualTo(first);
        assertThat(count("SELECT count(*) FROM banking.auth_challenge WHERE challenge_ref=? AND status='EXPIRED'", first))
                .isEqualTo(1);
        assertNoIntent(owner, order.key());
    }

    @Test
    void auditFailuresRollBackIssueVerifyAndConsumedT1() throws Exception {
        Actor owner = actor();
        var order = order(owner, actor(), STEP_UP_AMOUNT, key());
        rejectInsert("audit_log", "NEW.action = 'AUTH_CHALLENGE_CREATED'");
        try {
            mvc.perform(create(owner, order)).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("AUDIT_WRITE_FAILED"));
            assertThat(gateway.calls).hasValue(0);
            assertThat(count("SELECT count(*) FROM banking.auth_challenge WHERE user_id=?", owner.userId())).isZero();
        } finally {
            removeFailure("audit_log");
        }

        UUID ref = issue(owner, order);
        rejectInsert("audit_log", "NEW.action = 'AUTH_STEP_UP_VERIFIED'");
        try {
            mvc.perform(verify(owner, ref, gateway.peek(ref))).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("AUDIT_WRITE_FAILED"));
            assertThat(count("SELECT count(*) FROM banking.auth_challenge WHERE challenge_ref=?"
                    + " AND status='SENT' AND attempt_count=0", ref)).isEqualTo(1);
        } finally {
            removeFailure("audit_log");
        }
        mvc.perform(verify(owner, ref, gateway.peek(ref))).andExpect(status().isOk());

        rejectInsert("audit_log", "NEW.action = 'TRANSFER_CREATED'");
        try {
            mvc.perform(create(owner, order)).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("AUDIT_WRITE_FAILED"));
            assertNoIntent(owner, order.key());
            assertThat(handoff.calls).hasValue(0);
            assertThat(identity.findVerifiedBindingIfPresent(binding(owner, order))).isPresent();
        } finally {
            removeFailure("audit_log");
        }
        mvc.perform(create(owner, order)).andExpect(status().isServiceUnavailable());
        assertIntent(owner, order.key(), ref);
    }

    @Test
    void reservationSqlFailureRollsBackTransferAndKey() throws Exception {
        Actor owner = actor();
        var order = order(owner, actor(), "2001.00", key());
        rejectInsert("limit_transfer_reservation", "TRUE");
        try {
            mvc.perform(create(owner, order)).andExpect(status().is5xxServerError());
            assertNoIntent(owner, order.key());
            assertThat(handoff.calls).hasValue(0);
        } finally {
            removeFailure("limit_transfer_reservation");
        }
        mvc.perform(create(owner, order)).andExpect(status().isServiceUnavailable());
        assertIntent(owner, order.key(), null);
    }

    @Test
    void challengeAttachFailureRollsBackConsumptionAndAllT1Rows() throws Exception {
        Actor owner = actor();
        var order = order(owner, actor(), STEP_UP_AMOUNT, key());
        UUID ref = issue(owner, order);
        mvc.perform(verify(owner, ref, gateway.peek(ref))).andExpect(status().isOk());
        observer.execute("""
                CREATE FUNCTION banking.int05_fail_attach() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  RAISE EXCEPTION 'INT05 challenge attach failure' USING ERRCODE = '23514';
                END $$;
                CREATE TRIGGER int05_fail_attach BEFORE UPDATE OF step_up_challenge_id ON banking.transfer
                FOR EACH ROW EXECUTE FUNCTION banking.int05_fail_attach();
                """);
        try {
            mvc.perform(create(owner, order)).andExpect(status().is5xxServerError());
            assertNoIntent(owner, order.key());
            assertThat(handoff.calls).hasValue(0);
            assertThat(identity.findVerifiedBindingIfPresent(binding(owner, order))).isPresent();
        } finally {
            observer.execute("DROP TRIGGER int05_fail_attach ON banking.transfer");
            observer.execute("DROP FUNCTION banking.int05_fail_attach()");
        }
        mvc.perform(create(owner, order)).andExpect(status().isServiceUnavailable());
        assertIntent(owner, order.key(), ref);
    }

    @Test
    void expiredProofAfterLookupRollsBackUnderTheChallengeLock() throws Exception {
        Actor owner = actor();
        var order = order(owner, actor(), STEP_UP_AMOUNT, key());
        UUID ref = issue(owner, order);
        mvc.perform(verify(owner, ref, gateway.peek(ref))).andExpect(status().isOk());
        // Simulate a competing challenge change after the lookup, at the reservation boundary.
        observer.execute("""
                CREATE FUNCTION banking.int05_expire_proof() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  UPDATE banking.auth_challenge SET expires_at=clock_timestamp()
                    WHERE user_id=(SELECT initiated_by_user_id FROM banking.transfer WHERE transfer_id=NEW.transfer_id)
                      AND status='VERIFIED' AND consumed_at IS NULL;
                  RETURN NEW;
                END $$;
                CREATE TRIGGER int05_expire_proof BEFORE INSERT ON banking.limit_transfer_reservation
                FOR EACH ROW EXECUTE FUNCTION banking.int05_expire_proof();
                """);
        try {
            mvc.perform(create(owner, order)).andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("AUTH_CHALLENGE_EXPIRED"));
            assertNoIntent(owner, order.key());
            assertThat(handoff.calls).hasValue(0);
            assertThat(identity.findVerifiedBindingIfPresent(binding(owner, order))).isPresent();
        } finally {
            observer.execute("DROP TRIGGER int05_expire_proof ON banking.limit_transfer_reservation");
            observer.execute("DROP FUNCTION banking.int05_expire_proof()");
        }
    }

    @Test
    void sameAndDifferentCustomerKeyRacesHandoffOnlyOnceAndConflictIsPrivate() throws Exception {
        for (boolean sameCustomer : new boolean[] {true, false}) {
            Actor a = actor();
            Actor b = sameCustomer ? a : actor();
            Actor destination = actor();
            var first = order(a, destination, "2000.00", key());
            var second = order(b, destination, "2000.00", first.key());
            int before = handoff.calls.get();
            var replies = sameCustomer ? race(a, first, b, second) : globalKeyRace(a, first, b, second);
            assertThat(Arrays.stream(replies).map(result -> result.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(503, 409);
            var loser = Arrays.stream(replies).filter(result -> result.getResponse().getStatus() == 409)
                    .findFirst().orElseThrow().getResponse();
            assertThat(json.readTree(loser.getContentAsString()).path("code").asText())
                    .isEqualTo(sameCustomer ? "IDEMPOTENCY_IN_PROGRESS" : "IDEMPOTENCY_CONFLICT");
            assertThat(loser.getContentAsString()).doesNotContain(first.key(), destination.number(), "reference");
            assertThat(count("SELECT count(*) FROM banking.idempotency_record WHERE idempotency_key=?", first.key()))
                    .isEqualTo(1);
            assertThat(count("SELECT count(*) FROM banking.transfer WHERE customer_id IN (?, ?)", a.customer(), b.customer()))
                    .isEqualTo(1);
            assertThat(handoff.calls).hasValue(before + 1);
            if (!sameCustomer) {
                assertThat(count("SELECT count(*) FROM banking.audit_log WHERE action='SECURITY_IDEMPOTENCY_CONFLICT'"
                        + " AND actor_user_id IN (?, ?)", a.userId(), b.userId())).isEqualTo(1);
            }
        }
    }

    @Test
    void concurrentDistinctKeysUseCustomerGuardAcrossAccountsAndRespectProjectedBoundary() throws Exception {
        Actor owner = actor();
        Actor alternativeSource = new Actor(owner.userId(), owner.customer(),
                account(owner.customer(), number()), owner.token(), owner.number());
        Actor destination = actor();
        observer.update("""
                INSERT INTO banking.limit_usage(customer_id, usage_date, used_amount, transfer_count)
                VALUES (?, (clock_timestamp() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date, 2000000.01, 1)
                """, owner.customer());
        var a = order(owner, destination, "9000000.00", key());
        var b = order(alternativeSource, destination, "9000000.00", key());
        var replies = race(owner, a, alternativeSource, b);
        assertThat(Arrays.stream(replies).map(result -> result.getResponse().getStatus()))
                .containsExactlyInAnyOrder(503, 428);
        assertThat(count("SELECT count(*) FROM banking.limit_transfer_reservation WHERE customer_id=?", owner.customer()))
                .isEqualTo(1);
        assertThat(handoff.calls).hasValue(1);

        Actor exact = actor();
        observer.update("""
                INSERT INTO banking.limit_usage(customer_id, usage_date, used_amount, transfer_count)
                VALUES (?, (clock_timestamp() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date, 2000000, 1)
                """, exact.customer());
        var atBoundary = race(exact, order(exact, destination, "9000000.00", key()),
                exact, order(exact, destination, "9000000.00", key()));
        assertThat(Arrays.stream(atBoundary).map(result -> result.getResponse().getStatus())).containsOnly(503);
        assertThat(count("SELECT count(*) FROM banking.limit_transfer_reservation WHERE customer_id=?", exact.customer()))
                .isEqualTo(2);
    }

    @Test
    void pendingFromPreviousBusinessDateStillCountsUnderTheCustomerGuard() throws Exception {
        Actor owner = actor();
        Actor destination = actor();
        long prior = observer.queryForObject("""
                INSERT INTO banking.transfer(reference_no, customer_id, initiated_by_user_id,
                    source_account_id, destination_account_id, amount, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, 9500000, 'int05-old-reservation', clock_timestamp()-interval '1 day')
                RETURNING transfer_id
                """, Long.class, "TXN" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                owner.customer(), owner.userId(), owner.account(), destination.account());
        observer.update("""
                INSERT INTO banking.limit_transfer_reservation(transfer_id,customer_id,source_account_id,amount,currency,
                    transfer_created_at,gate_at,t1_date,policy_version)
                SELECT transfer_id,customer_id,source_account_id,amount,currency,created_at,created_at,
                    (created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,'fixture' FROM banking.transfer WHERE transfer_id=?
                """, prior);
        observer.update("""
                INSERT INTO banking.limit_usage(customer_id, usage_date, used_amount, transfer_count)
                VALUES (?, (clock_timestamp() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date, 1500000.01, 1)
                """, owner.customer());
        var order = order(owner, destination, "9000000.00", key());
        assertThat(issue(owner, order)).isNotNull();
        assertThat(count("SELECT count(*) FROM banking.limit_transfer_reservation WHERE customer_id=?", owner.customer()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM banking.idempotency_record WHERE idempotency_key=?", order.key())).isZero();
        assertThat(handoff.calls).hasValue(0);
    }

    @Test
    void dispatchDoesNotHoldGuardAndRetryFindsConcurrentIntent() throws Exception {
        Actor owner = actor();
        var order = order(owner, actor(), "3000000.00", key());
        observer.update("""
                INSERT INTO banking.limit_usage(customer_id, usage_date, used_amount, transfer_count)
                VALUES (?, (clock_timestamp() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date, 18000000, 1)
                """, owner.customer());
        var sending = new CountDownLatch(1);
        var finishDispatch = new CountDownLatch(1);
        gateway.callback.set(ref -> {
            sending.countDown();
            try {
                assertThat(finishDispatch.await(8, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interruption);
            }
            return Gateway.ACK;
        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> mvc.perform(create(owner, order)).andReturn());
            try {
                assertThat(sending.await(8, TimeUnit.SECONDS)).isTrue();
                assertNoIntent(owner, order.key());
                observer.update("DELETE FROM banking.limit_usage WHERE customer_id=?", owner.customer());
                mvc.perform(create(owner, order)).andExpect(status().isServiceUnavailable());
            } finally {
                finishDispatch.countDown();
            }
            var delivered = first.get(8, TimeUnit.SECONDS).getResponse();
            assertThat(delivered.getStatus()).isEqualTo(428);
            UUID ref = UUID.fromString(json.readTree(delivered.getContentAsString()).path("challenge")
                    .path("challengeId").asText());
            mvc.perform(verify(owner, ref, gateway.peek(ref))).andExpect(status().isOk());
            mvc.perform(create(owner, order)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
            assertIntent(owner, order.key(), null);
            assertThat(identity.findVerifiedBindingIfPresent(binding(owner, order))).isPresent();
            assertThat(handoff.calls).hasValue(1);
        }
    }

    @Test
    void preflightRejectionRereadsAKeyThatCommittedDuringAccountResolution() throws Exception {
        Actor owner = actor();
        Actor destination = actor();
        var order = order(owner, destination, "2400.00", key());
        try (var lock = observer.getDataSource().getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            lock.setAutoCommit(false);
            var writer = new JdbcTemplate(new SingleConnectionDataSource(lock, true));
            int locker = writer.queryForObject("SELECT pg_backend_pid()", Integer.class);
            writer.execute("LOCK TABLE banking.account IN ACCESS EXCLUSIVE MODE");
            var retry = pool.submit(() -> mvc.perform(create(owner, order)).andReturn());
            try {
                await().atMost(Duration.ofSeconds(6)).until(() -> count("""
                        SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))
                        """, locker) > 0);
                long transfer = writer.queryForObject("""
                        INSERT INTO banking.transfer(reference_no, customer_id, initiated_by_user_id,
                            source_account_id, destination_account_id, amount, correlation_id)
                        VALUES (?, ?, ?, ?, ?, 2400, 'int05-concurrent-winner') RETURNING transfer_id
                        """, Long.class, "TXN" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                        owner.customer(), owner.userId(), owner.account(), destination.account());
                writer.update("""
                        INSERT INTO banking.idempotency_record(idempotency_key, request_hash, transfer_id, expires_at,
                            ttl_t1_seconds, ttl_t1_config_version) VALUES (?, ?, ?, now()+interval '1 day', 86400, 'fixture')
                        """, order.key(), binding(owner, order).requestHash(), transfer);
                writer.update("UPDATE banking.account SET status='LOCKED' WHERE account_id=?", destination.account());
                lock.commit();
            } finally {
                lock.rollback();
            }
            var result = retry.get(8, TimeUnit.SECONDS).getResponse();
            assertThat(result.getStatus()).isEqualTo(409);
            assertThat(json.readTree(result.getContentAsString()).path("code").asText())
                    .isEqualTo("IDEMPOTENCY_IN_PROGRESS");
            assertThat(handoff.calls).hasValue(0);
        }
    }

    @Test
    void duplicateVerifiedProofIsAnInvariantErrorRatherThanAnArbitrarySelection() throws Exception {
        Actor owner = actor();
        var order = order(owner, actor(), STEP_UP_AMOUNT, key());
        UUID original = issue(owner, order);
        mvc.perform(verify(owner, original, gateway.peek(original))).andExpect(status().isOk());
        UUID duplicate = UUID.randomUUID();
        observer.execute("DROP INDEX banking.uq_auth_challenge_active_stepup");
        try {
            observer.update("""
                    INSERT INTO banking.auth_challenge(challenge_ref,user_id,purpose,method,channel,otp_hash,request_hash,
                        idempotency_key,status,attempt_count,expires_at,sent_at,verified_at,correlation_id)
                    SELECT ?,user_id,purpose,method,channel,otp_hash,request_hash,idempotency_key,status,attempt_count,
                        expires_at,sent_at,verified_at,correlation_id FROM banking.auth_challenge WHERE challenge_ref=?
                    """, duplicate, original);
            mvc.perform(create(owner, order)).andExpect(status().is5xxServerError());
            assertNoIntent(owner, order.key());
            assertThat(handoff.calls).hasValue(0);
            assertThat(gateway.calls).hasValue(1);
        } finally {
            observer.update("DELETE FROM banking.auth_challenge WHERE challenge_ref=?", duplicate);
            observer.execute("""
                    CREATE UNIQUE INDEX uq_auth_challenge_active_stepup
                    ON banking.auth_challenge(idempotency_key, request_hash)
                    WHERE purpose='TRANSFER_STEP_UP' AND status IN ('CREATED','SENT','VERIFIED') AND consumed_at IS NULL
                    """);
        }
    }

    @Test
    void invalidTtlStopsIntentAndHigherTtlPersistsItsVersion() {
        Actor owner = actor();
        Actor destination = actor();
        var order = order(owner, destination, "2000.00", key());
        var command = new TransferIntentCandidate.Command(
                new TransferIdentity("TXN12345678TTLVTEST", owner.customer(), owner.userId(),
                        owner.account(), destination.account(), null),
                new TransferAmount(new BigDecimal(order.amount()), "VND"), "", "int05-ttl-version",
                order.key(), binding(owner, order).requestHash(), null);
        assertThatThrownBy(() -> withTtl(23).createIntent(command)).isInstanceOf(IllegalArgumentException.class);
        assertNoIntent(owner, order.key());
        assertThat(handoff.calls).hasValue(0);

        assertThat(withTtl(48).createIntent(command)).isInstanceOf(TransferIntentCandidate.Created.class);
        assertIntent(owner, order.key(), null);
        assertThat(observer.queryForMap("""
                SELECT ttl_t1_seconds, ttl_t1_config_version, expires_at = created_at + interval '48 hours' AS exact
                FROM banking.idempotency_record WHERE idempotency_key=?
                """, order.key())).containsEntry("ttl_t1_seconds", 172800L)
                .containsEntry("ttl_t1_config_version", "transfer-ttl-hours-48").containsEntry("exact", true);
    }

    private TransferCreateAdapters withTtl(int hours) {
        return new TransferCreateAdapters(context.getBean(AccountQuery.class), context.getBean(CustomerQuery.class),
                context.getBean(TransferLimits.class), context.getBean(TransferLimitAdapter.class),
                context.getBean(TransferPersistence.class), new TransferIdentityAdapter(identity),
                new TransferAuditAdapter(context.getBean(AuditModuleApi.class)),
                context.getBean(PlatformTransactionManager.class), context.getBean(TransferTelemetry.class), handoff,
                ZoneId.of("Asia/Ho_Chi_Minh"), hours, TransferLimits.StepUpPolicy.DEFAULT);
    }

    @Test
    void lookupAndMissingLimitConfigurationFailClosed() throws Exception {
        Actor owner = actor();
        var order = order(owner, actor(), STEP_UP_AMOUNT, key());
        observer.execute("REVOKE SELECT ON banking.auth_challenge FROM banking_app");
        try {
            mvc.perform(create(owner, order)).andExpect(status().is5xxServerError());
            assertNoIntent(owner, order.key());
            assertThat(gateway.calls).hasValue(0);
        } finally {
            observer.execute("GRANT SELECT ON banking.auth_challenge TO banking_app");
        }
        observer.update("DELETE FROM banking.limit_config WHERE customer_id=?", owner.customer());
        mvc.perform(create(owner, order)).andExpect(status().is5xxServerError());
        assertNoIntent(owner, order.key());
        assertThat(handoff.calls).hasValue(0);
    }

    private MockHttpServletRequestBuilder create(Actor actor, Order order) {
        return post(TRANSFERS).header("Authorization", actor.bearer()).header("Idempotency-Key", order.key())
                .contentType("application/json").content(order.body());
    }

    private MockHttpServletRequestBuilder verify(Actor actor, UUID ref, String code) {
        return post(VERIFY).header("Authorization", actor.bearer()).contentType("application/json")
                .content("""
                        {"challengeId":"%s","method":"OTP","otp":"%s"}
                        """.formatted(ref, code));
    }

    private UUID issue(Actor actor, Order order) throws Exception {
        var result = mvc.perform(create(actor, order)).andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.challenge.methods[0]").value("OTP"))
                .andExpect(jsonPath("$.challenge.purpose").value("TRANSFER_STEP_UP")).andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .path("challenge").path("challengeId").asText());
    }

    private TransferBinding binding(Actor owner, Order order) {
        var hash = new TransferRequestHash(json, canonicalizer).hash(owner.customer(), owner.account(),
                new Destination(TransferRequestHash.DestinationType.ACCOUNT_NUMBER, order.destination()),
                new TransferAmount(new BigDecimal(order.amount()), "VND"), "");
        return new TransferBinding(owner.userId(), owner.customer(), hash, order.key());
    }

    private MvcResult[] globalKeyRace(Actor a, Order first, Actor b, Order second) throws Exception {
        try (var lock = observer.getDataSource().getConnection(); var pool = Executors.newFixedThreadPool(2)) {
            lock.setAutoCommit(false);
            var writer = new JdbcTemplate(new SingleConnectionDataSource(lock, true));
            int locker = writer.queryForObject("SELECT pg_backend_pid()", Integer.class);
            // Reads pass; both transactions reach the INSERT before either can win the global key.
            writer.execute("LOCK TABLE banking.idempotency_record IN SHARE MODE");
            var x = pool.submit(() -> mvc.perform(create(a, first)).andReturn());
            var y = pool.submit(() -> mvc.perform(create(b, second)).andReturn());
            try {
                await().atMost(Duration.ofSeconds(6)).until(() -> count("""
                        SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))
                        """, locker) == 2);
            } finally {
                lock.rollback();
            }
            return new MvcResult[] {x.get(12, TimeUnit.SECONDS), y.get(12, TimeUnit.SECONDS)};
        }
    }

    private MvcResult[] race(Actor a, Order first, Actor b, Order second) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var x = pool.submit(() -> {
                ready.countDown();
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                return mvc.perform(create(a, first)).andReturn();
            });
            var y = pool.submit(() -> {
                ready.countDown();
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                return mvc.perform(create(b, second)).andReturn();
            });
            return new MvcResult[] {x.get(12, TimeUnit.SECONDS), y.get(12, TimeUnit.SECONDS)};
        }
    }

    private Actor actor() {
        long user = observer.queryForObject("""
                INSERT INTO banking.app_user(username,password_hash,user_type,status) VALUES (?, 'fixture','CUSTOMER','ACTIVE')
                RETURNING user_id
                """, Long.class, key());
        long customer = observer.queryForObject("""
                INSERT INTO banking.customer(user_id,full_name_enc) VALUES (?, decode('00','hex')) RETURNING customer_id
                """, Long.class, user);
        String accountNumber = number();
        long account = account(customer, accountNumber);
        long approver = observer.queryForObject("""
                INSERT INTO banking.app_user(username,password_hash,user_type,status) VALUES (?, 'fixture','STAFF','ACTIVE')
                RETURNING user_id
                """, Long.class, key());
        observer.update("""
                INSERT INTO banking.limit_config(customer_id,per_transaction_limit,daily_limit,effective_from,status,
                    version,created_by,approved_by,approved_at)
                VALUES (?, 40000000, 90000000, '2020-01-01', 'ACTIVE', 1, ?, ?, clock_timestamp())
                """, customer, user, approver);
        String token = key();
        observer.update("""
                INSERT INTO banking.user_role(user_id,role_id) SELECT ?, role_id FROM banking.role WHERE role_code='CUSTOMER'
                """, user);
        observer.update("""
                INSERT INTO banking.session(session_token_hash,csrf_token_hash,user_id,idle_expires_at,absolute_expires_at)
                VALUES (?, ?, ?, clock_timestamp()+interval '20 minutes', clock_timestamp()+interval '1 hour')
                """, HmacService.sha256Hex(token), HmacService.sha256Hex(CSRF), user);
        return new Actor(user, customer, account, token, accountNumber);
    }

    private long account(long customer, String number) {
        var protectedNumber = numbers.protect(number);
        return observer.queryForObject("""
                INSERT INTO banking.account(customer_id,account_no_enc,account_no_hash,account_no_last4,
                                            available_balance,ledger_balance)
                VALUES (?, ?, ?, ?, 90000000, 90000000) RETURNING account_id
                """, Long.class, customer, protectedNumber.encrypted(), protectedNumber.blindIndex(),
                protectedNumber.accountNumberLast4());
    }

    private void terminal(String key, int status, String body) {
        String code = status == 201 ? "COMPLETED" : "FAILED";
        observer.update("""
                UPDATE banking.idempotency_record SET status=?, response_status=?, response_body=?::jsonb,
                       completed_at=clock_timestamp(), created_at=clock_timestamp()-interval '2 days',
                       expires_at=clock_timestamp()-interval '1 day',
                       ttl_terminal_seconds=86400, ttl_terminal_config_version='fixture'
                 WHERE idempotency_key=?
                """, code, status, body, key);
        observer.update("""
                UPDATE banking.transfer SET status=?,
                    completed_at=CASE WHEN ?='COMPLETED' THEN clock_timestamp() END,
                    failed_at=CASE WHEN ?='FAILED' THEN clock_timestamp() END,
                    failure_code=CASE WHEN ?='FAILED' THEN 'INSUFFICIENT_BALANCE' END
                 WHERE transfer_id=(SELECT transfer_id FROM banking.idempotency_record WHERE idempotency_key=?)
                """, code, code, code, code, key);
    }

    private long count(String sql, Object... args) {
        return observer.queryForObject(sql, Long.class, args);
    }

    private void assertNoIntent(Actor actor, String key) {
        assertThat(count("SELECT count(*) FROM banking.transfer WHERE customer_id=?", actor.customer())).isZero();
        assertThat(count("SELECT count(*) FROM banking.fraud_transfer_intent_history WHERE customer_id=?",
                actor.customer())).isZero();
        assertThat(count("SELECT count(*) FROM banking.idempotency_record WHERE idempotency_key=?", key)).isZero();
        assertThat(count("SELECT count(*) FROM banking.limit_transfer_reservation WHERE customer_id=?", actor.customer())).isZero();
        assertThat(count("SELECT count(*) FROM banking.audit_log WHERE action='TRANSFER_CREATED' AND actor_user_id=?",
                actor.userId())).isZero();
    }

    private void assertIntent(Actor actor, String key, UUID ref) {
        assertThat(count("""
                SELECT count(*) FROM banking.transfer t JOIN banking.idempotency_record i USING(transfer_id)
                JOIN banking.limit_transfer_reservation r USING(transfer_id)
                JOIN banking.audit_log l ON l.entity_id=t.reference_no AND l.action='TRANSFER_CREATED'
                LEFT JOIN banking.auth_challenge c ON c.challenge_id=t.step_up_challenge_id
                WHERE i.idempotency_key=? AND t.customer_id=? AND t.status='PENDING' AND i.status='RECEIVED'
                  AND r.state='PENDING' AND i.response_status IS NULL AND i.response_body IS NULL
                  AND (c.challenge_ref IS NOT DISTINCT FROM ?::uuid) AND (c.challenge_id IS NULL OR c.consumed_at IS NOT NULL)
                  AND l.correlation_id=t.correlation_id AND l.actor_user_id=t.initiated_by_user_id
                  AND l.amount=t.amount AND l.currency=t.currency AND l.after_state->>'status'='PENDING'
                """, key, actor.customer(), ref)).isEqualTo(1);
        assertThat(observer.queryForObject("SELECT available_balance FROM banking.account WHERE account_id=?",
                BigDecimal.class, actor.account())).isEqualByComparingTo("90000000");
        assertThat(count("SELECT count(*) FROM banking.limit_usage WHERE customer_id=?", actor.customer())).isZero();
        assertThat(count("SELECT count(*) FROM banking.ledger_entry")).isZero();
        assertThat(count("SELECT count(*) FROM banking.notification WHERE customer_id=? AND notification_type='TRANSFER_RESULT'",
                actor.customer())).isZero();
    }

    private void rejectInsert(String table, String condition) {
        observer.execute("""
                CREATE FUNCTION banking.int05_reject() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF %s THEN RAISE EXCEPTION 'INT05 injected failure' USING ERRCODE = '23514'; END IF;
                    RETURN NEW;
                END $$;
                CREATE TRIGGER int05_reject BEFORE INSERT ON banking.%s
                FOR EACH ROW EXECUTE FUNCTION banking.int05_reject();
                """.formatted(condition, table));
    }

    private void removeFailure(String table) {
        observer.execute("DROP TRIGGER int05_reject ON banking." + table);
        observer.execute("DROP FUNCTION banking.int05_reject()");
    }

    private static String key() { return "int05-" + UUID.randomUUID(); }

    private static final AtomicInteger NUMBER = new AtomicInteger(1000);
    private static String number() { return "001500" + NUMBER.incrementAndGet(); }

    private static Order order(Actor source, Actor destination, String amount, String key) {
        String body = """
                {"sourceAccountId":"%d","destination":{"accountNumber":"%s"},"amount":"%s"}
                """.formatted(source.account(), destination.number(), amount);
        return new Order(key, body, amount, destination.number());
    }

    record Actor(long userId, long customer, long account, String token, String number) {
        String bearer() { return "Bearer " + token; }
    }
    record Order(String key, String body, String amount, String destination) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean Gateway int05Gateway() { return new Gateway(); }
        @Bean Handoff int05Handoff() { return new Handoff(); }
        @Bean TransferReadPort unusedRead() { return ref -> Optional.empty(); }
        @Bean FraudFlagReadPort unusedFraud() { return id -> java.util.List.of(); }
        @Bean AccountOwnershipPort unusedOwnership() { return (account, customer) -> AccountOwnershipPort.Ownership.NOT_FOUND; }
        @Bean TransferStaffScope unusedStaffScope() { return (actor, transfer) -> false; }
    }

    static class Handoff implements PostCommitSettlement {
        private final AtomicInteger calls = new AtomicInteger();
        @Override
        public Settlement settle(long transferId) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            JdbcTemplate independent = new JdbcTemplate(new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), "banking_migrator", "test_migrator"));
            assertThat(independent.queryForObject("""
                    SELECT count(*) FROM banking.transfer t
                    JOIN banking.idempotency_record i USING(transfer_id)
                    JOIN banking.limit_transfer_reservation r USING(transfer_id)
                    JOIN banking.audit_log l ON l.entity_id=t.reference_no AND l.action='TRANSFER_CREATED'
                    WHERE t.transfer_id=? AND t.status='PENDING' AND r.state='PENDING' AND i.status='RECEIVED'
                    """, Long.class, transferId)).isEqualTo(1);
            calls.incrementAndGet();
            throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    static class Gateway implements OtpGateway {
        static final Result ACK = new Result(Outcome.ACCEPTED, Failure.NONE);
        private final Map<String, char[]> codes = new ConcurrentHashMap<>();
        private final AtomicReference<java.util.function.Function<String, Result>> callback = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Result sendOtp(String ref, long userId, String channel, char[] otp, Instant expiresAt, String correlationId) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet();
            codes.put(ref, otp.clone());
            return callback.get().apply(ref);
        }

        String peek(UUID ref) { return new String(codes.get(ref.toString())); }

        void reset() {
            codes.values().forEach(code -> Arrays.fill(code, '\0'));
            codes.clear();
            calls.set(0);
            callback.set(ref -> ACK);
        }
    }
}
