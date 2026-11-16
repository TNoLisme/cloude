package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.identity.adapter.out.JdbcChallengeRepository;
import com.simulatedbank.banking.identity.adapter.out.SecurityLogAuditAdapter;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.application.AuthAuditPort;
import com.simulatedbank.banking.identity.application.OtpChallengeService;
import com.simulatedbank.banking.identity.application.OtpCodeGenerator;
import com.simulatedbank.banking.identity.application.OtpDispatchService;
import com.simulatedbank.banking.identity.application.OtpExpiryRecoveryService;
import com.simulatedbank.banking.notification.api.OtpGateway;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Consumer-side delivery contract only; no production provider or durable redelivery is implied. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(OtpDeliveryPostgreSqlIntegrationTest.DeliveryTestConfig.class)
class OtpDeliveryPostgreSqlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("otp_delivery")
            .withUsername("postgres")
            .withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
    }

    @TestConfiguration
    static class DeliveryTestConfig {
        @Bean
        @Primary
        CapturingGenerator deliveryOtpGenerator() {
            return new CapturingGenerator();
        }

        @Bean
        TestGateway deliveryGateway(DataSource source, PlatformTransactionManager manager) {
            return new TestGateway(new JdbcTemplate(source), manager);
        }

        @Bean
        @Primary
        CapturingAudit deliveryAudit(SecurityLogAuditAdapter delegate) {
            return new CapturingAudit(delegate);
        }
    }

    @Autowired
    private OtpChallengeService challenges;
    @Autowired
    private CapturingGenerator generator;
    @Autowired
    private TestGateway gateway;
    @Autowired
    private CapturingAudit audit;
    @Autowired
    private Argon2PasswordHasher hasher;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager manager;
    @Autowired
    private OtpExpiryRecoveryService recovery;
    @Autowired
    private Clock clock;
    @Autowired
    private AppProperties properties;
    @Autowired
    private MeterRegistry meters;
    @MockitoSpyBean
    private JdbcChallengeRepository repository;

    private static final AtomicInteger USERS = new AtomicInteger();
    private static final String CORRELATION = "delivery-contract-test";
    private static final Logger IDENTITY_LOGGER =
            (Logger) LoggerFactory.getLogger("com.simulatedbank.banking.identity");
    private static final OtpGateway.Result ACCEPTED =
            new OtpGateway.Result(OtpGateway.Outcome.ACCEPTED, OtpGateway.Failure.NONE);
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private long userId;
    private String username;
    private final SafeLogCapture logs = new SafeLogCapture();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(manager);
        username = "delivery-user-" + USERS.incrementAndGet();
        userId = tx.execute(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                            + "VALUES (?, ?, 'CUSTOMER', 'ACTIVE')", username,
                    hasher.hash("test-only-password".toCharArray()));
            return jdbc.queryForObject("SELECT user_id FROM banking.app_user WHERE username = ?",
                    Long.class, username);
        });
        generator.clear();
        gateway.reset();
        audit.reset();
        logs.start();
        // Capture the component under test, not background pool failures from other cached test contexts.
        IDENTITY_LOGGER.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        IDENTITY_LOGGER.detachAppender(logs);
        logs.stop();
        gateway.clearSecrets();
        generator.clear();
    }

    @Test
    void acceptedDeliverySeesCommittedRowOutsideTransactionThenMarksSentAndWipesOtp() {
        var view = issue("accepted");
        assertThat(gateway.calls).hasSize(1);
        Delivery delivered = gateway.calls.getFirst();
        assertThat(delivered.transactionActive()).isFalse();
        assertThat(delivered.visibleRow().get("status")).isEqualTo("CREATED");
        assertThat(delivered.visibleRow().get("sent_at")).isNull();
        assertThat(delivered.challengeRef()).isEqualTo(view.challengeRef());
        assertThat(delivered.recipientUserId()).isEqualTo(userId);
        assertThat(delivered.channel()).isEqualTo("SMS");
        assertThat(delivered.expiresAt()).isEqualTo(view.expiresAt());
        assertThat(delivered.correlationId()).isEqualTo(CORRELATION);
        assertThat(hasher.matches((String) row(view.challengeRef()).get("otp_hash"), delivered.otp())).isTrue();
        assertThat(row(view.challengeRef()).get("status")).isEqualTo("SENT");
        assertThat(row(view.challengeRef()).get("sent_at")).isNotNull();
        assertThat(audit.createdTransactions).containsExactly(true);
        assertThat(generator.generated).hasSize(1);
        assertThat(generator.generated.getFirst()).containsOnly('\0');
        assertNoSessions();
        assertSafe(view.challengeRef(), view.toString());
    }

    @Test
    void throwingC2AuditRollsBackInsertAndNeverCallsGateway() {
        audit.failCreated = true;
        assertThatThrownBy(() -> issue("audit-rollback")).isInstanceOf(AuditRejected.class);
        assertThat(challengeCount()).isZero();
        assertThat(gateway.calls).isEmpty();
        assertThat(audit.createdTransactions).containsExactly(true);
        assertThat(generator.generated.getFirst()).containsOnly('\0');
        assertNoSessions();
    }

    @Test
    void missingProductionGatewayFailsClosedWithoutInventingSuccess() {
        var absentProvider = new StaticListableBeanFactory().getBeanProvider(OtpGateway.class);
        var unconfiguredDispatch = new OtpDispatchService(repository, absentProvider, manager, clock, meters);
        var unconfiguredService = new OtpChallengeService(
                repository, audit, hasher, generator, clock, properties, manager, unconfiguredDispatch,
                org.mockito.Mockito.mock(
                        com.simulatedbank.banking.identity.application.LoginSessionService.class),
                new com.simulatedbank.banking.identity.application.AuthMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        assertBusiness(() -> unconfiguredService.requestChallenge(request("unconfigured")),
                ErrorCode.OTP_DISPATCH_FAILED);

        assertThat(challengeCount()).isEqualTo(1);
        Map<String, Object> persisted = jdbc.queryForMap(
                "SELECT * FROM banking.auth_challenge WHERE user_id = ?", userId);
        assertThat(persisted.get("status")).isEqualTo("CREATED");
        assertThat(persisted.get("sent_at")).isNull();
        assertThat(gateway.calls).isEmpty();
        assertThat(generator.generated.getFirst()).containsOnly('\0');
        assertNoSessions();
    }

    static Stream<Arguments> failedResults() {
        return Stream.of(
                Arguments.of("timeout", new OtpGateway.Result(
                        OtpGateway.Outcome.RETRYABLE, OtpGateway.Failure.TIMEOUT)),
                Arguments.of("unavailable", new OtpGateway.Result(
                        OtpGateway.Outcome.RETRYABLE, OtpGateway.Failure.UNAVAILABLE)),
                Arguments.of("throttled", new OtpGateway.Result(
                        OtpGateway.Outcome.RETRYABLE, OtpGateway.Failure.THROTTLED)),
                Arguments.of("rejected", new OtpGateway.Result(
                        OtpGateway.Outcome.FAILED, OtpGateway.Failure.REJECTED)),
                Arguments.of("invalid", new OtpGateway.Result(
                        OtpGateway.Outcome.FAILED, OtpGateway.Failure.INVALID_RESPONSE)),
                Arguments.of("null-result", null),
                Arguments.of("contradictory", new OtpGateway.Result(
                        OtpGateway.Outcome.ACCEPTED, OtpGateway.Failure.REJECTED)),
                Arguments.of("missing-failure", new OtpGateway.Result(OtpGateway.Outcome.ACCEPTED, null)),
                Arguments.of("missing-outcome", new OtpGateway.Result(null, OtpGateway.Failure.NONE)));
    }

    @ParameterizedTest(name = "{0} fails closed without retry")
    @MethodSource("failedResults")
    void gatewayFailureKeepsCreatedAndCorrectOtpCannotVerify(String scenario, OtpGateway.Result result) {
        gateway.behavior = ignored -> result;
        assertBusiness(() -> issue(scenario), ErrorCode.OTP_DISPATCH_FAILED);
        assertUndeliveredCannotVerify();
    }

    @Test
    void throwingTimeoutDoesNotExposeProviderMessageOrAutomaticallyRetry() {
        gateway.behavior = delivery -> {
            throw new IllegalStateException("provider-private " + new String(delivery.otp()) + " " + username,
                    new SocketTimeoutException("private destination +15555550101"));
        };
        assertBusiness(() -> issue("throwing-timeout"), ErrorCode.OTP_DISPATCH_FAILED);
        assertUndeliveredCannotVerify();
        assertThat(logs.text()).doesNotContain("provider-private", "+15555550101", "private destination");
        assertThat(logs.events).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    void repeatedRequestReturnsSameSentChallengeWithoutAnotherExternalInvocation() {
        var first = issue("duplicate");
        var second = issue("duplicate");
        assertThat(second.challengeRef()).isEqualTo(first.challengeRef());
        assertThat(challengeCount()).isEqualTo(1);
        assertThat(gateway.calls).hasSize(1);
        assertThat(audit.createdTransactions).containsExactly(true);
        assertThat(generator.generated).allSatisfy(code -> assertThat(code).containsOnly('\0'));
    }

    @Test
    void concurrentRequestsShareOneCommittedRowAndOneExternalInvocation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        gateway.behavior = ignored -> {
            entered.countDown();
            await(release);
            return ACCEPTED;
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> issue("concurrent"));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var duplicate = pool.submit(() -> {
                    assertBusiness(() -> issue("concurrent"), ErrorCode.OTP_DISPATCH_FAILED);
                });
                duplicate.get(10, TimeUnit.SECONDS);
                String ref = gateway.calls.getFirst().challengeRef();
                assertThat(challengeCount()).isEqualTo(1);
                assertThat(gateway.calls).hasSize(1);
                assertThat(row(ref).get("status")).isEqualTo("CREATED");
                release.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS).challengeRef()).isEqualTo(ref);
                assertThat(row(ref).get("status")).isEqualTo("SENT");
                assertThat(audit.createdTransactions).containsExactly(true);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void ambientCallerRollbackDoesNotUndoOwnChallengeAndGatewayRunsOutsideCallerTransaction() {
        var ref = new AtomicReference<String>();
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            jdbc.update("UPDATE banking.app_user SET failed_attempt_count = 1 WHERE user_id = ?", userId);
            ref.set(issue("outer-rollback").challengeRef());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            throw new CallerRolledBack();
        })).isInstanceOf(CallerRolledBack.class);
        assertThat(row(ref.get()).get("status")).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT failed_attempt_count FROM banking.app_user WHERE user_id = ?",
                Integer.class, userId)).isZero();
        assertThat(gateway.calls).hasSize(1);
        assertThat(gateway.calls.getFirst().transactionActive()).isFalse();
        assertThat(gateway.calls.getFirst().visibleRow().get("status")).isEqualTo("CREATED");
        assertNoSessions();
    }

    @Test
    void acceptedProviderWithFailedLocalAcknowledgementStaysCreatedAndCannotBeRedispatchedByReplay() {
        doThrow(new DataAccessResourceFailureException("private database acknowledgement failure"))
                .when(repository).markSent(anyLong(), anyString());
        assertThatThrownBy(() -> issue("ack-failure"))
                .isInstanceOfSatisfying(BankingBusinessException.class, failure ->
                        assertThat(failure.code()).isIn(
                                ErrorCode.OTP_DISPATCH_FAILED, ErrorCode.SERVICE_UNAVAILABLE));
        assertUndeliveredCannotVerify();
        String ref = gateway.calls.getFirst().challengeRef();
        String hash = (String) row(ref).get("otp_hash");
        assertBusiness(() -> issue("ack-failure"), ErrorCode.OTP_DISPATCH_FAILED);
        assertThat(gateway.calls).hasSize(1);
        assertThat(challengeCount()).isEqualTo(1);
        assertThat(row(ref).get("status")).isEqualTo("CREATED");
        assertThat(row(ref).get("otp_hash")).isEqualTo(hash);
        assertThat(audit.createdTransactions).containsExactly(true);
        assertThat(logs.text()).doesNotContain("private database acknowledgement failure");
    }

    @Test
    void expiryWhileProviderIsInFlightPreventsSentAcknowledgementAndVerification() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        gateway.behavior = ignored -> {
            entered.countDown();
            await(release);
            return ACCEPTED;
        };
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> {
                try {
                    issue("expire-in-flight");
                    return (ErrorCode) null;
                } catch (BankingBusinessException rejected) {
                    return rejected.code();
                }
            });
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                String ref = gateway.calls.getFirst().challengeRef();
                expire(ref);
                release.countDown();
                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.OTP_DISPATCH_FAILED);
                assertThat(row(ref).get("status")).isEqualTo("CREATED");
                assertThat(row(ref).get("sent_at")).isNull();
                assertBusiness(() -> verify(ref, gateway.calls.getFirst().otp()), ErrorCode.AUTH_OTP_EXPIRED);
                assertThat(row(ref).get("status")).isEqualTo("EXPIRED");
                assertNoSessions();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void acceptedCodeVerifiesOnlyOnceUnderConcurrentVerificationAndCannotReplay() throws Exception {
        var view = issue("verify-once");
        char[] otp = gateway.calls.getFirst().otp();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<ErrorCode> verifier = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    verify(view.challengeRef(), otp);
                    return null;
                } catch (BankingBusinessException rejected) {
                    return rejected.code();
                }
            };
            var first = pool.submit(verifier);
            var second = pool.submit(verifier);
            ErrorCode firstCode = first.get(15, TimeUnit.SECONDS);
            ErrorCode secondCode = second.get(15, TimeUnit.SECONDS);
            assertThat(Arrays.asList(firstCode, secondCode)).containsExactlyInAnyOrder(
                    null, ErrorCode.INVALID_STATE_TRANSITION);
        }
        assertThat(row(view.challengeRef()).get("status")).isEqualTo("VERIFIED");
        assertThat(((Number) row(view.challengeRef()).get("attempt_count")).intValue()).isEqualTo(1);
        assertBusiness(() -> verify(view.challengeRef(), otp), ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(gateway.calls).hasSize(1);
        assertNoSessions();
        assertSafe(view.challengeRef(), view.toString());
    }

    @Test
    void expiryRecoveryExpiresOnlyUnconsumedActiveChallengesWithoutSendingAnything() {
        var sent = issue("recovery-sent");
        var verified = issue("recovery-verified");
        verify(verified.challengeRef(), gateway.calls.getLast().otp());
        var consumed = issue("recovery-consumed");
        verify(consumed.challengeRef(), gateway.calls.getLast().otp());
        Map<String, Object> consumedRow = row(consumed.challengeRef());
        tx.executeWithoutResult(ignored -> challenges.consumeVerified(new StepUpChallengePort.ConsumeCommand(
                consumed.challengeRef(), userId, StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP,
                (String) consumedRow.get("request_hash"), (String) consumedRow.get("idempotency_key"))));
        Object consumedAt = row(consumed.challengeRef()).get("consumed_at");
        var locked = issue("recovery-locked");
        var cancelled = issue("recovery-cancelled");
        tx.executeWithoutResult(ignored -> {
            jdbc.update("UPDATE banking.auth_challenge SET status = 'LOCKED', attempt_count = max_attempts "
                    + "WHERE challenge_ref = ?::uuid", locked.challengeRef());
            jdbc.update("UPDATE banking.auth_challenge SET status = 'CANCELLED' "
                    + "WHERE challenge_ref = ?::uuid", cancelled.challengeRef());
        });
        var future = issue("recovery-future");
        gateway.behavior = ignored -> new OtpGateway.Result(
                OtpGateway.Outcome.RETRYABLE, OtpGateway.Failure.UNAVAILABLE);
        assertBusiness(() -> issue("recovery-created"), ErrorCode.OTP_DISPATCH_FAILED);
        String created = gateway.calls.getLast().challengeRef();
        for (String ref : List.of(created, sent.challengeRef(), verified.challengeRef(),
                consumed.challengeRef(), locked.challengeRef(), cancelled.challengeRef())) {
            expire(ref);
        }
        int invocations = gateway.calls.size();

        assertThat(recovery.recoverExpired()).isGreaterThanOrEqualTo(3);

        for (String ref : List.of(created, sent.challengeRef(), verified.challengeRef())) {
            assertThat(row(ref).get("status")).isEqualTo("EXPIRED");
            assertThat(row(ref).get("consumed_at")).isNull();
        }
        assertThat(row(created).get("sent_at")).isNull();
        assertThat(row(consumed.challengeRef()).get("status")).isEqualTo("VERIFIED");
        assertThat(row(consumed.challengeRef()).get("consumed_at")).isEqualTo(consumedAt);
        assertThat(row(locked.challengeRef()).get("status")).isEqualTo("LOCKED");
        assertThat(row(cancelled.challengeRef()).get("status")).isEqualTo("CANCELLED");
        assertThat(row(future.challengeRef()).get("status")).isEqualTo("SENT");
        assertThat(gateway.calls).hasSize(invocations);
        assertNoSessions();
        assertSafe(created, "");
    }

    @Test
    void concurrentExpiryWorkersRecoverCommittedRowsWithoutRedelivery() throws Exception {
        gateway.behavior = ignored -> new OtpGateway.Result(
                OtpGateway.Outcome.RETRYABLE, OtpGateway.Failure.UNAVAILABLE);
        for (int i = 0; i < 3; i++) {
            String key = "concurrent-recovery-" + i;
            assertBusiness(() -> issue(key), ErrorCode.OTP_DISPATCH_FAILED);
            expire(gateway.calls.getLast().challengeRef());
        }
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> worker = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return recovery.recoverExpired();
            };
            var first = pool.submit(worker);
            var second = pool.submit(worker);
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS))
                    .isGreaterThanOrEqualTo(3);
        }
        assertThat(gateway.calls).hasSize(3);
        for (Delivery delivery : gateway.calls) {
            assertThat(row(delivery.challengeRef()).get("status")).isEqualTo("EXPIRED");
            assertThat(row(delivery.challengeRef()).get("sent_at")).isNull();
            assertBusiness(() -> verify(delivery.challengeRef(), delivery.otp()), ErrorCode.AUTH_OTP_EXPIRED);
        }
        assertThat(recovery.recoverExpired()).isZero();
        assertThat(gateway.calls).hasSize(3);
        assertNoSessions();
    }

    private void assertUndeliveredCannotVerify() {
        assertThat(gateway.calls).hasSize(1);
        Delivery delivery = gateway.calls.getFirst();
        assertThat(delivery.transactionActive()).isFalse();
        assertThat(delivery.visibleRow().get("status")).isEqualTo("CREATED");
        assertThat(row(delivery.challengeRef()).get("status")).isEqualTo("CREATED");
        assertThat(row(delivery.challengeRef()).get("sent_at")).isNull();
        assertBusiness(() -> verify(delivery.challengeRef(), delivery.otp()), ErrorCode.OTP_DISPATCH_FAILED);
        assertThat(row(delivery.challengeRef()).get("verified_at")).isNull();
        assertThat(((Number) row(delivery.challengeRef()).get("attempt_count")).intValue()).isZero();
        assertThat(gateway.calls).hasSize(1);
        assertThat(generator.generated.getFirst()).containsOnly('\0');
        assertNoSessions();
        assertSafe(delivery.challengeRef(), "");
    }

    private StepUpChallengePort.ChallengeView issue(String key) {
        return challenges.requestChallenge(request(key));
    }

    private StepUpChallengePort.ChallengeRequest request(String key) {
        String binding = username + "-" + key;
        return new StepUpChallengePort.ChallengeRequest(
                StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP, userId,
                HmacService.sha256Hex("payload-" + binding), binding, CORRELATION);
    }

    private void verify(String ref, char[] otp) {
        challenges.verifyChallenge(new StepUpChallengePort.VerifyCommand(ref, userId,
                StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP, otp.clone(), CORRELATION));
    }

    private Map<String, Object> row(String ref) {
        return jdbc.queryForMap("SELECT * FROM banking.auth_challenge WHERE challenge_ref = ?::uuid", ref);
    }

    private int challengeCount() {
        return jdbc.queryForObject("SELECT count(*) FROM banking.auth_challenge WHERE user_id = ?",
                Integer.class, userId);
    }

    private void assertNoSessions() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.session WHERE user_id = ?",
                Integer.class, userId)).isZero();
    }

    private void assertSafe(String ref, String response) {
        String persisted = jdbc.queryForObject(
                "SELECT to_jsonb(c)::text FROM banking.auth_challenge c WHERE challenge_ref = ?::uuid",
                String.class, ref);
        String hash = (String) row(ref).get("otp_hash");
        assertThat(hash).startsWith("$argon2id$");
        assertThat(logs.text()).doesNotContain(hash, username);
        assertThat(response).doesNotContain(hash);
        for (Delivery delivered : gateway.calls) {
            String secret = new String(delivered.otp());
            assertThat(persisted).doesNotContain(secret);
            assertThat(response).doesNotContain(secret);
            assertThat(logs.text()).doesNotContain(secret);
        }
    }

    private void expire(String ref) {
        tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.auth_challenge SET created_at = now() - interval '10 minutes', "
                        + "expires_at = now() - interval '1 second' WHERE challenge_ref = ?::uuid", ref));
    }

    private static void assertBusiness(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BankingBusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo(expected));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Delivery test barrier timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Delivery test interrupted", interrupted);
        }
    }

    private record Delivery(String challengeRef, long recipientUserId, String channel, char[] otp,
            Instant expiresAt, String correlationId, boolean transactionActive, Map<String, Object> visibleRow) {
        @Override
        public String toString() {
            return "Delivery[redacted]";
        }
    }

    static final class TestGateway implements OtpGateway {
        final List<Delivery> calls = new CopyOnWriteArrayList<>();
        volatile Function<Delivery, Result> behavior = ignored -> ACCEPTED;
        private final JdbcTemplate jdbc;
        private final TransactionTemplate independent;

        TestGateway(JdbcTemplate jdbc, PlatformTransactionManager manager) {
            this.jdbc = jdbc;
            independent = new TransactionTemplate(manager);
            independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        @Override
        public Result sendOtp(String ref, long recipientUserId, String channel, char[] otp,
                Instant expiresAt, String correlationId) {
            boolean active = TransactionSynchronizationManager.isActualTransactionActive();
            Map<String, Object> visible = independent.execute(ignored -> jdbc.queryForMap(
                    "SELECT status, sent_at FROM banking.auth_challenge WHERE challenge_ref = ?::uuid", ref));
            // Test-only copy: production providers must not retain the borrowed secret.
            Delivery delivery = new Delivery(ref, recipientUserId, channel, otp.clone(), expiresAt,
                    correlationId, active, visible);
            calls.add(delivery);
            return behavior.apply(delivery);
        }

        void reset() {
            clearSecrets();
            calls.clear();
            behavior = ignored -> ACCEPTED;
        }

        void clearSecrets() {
            calls.forEach(delivery -> Arrays.fill(delivery.otp(), '\0'));
        }
    }

    static final class CapturingGenerator implements OtpCodeGenerator {
        final List<char[]> generated = new CopyOnWriteArrayList<>();
        private final AtomicInteger sequence = new AtomicInteger(827360);

        @Override
        public char[] generate() {
            char[] code = Integer.toString(sequence.incrementAndGet()).toCharArray();
            generated.add(code);
            return code;
        }

        void clear() {
            generated.forEach(code -> Arrays.fill(code, '\0'));
            generated.clear();
        }
    }

    static final class CapturingAudit implements AuthAuditPort {
        final List<Boolean> createdTransactions = new CopyOnWriteArrayList<>();
        volatile boolean failCreated;
        private final SecurityLogAuditAdapter delegate;

        CapturingAudit(SecurityLogAuditAdapter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void recordInTransaction(AuthAuditEvent event, String correlationId) {
            if (event.action() == AuthAction.AUTH_CHALLENGE_CREATED) {
                createdTransactions.add(TransactionSynchronizationManager.isActualTransactionActive());
                if (failCreated) {
                    throw new AuditRejected();
                }
            }
            delegate.recordInTransaction(event, correlationId);
        }

        @Override
        public void recordStandalone(AuthAuditEvent event, String correlationId) {
            delegate.recordStandalone(event, correlationId);
        }

        void reset() {
            failCreated = false;
            createdTransactions.clear();
        }
    }

    private static final class SafeLogCapture extends AppenderBase<ILoggingEvent> {
        final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            events.add(event);
        }

        String text() {
            return String.join(" ", events.stream().map(ILoggingEvent::getFormattedMessage).toList());
        }
    }

    private static final class AuditRejected extends RuntimeException {
    }

    private static final class CallerRolledBack extends RuntimeException {
    }
}
