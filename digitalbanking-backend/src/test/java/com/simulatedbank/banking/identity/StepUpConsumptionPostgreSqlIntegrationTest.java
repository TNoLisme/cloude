package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengePurpose;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ConsumeCommand;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ConsumedChallenge;
import com.simulatedbank.banking.identity.application.ChallengeRepository;
import com.simulatedbank.banking.identity.application.OtpChallengeService;
import com.simulatedbank.banking.identity.application.OtpCodeGenerator;
import com.simulatedbank.banking.notification.api.OtpGateway;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Identity's real JOIN boundary only: no Transfer, Ledger or Audit tables are invented. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class StepUpConsumptionPostgreSqlIntegrationTest {

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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "16");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
    }

    @TestConfiguration
    static class OtpCaptureConfig {
        @Bean
        Deque<String> consumptionOtps() {
            return new ConcurrentLinkedDeque<>();
        }

        @Bean
        @Primary
        OtpCodeGenerator consumptionOtpGenerator(Deque<String> consumptionOtps) {
            AtomicInteger sequence = new AtomicInteger();
            return () -> {
                String otp = Integer.toString(100000 + sequence.incrementAndGet());
                consumptionOtps.addLast(otp);
                return otp.toCharArray();
            };
        }

        @Bean
        OtpGateway consumptionGateway() {
            return (ref, recipient, channel, otp, expiry, correlation) ->
                    new OtpGateway.Result(OtpGateway.Outcome.ACCEPTED, OtpGateway.Failure.NONE);
        }
    }

    @Autowired
    private OtpChallengeService challenges;

    @Autowired
    private ChallengeRepository repository;

    @Autowired
    private Argon2PasswordHasher hasher;

    @Autowired
    private Deque<String> consumptionOtps;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final AtomicInteger USER_SEQUENCE = new AtomicInteger();
    private JdbcTemplate jdbc;
    private TransactionTemplate callerTransaction;
    private long userId;
    private long otherUserId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        // Use the service's actual PlatformTransactionManager, including JpaTransactionManager's
        // JDBC connection binding. A separate DataSourceTransactionManager would not prove JOIN.
        callerTransaction = new TransactionTemplate(transactionManager);
        callerTransaction.setTimeout(20);
        int sequence = USER_SEQUENCE.incrementAndGet();
        userId = seedUser("consume-owner-" + sequence);
        otherUserId = seedUser("consume-foreign-" + sequence);
        consumptionOtps.clear();
    }

    @RepeatedTest(5)
    void a_twoConcurrentConsumersHaveExactlyOneCommittedWinner() throws Exception {
        Proof proof = verified("two-" + userId);
        assertSingleWinner(proof, race(List.of(proof.command(), proof.command())), 1);
    }

    @RepeatedTest(5)
    void b_manyConcurrentConsumersHaveExactlyOneCommittedWinner() throws Exception {
        Proof proof = verified("many-" + userId);
        List<ConsumeCommand> commands = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            commands.add(proof.command());
        }
        assertSingleWinner(proof, race(commands), 7);
    }

    @RepeatedTest(5)
    void d_simultaneousWrongHashWrongKeyAndValidProofCannotStealConsumption() throws Exception {
        Proof proof = verified("mixed-" + userId);
        ConsumeCommand wrongHash = withBinding(proof, HmacService.sha256Hex("different payload"), proof.key());
        ConsumeCommand wrongKey = withBinding(proof, proof.hash(), proof.key() + "-other");
        List<Outcome> outcomes = race(List.of(wrongHash, wrongKey, proof.command()));

        assertThat(outcomes.get(0).error()).isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertThat(outcomes.get(1).error()).isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertThat(outcomes.get(2).error()).isNull();
        assertCommitted(proof, outcomes.get(2).consumed());
        assertRejected(proof.command(), ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void wrongHashAndWrongKeyAreIndependentlyRejectedWithoutChangingProof() {
        Proof proof = verified("independent-" + userId);
        Map<String, Object> before = row(proof.ref());

        assertRejected(withBinding(proof, HmacService.sha256Hex("wrong hash only"), proof.key()),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertUnchanged(proof, before);
        assertRejected(withBinding(proof, proof.hash(), proof.key() + "-wrong-key-only"),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertUnchanged(proof, before);
        assertCommitted(proof, consume(proof.command()));
    }

    @Test
    void oversizedPaddedMalformedAndMissingBindingCannotAuthorize() {
        Proof proof = verified("k".repeat(255));
        Map<String, Object> before = row(proof.ref());
        List<ConsumeCommand> malformed = List.of(
                withBinding(proof, proof.hash() + "f", proof.key()),
                withBinding(proof, proof.hash() + " ", proof.key()),
                withBinding(proof, " " + proof.hash(), proof.key()),
                withBinding(proof, proof.hash().substring(1), proof.key()),
                withBinding(proof, "g".repeat(64), proof.key()),
                withBinding(proof, "", proof.key()),
                withBinding(proof, null, proof.key()),
                withBinding(proof, proof.hash(), proof.key() + "suffix"),
                withBinding(proof, proof.hash(), proof.key() + " "),
                withBinding(proof, proof.hash(), ""),
                withBinding(proof, proof.hash(), " "),
                withBinding(proof, proof.hash(), "key" + '\0' + "suffix"),
                withBinding(proof, proof.hash(), null),
                withBinding(proof, null, null));
        for (ConsumeCommand command : malformed) {
            assertRejected(command, ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
            assertUnchanged(proof, before);
        }
        assertCommitted(proof, consume(proof.command()));
    }

    @Test
    void databaseProofComparisonItselfNeverTruncatesOrUsesCharPaddingEquality() {
        Proof proof = verified("q".repeat(255));
        List<ConsumeCommand> malformed = List.of(
                withBinding(proof, proof.hash() + "suffix", proof.key()),
                withBinding(proof, proof.hash() + " ", proof.key()),
                withBinding(proof, proof.hash(), proof.key() + "suffix"),
                withBinding(proof, proof.hash(), proof.key() + " "),
                withBinding(proof, null, proof.key()),
                withBinding(proof, proof.hash(), null));
        // Exercise the real repository too: Java validation must not conceal unsafe SQL casts.
        for (ConsumeCommand command : malformed) {
            callerTransaction.executeWithoutResult(status -> assertThat(repository.consume(
                    new ChallengeRepository.ConsumePredicate(command.challengeRef(), command.userId(),
                            command.purpose().name(), command.requestHash(), command.bindingKey())))
                    .isEmpty());
            assertUnconsumed(proof);
        }
        assertCommitted(proof, consume(proof.command()));
    }

    @Test
    void shorterOpaqueKeyDoesNotAcceptAppendedSpacesOrMissingSensitiveActionBinding() {
        Proof proof = verified("opaque-" + userId);
        assertRejected(withBinding(proof, proof.hash(), proof.key() + " "),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertUnconsumed(proof);

        Proof sensitive = verified("sensitive-" + userId, ChallengePurpose.SENSITIVE_ACTION);
        assertRejected(withBinding(sensitive, null, null), ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertRejected(withBinding(sensitive, sensitive.hash(), null), ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertRejected(withBinding(sensitive, null, sensitive.key()), ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertUnconsumed(sensitive);
        assertCommitted(sensitive, consume(sensitive.command()));
        assertCommitted(proof, consume(proof.command()));
    }

    @Test
    void malformedMissingForeignAndWrongPurposeProofFailClosed() {
        Proof proof = verified("identity-" + userId);
        for (String ref : new String[] {null, "", " ", "not-a-uuid", proof.ref() + "suffix",
                "1-1-1-1-1", UUID.randomUUID().toString()}) {
            assertRejected(new ConsumeCommand(ref, userId, proof.command().purpose(), proof.hash(), proof.key()),
                    ErrorCode.AUTH_CHALLENGE_NOT_FOUND);
        }
        for (long user : new long[] {0, -1, otherUserId}) {
            assertRejected(new ConsumeCommand(proof.ref(), user, proof.command().purpose(), proof.hash(), proof.key()),
                    ErrorCode.AUTH_CHALLENGE_NOT_FOUND);
        }
        // Ownership remains non-enumerating even when the foreign caller supplies malformed proof.
        assertRejected(new ConsumeCommand(proof.ref(), otherUserId, null, null, null),
                ErrorCode.AUTH_CHALLENGE_NOT_FOUND);
        assertRejected(new ConsumeCommand(proof.ref(), userId, null, proof.hash(), proof.key()),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertRejected(new ConsumeCommand(proof.ref(), userId, ChallengePurpose.SENSITIVE_ACTION,
                proof.hash(), proof.key()), ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertRejected(new ConsumeCommand(proof.ref(), userId, ChallengePurpose.LOGIN_NEW_DEVICE, null, null),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertRejected(null, ErrorCode.VALIDATION_ERROR);
        assertUnconsumed(proof);
        assertCommitted(proof, consume(proof.command()));
    }

    @Test
    void unverifiedSentProofReturnsPublic428ChallengeAndNoConsumption() {
        Proof proof = issued("sent-" + userId, ChallengePurpose.TRANSFER_STEP_UP);
        consumptionOtps.removeLast();

        assertThatThrownBy(() -> consume(proof.command()))
                .isInstanceOfSatisfying(BankingBusinessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ErrorCode.STEP_UP_REQUIRED);
                    assertThat(failure.extensions()).containsOnlyKeys("challenge");
                    assertThat(failure.extensions().get("challenge")).isInstanceOfSatisfying(Map.class, challenge -> {
                        assertThat(challenge).containsOnlyKeys("challengeId", "purpose", "methods", "expiresAt",
                                "maxAttempts", "remainingAttempts", "channel");
                        assertThat(challenge.get("challengeId").toString()).isEqualTo(proof.ref());
                        assertThat(challenge.get("purpose")).isEqualTo("TRANSFER_STEP_UP");
                    });
                });
        assertThat(row(proof.ref()).get("status")).isEqualTo("SENT");
        assertThat(row(proof.ref()).get("consumed_at")).isNull();
    }

    @Test
    void callerBusinessAndAuditFailuresRollBackConsumptionAndAllowRetry() {
        // These are caller exceptions, not a fabricated Transfer/Audit implementation.
        for (RuntimeException failure : List.of(new IllegalStateException("caller business failure"),
                new BankingBusinessException(ErrorCode.AUDIT_WRITE_FAILED))) {
            Proof proof = verified("rollback-" + userId + "-" + failure.getClass().getSimpleName());
            Map<String, Object> before = row(proof.ref());
            assertThatThrownBy(() -> callerTransaction.executeWithoutResult(status -> {
                ConsumedChallenge consumed = challenges.consumeVerified(proof.command());
                assertThat(row(proof.ref()).get("consumed_at")).isEqualTo(Timestamp.from(consumed.consumedAt()));
                throw failure;
            })).isSameAs(failure);
            assertUnchanged(proof, before);

            // A different, committed caller transaction can reuse the rolled-back proof once.
            ConsumedChallenge committed = consume(proof.command());
            assertCommitted(proof, committed);
            assertRejected(proof.command(), ErrorCode.INVALID_STATE_TRANSITION);
            assertRejected(proof.command(), ErrorCode.INVALID_STATE_TRANSITION);
            assertCommitted(proof, committed);
        }
    }

    @Test
    void cancelledProofRejectsConsumptionWithoutChangingTheRow() {
        Proof proof = verified("cancelled-" + userId);
        callerTransaction.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.auth_challenge SET status = 'CANCELLED' WHERE challenge_ref = ?::uuid",
                proof.ref()));
        Map<String, Object> before = row(proof.ref());

        assertRejected(proof.command(), ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(row(proof.ref())).isEqualTo(before);
        assertThat(before.get("status")).isEqualTo("CANCELLED");
        assertThat(before.get("consumed_at")).isNull();
    }

    @Test
    void committedProofCannotBeReboundOrReplayedInAnotherTransaction() {
        Proof first = verified("committed-" + userId);
        Proof second = verified("different-" + userId);
        ConsumedChallenge committed = consume(first.command());

        assertRejected(first.command(), ErrorCode.INVALID_STATE_TRANSITION);
        assertRejected(withBinding(first, second.hash(), first.key()), ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertRejected(withBinding(first, first.hash(), second.key()), ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertCommitted(first, committed);
        assertUnconsumed(second);
        assertCommitted(second, consume(second.command()));
    }

    @Test
    void serviceAndPersistenceConsumeRequireExistingCallerTransaction() {
        Proof proof = verified("mandatory-" + userId);
        assertThatThrownBy(() -> challenges.consumeVerified(proof.command()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> repository.consume(new ChallengeRepository.ConsumePredicate(
                proof.ref(), userId, proof.command().purpose().name(), proof.hash(), proof.key())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertUnconsumed(proof);
        assertCommitted(proof, consume(proof.command()));
    }

    @Test
    void callerTransactionStartingBeforeExpiryCannotConsumeAfterWallClockExpiry() {
        Proof proof = verified("old-transaction-" + userId);
        assertThatThrownBy(() -> callerTransaction.executeWithoutResult(status -> {
            Instant started = jdbc.queryForObject("SELECT transaction_timestamp()", Timestamp.class).toInstant();
            Instant expiry = changeExpiry(proof.ref(), 250);
            assertThat(started).isBefore(expiry);
            awaitExpiry(proof.ref());
            assertThat(jdbc.queryForObject("SELECT now() < expires_at FROM banking.auth_challenge "
                    + "WHERE challenge_ref = ?::uuid", Boolean.class, proof.ref())).isTrue();
            challenges.consumeVerified(proof.command());
        })).isInstanceOfSatisfying(BankingBusinessException.class, failure ->
                assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_CHALLENGE_EXPIRED));
        // The caller's expiry adjustment and any lazy EXPIRED transition roll back as well.
        assertUnconsumed(proof);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiryIsRecheckedAfterRowLockHolderCommitsOrRollsBack(boolean holderRollsBack) throws Exception {
        Proof proof = verified("lock-expiry-" + userId);
        callerTransaction.executeWithoutResult(status -> changeExpiry(proof.ref(), 3000));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        List<Future<Outcome>> futures = new ArrayList<>();
        String label = "expiry-" + UUID.randomUUID();
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            callerTransaction.executeWithoutResult(status -> {
                lock(proof.ref());
                futures.add(pool.submit(() -> attempt(proof.command(), label, start)));
                awaitBarrier(start);
                awaitBlocked(label, 1);
                // The consumer reached the row-lock wait while the proof was still valid.
                assertThat(jdbc.queryForObject("SELECT clock_timestamp() < expires_at "
                        + "FROM banking.auth_challenge WHERE challenge_ref = ?::uuid",
                        Boolean.class, proof.ref())).isTrue();
                awaitExpiry(proof.ref());
                if (holderRollsBack) {
                    // Critical case: rollback leaves the same tuple version. An UPDATE that
                    // evaluated clock_timestamp before waiting need not re-evaluate its WHERE.
                    status.setRollbackOnly();
                }
            });
            Outcome outcome = futures.get(0).get(20, TimeUnit.SECONDS);
            assertThat(outcome.error()).isEqualTo(ErrorCode.AUTH_CHALLENGE_EXPIRED);
            assertThat(outcome.consumed()).isNull();
            assertUnconsumed(proof);
            assertRejected(proof.command(), ErrorCode.AUTH_CHALLENGE_EXPIRED);
        } finally {
            shutdown(pool);
        }
    }

    @Test
    void expiryBoundaryIsExclusiveAndConsumedTimestampUsesWallClock() {
        Proof expired = verified("boundary-expired-" + userId);
        callerTransaction.executeWithoutResult(status -> changeExpiry(expired.ref(), 0));
        assertRejected(expired.command(), ErrorCode.AUTH_CHALLENGE_EXPIRED);
        assertUnconsumed(expired);

        Proof live = verified("boundary-live-" + userId);
        ConsumedChallenge consumed = callerTransaction.execute(status -> {
            Instant started = jdbc.queryForObject("SELECT transaction_timestamp()", Timestamp.class).toInstant();
            await().pollInSameThread().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(5))
                    .until(() -> jdbc.queryForObject("SELECT clock_timestamp() > ?::timestamptz "
                            + "+ interval '50 milliseconds'", Boolean.class, Timestamp.from(started)));
            ConsumedChallenge result = challenges.consumeVerified(live.command());
            assertThat(result.consumedAt()).isAfter(started.plusMillis(50));
            assertThat(result.consumedAt()).isBefore(live.view().expiresAt());
            return result;
        });
        assertCommitted(live, consumed);
    }

    /** All consumers have active transactions and observable PostgreSQL lock waits before release. */
    private List<Outcome> race(List<ConsumeCommand> commands) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(commands.size());
        CyclicBarrier start = new CyclicBarrier(commands.size() + 1);
        String label = "consume-race-" + UUID.randomUUID();
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            callerTransaction.executeWithoutResult(status -> {
                lock(commands.get(0).challengeRef());
                for (ConsumeCommand command : commands) {
                    futures.add(pool.submit(() -> attempt(command, label, start)));
                }
                awaitBarrier(start);
                awaitBlocked(label, commands.size());
            });
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(20, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            shutdown(pool);
        }
    }

    private Outcome attempt(ConsumeCommand command, String label, CyclicBarrier start) {
        try {
            ConsumedChallenge consumed = callerTransaction.execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '10s'");
                jdbc.execute("SET LOCAL statement_timeout = '12s'");
                jdbc.queryForObject("SELECT set_config('application_name', ?, true)", String.class, label);
                awaitBarrier(start);
                return challenges.consumeVerified(command);
            });
            return new Outcome(null, consumed);
        } catch (BankingBusinessException rejection) {
            return new Outcome(rejection.code(), null);
        }
    }

    private void awaitBlocked(String label, int count) {
        await().pollInSameThread().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(5))
                .until(() -> {
                    // Activity snapshots can otherwise remain cached for the holder transaction.
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                            + "WHERE application_name = ? AND wait_event_type = 'Lock' "
                            + "AND cardinality(pg_blocking_pids(pid)) > 0", Integer.class, label) == count;
                });
    }

    private void awaitExpiry(String ref) {
        await().pollInSameThread().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(8))
                .until(() -> jdbc.queryForObject("SELECT clock_timestamp() >= expires_at "
                        + "FROM banking.auth_challenge WHERE challenge_ref = ?::uuid", Boolean.class, ref));
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("Consumers did not reach the transaction barrier", failure);
        }
    }

    private static void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).as("bounded consumer shutdown").isTrue();
    }

    private void lock(String ref) {
        jdbc.queryForObject("SELECT challenge_id FROM banking.auth_challenge "
                + "WHERE challenge_ref = ?::uuid FOR UPDATE", Long.class, ref);
    }

    private Instant changeExpiry(String ref, int millisecondsFromNow) {
        return jdbc.queryForObject("UPDATE banking.auth_challenge SET created_at = clock_timestamp() "
                + "- interval '1 day', expires_at = clock_timestamp() + (? * interval '1 millisecond') "
                + "WHERE challenge_ref = ?::uuid RETURNING expires_at", Timestamp.class, millisecondsFromNow, ref)
                .toInstant();
    }

    private long seedUser(String username) {
        return callerTransaction.execute(status -> jdbc.queryForObject(
                "INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                        + "VALUES (?, ?, 'CUSTOMER', 'ACTIVE') RETURNING user_id", Long.class, username,
                hasher.hash("irrelevant".toCharArray())));
    }

    private Proof issued(String key, ChallengePurpose purpose) {
        String hash = HmacService.sha256Hex("payload-" + key);
        StepUpChallengePort.ChallengeView view = challenges.requestChallenge(
                new StepUpChallengePort.ChallengeRequest(purpose, userId, hash, key, "consume-contract"));
        return new Proof(view, new ConsumeCommand(view.challengeRef(), userId, purpose, hash, key));
    }

    private Proof verified(String key) {
        return verified(key, ChallengePurpose.TRANSFER_STEP_UP);
    }

    private Proof verified(String key, ChallengePurpose purpose) {
        Proof proof = issued(key, purpose);
        String otp = consumptionOtps.pollLast();
        assertThat(otp).as("real service OTP captured only in test fixture").isNotNull();
        challenges.verifyChallenge(new StepUpChallengePort.VerifyCommand(
                proof.ref(), userId, purpose, otp.toCharArray(), "consume-contract"));
        assertUnconsumed(proof);
        return proof;
    }

    private ConsumeCommand withBinding(Proof proof, String hash, String key) {
        return new ConsumeCommand(proof.ref(), userId, proof.command().purpose(), hash, key);
    }

    private ConsumedChallenge consume(ConsumeCommand command) {
        return callerTransaction.execute(status -> challenges.consumeVerified(command));
    }

    private void assertRejected(ConsumeCommand command, ErrorCode expected) {
        assertThatThrownBy(() -> consume(command)).isInstanceOfSatisfying(BankingBusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo(expected));
    }

    private void assertSingleWinner(Proof proof, List<Outcome> outcomes, int losers) {
        assertThat(outcomes.stream().filter(outcome -> outcome.error() == null).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.error() == ErrorCode.INVALID_STATE_TRANSITION)
                .count()).isEqualTo(losers);
        Outcome winner = outcomes.stream().filter(outcome -> outcome.error() == null).findFirst().orElseThrow();
        assertCommitted(proof, winner.consumed());
        assertRejected(proof.command(), ErrorCode.INVALID_STATE_TRANSITION);
    }

    private Map<String, Object> row(String ref) {
        return jdbc.queryForMap("SELECT * FROM banking.auth_challenge WHERE challenge_ref = ?::uuid", ref);
    }

    private void assertUnconsumed(Proof proof) {
        Map<String, Object> persisted = row(proof.ref());
        assertThat(persisted.get("status")).isEqualTo("VERIFIED");
        assertThat(persisted.get("verified_at")).isNotNull();
        assertThat(persisted.get("consumed_at")).isNull();
    }

    private void assertUnchanged(Proof proof, Map<String, Object> before) {
        assertThat(row(proof.ref())).isEqualTo(before);
        assertUnconsumed(proof);
    }

    private void assertCommitted(Proof proof, ConsumedChallenge consumed) {
        assertThat(consumed).isNotNull();
        Map<String, Object> persisted = row(proof.ref());
        assertThat(consumed.challengeRef()).isEqualTo(proof.ref());
        assertThat(consumed.purpose()).isEqualTo(proof.command().purpose());
        assertThat(consumed.challengeId()).isEqualTo(((Number) persisted.get("challenge_id")).longValue());
        assertThat(consumed.consumedAt()).isEqualTo(((Timestamp) persisted.get("consumed_at")).toInstant());
        assertThat(persisted.get("status")).isEqualTo("VERIFIED");
        assertThat(consumed.consumedAt()).isBefore(((Timestamp) persisted.get("expires_at")).toInstant());
    }

    private record Proof(StepUpChallengePort.ChallengeView view, ConsumeCommand command) {
        String ref() { return command.challengeRef(); }
        String hash() { return command.requestHash(); }
        String key() { return command.bindingKey(); }
    }

    private record Outcome(ErrorCode error, ConsumedChallenge consumed) {
    }
}
