package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.application.ChallengeRepository;
import com.simulatedbank.banking.identity.application.OtpDispatchService;
import com.simulatedbank.banking.notification.api.OtpGateway;
import com.simulatedbank.banking.notification.api.OtpGateway.Failure;
import com.simulatedbank.banking.notification.api.OtpGateway.Outcome;
import com.simulatedbank.banking.notification.api.OtpGateway.Result;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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

/** Unit result vectors plus real PostgreSQL evidence for suspension, commit and hash fencing. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OtpDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    private static final String REF = "11111111-2222-4333-8444-555555555555";
    private static final String VERIFIER = "$argon2id$synthetic-verifier-never-log";
    private static final String PROVIDER_SECRET = "123456 provider-password recipient@example.invalid";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("digital_banking")
            .withUsername("postgres")
            .withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
    }

    @TestConfiguration
    static class GatewayConfig {
        @Bean
        TestGateway testOtpGateway() {
            return new TestGateway();
        }
    }

    /** Test-only adapter never retains the borrowed OTP. */
    static class TestGateway implements OtpGateway {
        int calls;
        boolean transactionActive;
        Result result = new Result(Outcome.ACCEPTED, Failure.NONE);
        RuntimeException failure;
        Runnable duringSend = () -> { };

        @Override
        public Result sendOtp(String challengeRef, long recipientUserId, String channel, char[] otp,
                Instant expiresAt, String correlationId) {
            calls++;
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            assertThat(challengeRef).isNotBlank();
            assertThat(recipientUserId).isPositive();
            assertThat(channel).isEqualTo("SMS");
            assertThat(otp).containsExactly('1', '2', '3', '4', '5', '6');
            assertThat(expiresAt).isNotNull();
            assertThat(correlationId).isEqualTo("corr-dispatch");
            duringSend.run();
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ChallengeRepository realChallenges;
    @Autowired
    private OtpDispatchService realDispatch;
    @Autowired
    private TestGateway realGateway;
    @Autowired
    private DataSource dataSource;

    private ChallengeRepository challenges;
    private ObjectProvider<OtpGateway> provider;
    private TestGateway gateway;
    private OtpDispatchService dispatch;
    private SimpleMeterRegistry metrics;
    private TransactionTemplate tx;
    private JdbcTemplate jdbc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        challenges = mock(ChallengeRepository.class);
        provider = mock(ObjectProvider.class);
        gateway = new TestGateway();
        when(provider.getIfUnique()).thenReturn(gateway);
        when(challenges.markSent(7L, VERIFIER)).thenReturn(true);
        metrics = new SimpleMeterRegistry();
        dispatch = new OtpDispatchService(challenges, provider, transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC), metrics);
        tx = new TransactionTemplate(transactionManager);
        jdbc = new JdbcTemplate(dataSource);
        realGateway.calls = 0;
        realGateway.result = new Result(Outcome.ACCEPTED, Failure.NONE);
        realGateway.failure = null;
        realGateway.duringSend = () -> { };
    }

    private static ChallengeRepository.ChallengeSnapshot snapshot(String status, Instant expiresAt) {
        return new ChallengeRepository.ChallengeSnapshot(7L, REF, 101L, "LOGIN_NEW_DEVICE", "OTP", "SMS",
                status, 0, 5, expiresAt, NOW.minusSeconds(30), null, null, null, null, VERIFIER, null);
    }

    private void deliver() {
        dispatch.deliver(snapshot("CREATED", NOW.plusSeconds(300)), "123456".toCharArray(), "corr-dispatch");
    }

    private void assertDispatchFailure(Runnable attempt) {
        assertThatThrownBy(attempt::run).isInstanceOfSatisfying(BankingBusinessException.class, failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.OTP_DISPATCH_FAILED);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(failure.extensions()).isEmpty();
            assertThat(failure.getMessage()).isEqualTo(ErrorCode.OTP_DISPATCH_FAILED.detail());
            assertThat(failure.toString()).doesNotContain(PROVIDER_SECRET, "123456", VERIFIER);
        });
    }

    private void assertMetric(Outcome outcome, Failure failure) {
        assertThat(metrics.get("auth.otp.delivery").tags("outcome", outcome.name(), "failure", failure.name())
                .counter().count()).isEqualTo(1);
        assertThat(metrics.getMeters()).hasSize(1);
        assertThat(metrics.getMeters().getFirst().getId().getTags()).hasSize(2);
    }

    @Test
    void acceptedMarksOnlyTheExpectedVerifierAndBorrowsTheOriginalArray() {
        char[] otp = "123456".toCharArray();
        OtpGateway checkingGateway = (ref, user, channel, borrowed, expiry, correlation) -> {
            assertThat(ref).isEqualTo(REF);
            assertThat(user).isEqualTo(101L);
            assertThat(channel).isEqualTo("SMS");
            assertThat(borrowed).isSameAs(otp);
            assertThat(expiry).isEqualTo(NOW.plusSeconds(300));
            assertThat(correlation).isEqualTo("corr-dispatch");
            return new Result(Outcome.ACCEPTED, Failure.NONE);
        };
        when(provider.getIfUnique()).thenReturn(checkingGateway);

        dispatch.deliver(snapshot("CREATED", NOW.plusSeconds(300)), otp, "corr-dispatch");

        verify(challenges).markSent(7L, VERIFIER);
        verify(provider).getIfUnique();
        assertThat(otp).containsExactly('1', '2', '3', '4', '5', '6');
        assertMetric(Outcome.ACCEPTED, Failure.NONE);
    }

    static Stream<Arguments> rejectedResults() {
        return Stream.of(
                Arguments.of(null, Outcome.FAILED, Failure.INVALID_RESPONSE),
                Arguments.of(new Result(null, Failure.NONE), Outcome.FAILED, Failure.INVALID_RESPONSE),
                Arguments.of(new Result(Outcome.ACCEPTED, null), Outcome.FAILED, Failure.INVALID_RESPONSE),
                Arguments.of(new Result(Outcome.ACCEPTED, Failure.TIMEOUT), Outcome.FAILED, Failure.INVALID_RESPONSE),
                Arguments.of(new Result(Outcome.FAILED, Failure.NONE), Outcome.FAILED, Failure.INVALID_RESPONSE),
                Arguments.of(new Result(Outcome.RETRYABLE, Failure.NONE), Outcome.FAILED, Failure.INVALID_RESPONSE),
                Arguments.of(new Result(Outcome.RETRYABLE, Failure.REJECTED), Outcome.FAILED, Failure.INVALID_RESPONSE),
                Arguments.of(new Result(Outcome.RETRYABLE, Failure.TIMEOUT), Outcome.RETRYABLE, Failure.TIMEOUT),
                Arguments.of(new Result(Outcome.RETRYABLE, Failure.UNAVAILABLE), Outcome.RETRYABLE, Failure.UNAVAILABLE),
                Arguments.of(new Result(Outcome.RETRYABLE, Failure.THROTTLED), Outcome.RETRYABLE, Failure.THROTTLED),
                Arguments.of(new Result(Outcome.RETRYABLE, Failure.AMBIGUOUS), Outcome.RETRYABLE, Failure.AMBIGUOUS),
                Arguments.of(new Result(Outcome.FAILED, Failure.REJECTED), Outcome.FAILED, Failure.REJECTED));
    }

    @ParameterizedTest
    @MethodSource("rejectedResults")
    void normalizesResultsWithoutAcknowledgingOrRetrying(Result result, Outcome outcome, Failure failure) {
        gateway.result = result;
        assertDispatchFailure(this::deliver);
        assertThat(gateway.calls).isEqualTo(1);
        verify(challenges, never()).markSent(anyLong(), anyString());
        assertMetric(outcome, failure);
    }

    static Stream<Arguments> providerExceptions() {
        return Stream.of(
                Arguments.of(new CompletionException(new SocketTimeoutException(PROVIDER_SECRET)), Outcome.RETRYABLE, Failure.TIMEOUT),
                Arguments.of(new CompletionException(new HttpTimeoutException(PROVIDER_SECRET)), Outcome.RETRYABLE, Failure.TIMEOUT),
                Arguments.of(new CompletionException(new TimeoutException(PROVIDER_SECRET)), Outcome.RETRYABLE, Failure.TIMEOUT),
                Arguments.of(new CompletionException(new ConnectException(PROVIDER_SECRET)), Outcome.RETRYABLE, Failure.UNAVAILABLE),
                Arguments.of(new CompletionException(new UnknownHostException(PROVIDER_SECRET)), Outcome.RETRYABLE, Failure.UNAVAILABLE),
                Arguments.of(new CompletionException(new java.net.NoRouteToHostException(PROVIDER_SECRET)), Outcome.RETRYABLE, Failure.UNAVAILABLE),
                Arguments.of(new IllegalStateException(PROVIDER_SECRET), Outcome.FAILED, Failure.AMBIGUOUS),
                Arguments.of(new CompletionException(new java.io.IOException(PROVIDER_SECRET)), Outcome.FAILED, Failure.AMBIGUOUS));
    }

    @ParameterizedTest
    @MethodSource("providerExceptions")
    void classifiesExceptionsWithoutLeakingTheirCauses(RuntimeException exception, Outcome outcome, Failure failure) {
        gateway.failure = exception;
        Logger logger = (Logger) LoggerFactory.getLogger(OtpDispatchService.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            assertDispatchFailure(this::deliver);
            assertThat(events.list).hasSize(1);
            assertThat(events.list.getFirst().getFormattedMessage())
                    .contains(outcome.name(), failure.name(), REF, "corr-dispatch")
                    .doesNotContain(PROVIDER_SECRET, "123456", VERIFIER, "recipient@example.invalid");
            assertThat(events.list.getFirst().getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
        assertThat(gateway.calls).isEqualTo(1);
        verify(challenges, never()).markSent(anyLong(), anyString());
        assertMetric(outcome, failure);
    }

    @Test
    void causeCyclesAreBoundedAndDoNotTriggerRetry() {
        RuntimeException first = new RuntimeException(PROVIDER_SECRET);
        RuntimeException second = new RuntimeException(PROVIDER_SECRET);
        first.initCause(second);
        second.initCause(first);
        gateway.failure = first;
        assertDispatchFailure(this::deliver);
        assertThat(gateway.calls).isEqualTo(1);
        assertMetric(Outcome.FAILED, Failure.AMBIGUOUS);
    }

    @Test
    void noConfiguredProviderFailsClosed() {
        when(provider.getIfUnique()).thenReturn(null);
        assertDispatchFailure(this::deliver);
        assertThat(gateway.calls).isZero();
        verify(challenges, never()).markSent(anyLong(), anyString());
        assertMetric(Outcome.FAILED, Failure.UNCONFIGURED);
    }

    @ParameterizedTest
    @ValueSource(strings = { "SENT", "VERIFIED", "LOCKED", "EXPIRED", "CANCELLED" })
    void rejectsNonCreatedSnapshotsBeforeGatewayResolution(String status) {
        assertDispatchFailure(() -> dispatch.deliver(snapshot(status, NOW.plusSeconds(300)),
                "123456".toCharArray(), "corr-dispatch"));
        verify(provider, never()).getIfUnique();
        verify(challenges, never()).markSent(anyLong(), anyString());
        assertMetric(Outcome.FAILED, Failure.REJECTED);
    }

    @ParameterizedTest
    @ValueSource(longs = { -1, 0 })
    void rejectsExpiredSnapshotsIncludingTheExactBoundary(long seconds) {
        assertDispatchFailure(() -> dispatch.deliver(snapshot("CREATED", NOW.plusSeconds(seconds)),
                "123456".toCharArray(), "corr-dispatch"));
        verify(provider, never()).getIfUnique();
        verify(challenges, never()).markSent(anyLong(), anyString());
    }

    @Test
    void deeplyWrappedCausesAreBoundedWithoutRetry() {
        RuntimeException nested = new CompletionException(new SocketTimeoutException(PROVIDER_SECRET));
        for (int depth = 0; depth < 32; depth++) {
            nested = new RuntimeException(PROVIDER_SECRET, nested);
        }
        gateway.failure = nested;
        assertDispatchFailure(this::deliver);
        assertThat(gateway.calls).isEqualTo(1);
        assertMetric(Outcome.FAILED, Failure.AMBIGUOUS);
    }

    @Test
    void conditionalAcknowledgementLossFailsClosedWithoutResending() {
        when(challenges.markSent(7L, VERIFIER)).thenReturn(false);
        assertDispatchFailure(this::deliver);
        assertThat(gateway.calls).isEqualTo(1);
        verify(challenges).markSent(7L, VERIFIER);
        assertMetric(Outcome.FAILED, Failure.AMBIGUOUS);
    }

    @Test
    void acknowledgementDatabaseFailureIsSanitizedAndNotRetried() {
        when(challenges.markSent(7L, VERIFIER)).thenThrow(new IllegalStateException(PROVIDER_SECRET));
        assertDispatchFailure(this::deliver);
        assertThat(gateway.calls).isEqualTo(1);
        verify(challenges).markSent(7L, VERIFIER);
        assertMetric(Outcome.FAILED, Failure.AMBIGUOUS);
    }

    private ChallengeRepository.ChallengeSnapshot committedChallenge() {
        long userId = tx.execute(ignored -> {
            String username = "dispatch-" + UUID.randomUUID();
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, ?, 'CUSTOMER', 'ACTIVE')", username, "synthetic-test-password-hash");
            return jdbc.queryForObject("SELECT user_id FROM banking.app_user WHERE username = ?", Long.class, username);
        });
        return tx.execute(ignored -> realChallenges.insert(new ChallengeRepository.NewChallenge(userId,
                "LOGIN_NEW_DEVICE", "OTP", "SMS", VERIFIER, null, null,
                Instant.now().plusSeconds(300), 5, "corr-dispatch", null)));
    }

    private String persistedStatus(long challengeId) {
        return tx.execute(ignored -> jdbc.queryForObject(
                "SELECT status FROM banking.auth_challenge WHERE challenge_id = ?", String.class, challengeId));
    }

    @Test
    void gatewayRunsOutsideTheAmbientPostgreSqlTransactionAndAcknowledgementCommitsIndependently() {
        var challenge = committedChallenge();
        realGateway.duringSend = () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // NOT_SUPPORTED may establish synchronization without an actual transaction.
            assertThat(persistedStatus(challenge.challengeId())).isEqualTo("CREATED");
        };

        tx.executeWithoutResult(outer -> {
            jdbc.queryForObject("SELECT count(*) FROM banking.app_user", Long.class);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            realDispatch.deliver(challenge, "123456".toCharArray(), "corr-dispatch");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            outer.setRollbackOnly();
        });

        assertThat(realGateway.calls).isEqualTo(1);
        assertThat(realGateway.transactionActive).isFalse();
        assertThat(persistedStatus(challenge.challengeId())).isEqualTo("SENT");
        Timestamp sentAt = tx.execute(ignored -> jdbc.queryForObject(
                "SELECT sent_at FROM banking.auth_challenge WHERE challenge_id = ?",
                Timestamp.class, challenge.challengeId()));
        assertThat(sentAt).isNotNull();
    }

    @Test
    void changedVerifierCannotBeAcknowledgedByAnOldAcceptedSend() {
        var challenge = committedChallenge();
        realGateway.duringSend = () -> tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.auth_challenge SET otp_hash = ? WHERE challenge_id = ?",
                "replacement-synthetic-verifier", challenge.challengeId()));

        assertDispatchFailure(() -> realDispatch.deliver(challenge, "123456".toCharArray(), "corr-dispatch"));

        assertThat(realGateway.calls).isEqualTo(1);
        assertThat(persistedStatus(challenge.challengeId())).isEqualTo("CREATED");
        Timestamp sentAt = tx.execute(ignored -> jdbc.queryForObject(
                "SELECT sent_at FROM banking.auth_challenge WHERE challenge_id = ?",
                Timestamp.class, challenge.challengeId()));
        assertThat(sentAt).isNull();
    }

    @Test
    void expiryDuringAcceptedSendCannotBeAcknowledged() {
        var challenge = committedChallenge();
        realGateway.duringSend = () -> tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.auth_challenge SET created_at = now() - interval '2 minutes', "
                        + "expires_at = now() - interval '1 minute' WHERE challenge_id = ?",
                challenge.challengeId()));

        assertDispatchFailure(() -> realDispatch.deliver(challenge, "123456".toCharArray(), "corr-dispatch"));

        assertThat(realGateway.calls).isEqualTo(1);
        assertThat(persistedStatus(challenge.challengeId())).isEqualTo("EXPIRED");
    }
}
