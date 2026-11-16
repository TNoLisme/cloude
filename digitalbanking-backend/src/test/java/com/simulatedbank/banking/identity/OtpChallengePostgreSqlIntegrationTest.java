package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.application.OtpChallengeService;
import com.simulatedbank.banking.identity.application.OtpCodeGenerator;
import com.simulatedbank.banking.notification.api.OtpGateway;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-ID-IMP-05: integration PostgreSQL thật (Testcontainers) — lifecycle issue/verify/consume/
 * expire/lock, binding, T1 rollback/consume, race T-STEP-01, AC-003/AC-033, audit log không OTP.
 * OTP plaintext bắt qua @Primary test generator (test seam duy nhất; prod dùng SecureRandom).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OtpChallengePostgreSqlIntegrationTest {

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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
    }

    @TestConfiguration
    static class OtpCaptureConfig {
        /** Test seam: OTP plaintext được giữ trong queue cho assert; không bao giờ log/DB. */
        @Bean
        @Primary
        OtpCodeGenerator capturingOtpGenerator(Deque<String> issued) {
            AtomicInteger sequence = new AtomicInteger();
            return () -> {
                // Deterministic and never "000000" (the wrong-code fixture).
                String otp = Integer.toString(100000 + sequence.incrementAndGet());
                issued.add(otp);
                return otp.toCharArray();
            };
        }

        @Bean
        Deque<String> issuedOtps() {
            return new ArrayDeque<>();
        }

        @Bean
        OtpGateway acceptingGateway() {
            return (ref, recipient, channel, otp, expiry, correlation) ->
                    new OtpGateway.Result(OtpGateway.Outcome.ACCEPTED, OtpGateway.Failure.NONE);
        }
    }

    @Autowired
    private OtpChallengeService challenges;

    @Autowired
    private Argon2PasswordHasher hasher;

    @Autowired
    private Deque<String> issuedOtps;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager platformTransactionManager;

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private TransactionTemplate t1;
    private final ListAppender<ILoggingEvent> auditLog = new ListAppender<>();
    private long userId;
    private long foreignUserId;
    private static final AtomicInteger USER_SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        // Dùng cùng PlatformTransactionManager với @Transactional(MANDATORY) của service
        // (auto-config là JpaTransactionManager vì starter-data-jpa) để JOIN/T1 nhìn thấy TX.
        tx = new TransactionTemplate(platformTransactionManager);
        t1 = new TransactionTemplate(platformTransactionManager);
        int seq = USER_SEQ.incrementAndGet();
        userId = seedUser("otp-user-" + seq);
        foreignUserId = seedUser("otp-other-" + seq);
        issuedOtps.clear();
        Logger auditLogger = (Logger) LoggerFactory.getLogger("com.simulatedbank.banking.identity.AUDIT");
        auditLog.list.clear();
        auditLog.start();
        auditLogger.addAppender(auditLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger("com.simulatedbank.banking.identity.AUDIT")).detachAppender(auditLog);
    }

    private long seedUser(String username) {
        return tx.execute(ignored -> {
            jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                    + "VALUES (?, ?, 'CUSTOMER', 'ACTIVE')", username, hasher.hash("irrelevant".toCharArray()));
            return jdbc.queryForObject(
                    "SELECT user_id FROM banking.app_user WHERE username = ?", Long.class, username);
        });
    }

    private String lastOtp() {
        String otp = issuedOtps.pollLast();
        assertThat(otp).as("OTP issued by capturing generator").isNotNull();
        return otp;
    }

    private StepUpChallengePort.ChallengeRequest stepUpRequest(String key) {
        return new StepUpChallengePort.ChallengeRequest(
                StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP, userId,
                com.simulatedbank.banking.common.crypto.HmacService.sha256Hex("payload-" + key),
                key, "corr-int-1");
    }

    private StepUpChallengePort.ChallengeView issue(String key) {
        return challenges.requestChallenge(stepUpRequest(key));
    }

    private StepUpChallengePort.VerifyCommand verify(String ref, String otp) {
        return new StepUpChallengePort.VerifyCommand(ref, userId,
                StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP, otp.toCharArray(), "corr-int-1");
    }

    private StepUpChallengePort.ConsumeCommand consume(String ref, String key) {
        return new StepUpChallengePort.ConsumeCommand(ref, userId,
                StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP,
                com.simulatedbank.banking.common.crypto.HmacService.sha256Hex("payload-" + key), key);
    }

    private Map<String, Object> row(String ref) {
        return jdbc.queryForMap(
                "SELECT * FROM banking.auth_challenge WHERE challenge_ref = ?::uuid", ref);
    }

    /** Consume luôn trong T1 (JOIN caller TX — design/05 §5.2); rejection ném BankingBusinessException. */
    private StepUpChallengePort.ConsumedChallenge consumeInT1(StepUpChallengePort.ConsumeCommand command) {
        return t1.execute(status -> challenges.consumeVerified(command));
    }

    private void setExpiryPast(String ref) {
        // CHECK `expires_at > created_at`: dịch cả created_at lùi để expires_at (now()-1s) hợp lệ.
        tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.auth_challenge SET created_at = created_at - interval '2 seconds', "
                        + "expires_at = now() - interval '1 second' WHERE challenge_ref = ?::uuid", ref));
    }

    // ------------------------------------------------------------------ issue

    // T01: accepted delivery — status SENT, attempt 0, TTL 300s, binding persist, otp_hash argon2id
    // (không plaintext), correlation_id; audit C2 AUTH_CHALLENGE_CREATED có trong security log.
    @Test
    void t01_issuePersistsChallengeWithBindingTtlAndAudit() {
        StepUpChallengePort.ChallengeView view = issue("k1");
        String otp = lastOtp();

        Map<String, Object> persisted = row(view.challengeRef());
        assertEquals("SENT", persisted.get("status"));
        assertEquals(0, ((Number) persisted.get("attempt_count")).intValue());
        assertEquals(5, ((Number) persisted.get("max_attempts")).intValue());
        assertThat(persisted.get("purpose")).isEqualTo("TRANSFER_STEP_UP");
        assertThat(persisted.get("method")).isEqualTo("OTP");
        assertThat(persisted.get("channel")).isEqualTo("SMS");
        assertThat((String) persisted.get("idempotency_key")).isEqualTo("k1");
        assertThat((String) persisted.get("request_hash"))
                .isEqualTo(stepUpRequest("k1").requestHash());
        assertThat((String) persisted.get("otp_hash")).startsWith("$argon2id$");
        assertThat((String) persisted.get("otp_hash")).doesNotContain(otp);
        assertThat((String) persisted.get("correlation_id")).isEqualTo("corr-int-1");
        Instant expiresAt = ((Timestamp) persisted.get("expires_at")).toInstant();
        Instant createdAt = ((Timestamp) persisted.get("created_at")).toInstant();
        assertThat(expiresAt).isAfter(createdAt).isBeforeOrEqualTo(createdAt.plusSeconds(300).plusSeconds(2));
        assertThat(view.remainingAttempts()).isEqualTo(5);
        assertThat(view.toString()).doesNotContain(otp);

        List<String> auditLines = auditLog.list.stream().map(e -> e.getFormattedMessage()).toList();
        assertThat(auditLines).anySatisfy(line -> {
            assertThat(line).contains("action=AUTH_CHALLENGE_CREATED");
            assertThat(line).contains("outcome=C2");
            assertThat(line).contains("AUTH_CHALLENGE:" + view.challengeRef());
        });
        // Không OTP/hash nào lọt log.
        assertThat(String.join(" ", auditLines)).doesNotContain(otp).doesNotContain("$argon2");
    }

    // T02: context không hợp lệ → 400, không INSERT.
    @Test
    void t02_invalidIssueContextRejected() {
        var missingBinding = new StepUpChallengePort.ChallengeRequest(
                StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP, userId, null, null, "corr");
        var loginWithBinding = new StepUpChallengePort.ChallengeRequest(
                StepUpChallengePort.ChallengePurpose.LOGIN_NEW_DEVICE, userId,
                com.simulatedbank.banking.common.crypto.HmacService.sha256Hex("p"), "key", "corr");

        assertThatThrownBy(() -> challenges.requestChallenge(missingBinding))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> challenges.requestChallenge(loginWithBinding))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM banking.auth_challenge WHERE user_id = ?", Integer.class, userId);
        assertThat(count).isZero();
    }

    // T03: duplicate issuance (cùng key+hash) — partial unique → trả lại cùng challengeRef, 1 row.
    @Test
    void t03_duplicateStepUpIssueReturnsSameChallenge() {
        StepUpChallengePort.ChallengeView first = issue("k-dup");
        lastOtp();
        StepUpChallengePort.ChallengeView second = challenges.requestChallenge(
                stepUpRequest("k-dup"));

        assertThat(second.challengeRef()).isEqualTo(first.challengeRef());
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM banking.auth_challenge WHERE idempotency_key = 'k-dup'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ------------------------------------------------------------------ verify

    // T04: OTP sai → 401 AUTH_OTP_INVALID, attempt_count tăng atomically.
    @Test
    void t04_wrongOtpRejectedAndCounted() {
        StepUpChallengePort.ChallengeView view = issue("k2");
        lastOtp();

        assertThatThrownBy(() -> challenges.verifyChallenge(verify(view.challengeRef(), "000000")))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_OTP_INVALID));
        assertThat((String) row(view.challengeRef()).get("status")).isIn("CREATED", "SENT");
        assertThat(((Number) row(view.challengeRef()).get("attempt_count")).intValue()).isEqualTo(1);
    }

    // T05 + AC-003: 4 lần sai rồi lần thứ 5 đúng → VERIFIED + verified_at; consume được sau đó.
    @Test
    void t05_correctOtpOnFinalAttemptVerifies() {
        StepUpChallengePort.ChallengeView view = issue("k3");
        String otp = lastOtp();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> challenges.verifyChallenge(verify(view.challengeRef(), "000000")))
                    .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.AUTH_OTP_INVALID));
        }

        StepUpChallengePort.VerifiedChallenge verified =
                challenges.verifyChallenge(verify(view.challengeRef(), otp));
        assertThat(verified.challengeRef()).isEqualTo(view.challengeRef());
        Map<String, Object> persisted = row(view.challengeRef());
        assertThat(persisted.get("status")).isEqualTo("VERIFIED");
        assertThat(persisted.get("verified_at")).isNotNull();
        assertThat(((Number) persisted.get("attempt_count")).intValue()).isEqualTo(5);

        // Consume ngay sau verify thành công (T1).
        StepUpChallengePort.ConsumedChallenge consumed =
                consumeInT1(consume(view.challengeRef(), "k3"));
        assertThat(consumed.challengeRef()).isEqualTo(view.challengeRef());
    }

    // T06 + AC-003: sai đủ 5 lần → LOCKED 423 + status LOCKED; đúng OTP sau đó vẫn 423.
    @Test
    void t06_maxAttemptsLocksChallenge() {
        StepUpChallengePort.ChallengeView view = issue("k4");
        String otp = lastOtp();
        for (int i = 0; i < 5; i++) {
            ErrorCode expected = i == 4 ? ErrorCode.AUTH_CHALLENGE_LOCKED : ErrorCode.AUTH_OTP_INVALID;
            assertThatThrownBy(() -> challenges.verifyChallenge(verify(view.challengeRef(), "000000")))
                    .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                            assertThat(e.code()).isEqualTo(expected));
        }
        assertThat(row(view.challengeRef()).get("status")).isEqualTo("LOCKED");
        assertThatThrownBy(() -> challenges.verifyChallenge(verify(view.challengeRef(), otp)))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_CHALLENGE_LOCKED));
        List<String> auditLines = auditLog.list.stream().map(e -> e.getFormattedMessage()).toList();
        assertThat(auditLines).anySatisfy(line ->
                assertThat(line).contains("action=AUTH_CHALLENGE_LOCKED").contains("outcome=C3"));
    }

    // T07 + AC-003: hết hạn → 410 AUTH_OTP_EXPIRED + transition EXPIRED; verify lại vẫn 410.
    @Test
    void t07_expiredOtpRejectedAndTransitionPersisted() {
        StepUpChallengePort.ChallengeView view = issue("k5");
        String otp = lastOtp();
        setExpiryPast(view.challengeRef());

        assertThatThrownBy(() -> challenges.verifyChallenge(verify(view.challengeRef(), otp)))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_OTP_EXPIRED));
        assertThat(row(view.challengeRef()).get("status")).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> challenges.verifyChallenge(verify(view.challengeRef(), otp)))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_OTP_EXPIRED));
    }

    // T08: challenge đã consume → verify lại bị cấm (409 INVALID_STATE_TRANSITION).
    @Test
    void t08_verifyingConsumedChallengeIsRejected() {
        StepUpChallengePort.ChallengeView view = issue("k6");
        String otp = lastOtp();
        challenges.verifyChallenge(verify(view.challengeRef(), otp));
        consumeInT1(consume(view.challengeRef(), "k6"));

        assertThatThrownBy(() -> challenges.verifyChallenge(verify(view.challengeRef(), otp)))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    // T09: purpose mismatch khi verify → 409 STEP_UP_CHALLENGE_MISMATCH.
    @Test
    void t09_verifyWithWrongPurposeIsMismatch() {
        StepUpChallengePort.ChallengeView view = issue("k7");
        lastOtp();
        var wrongPurpose = new StepUpChallengePort.VerifyCommand(view.challengeRef(), userId,
                StepUpChallengePort.ChallengePurpose.LOGIN_NEW_DEVICE, "123456".toCharArray(), "corr");

        assertThatThrownBy(() -> challenges.verifyChallenge(wrongPurpose))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
    }

    // T10: user khác verify challenge không phải của mình → 404 chung, state không đổi.
    @Test
    void t10_foreignUserVerifyIsNotFound() {
        StepUpChallengePort.ChallengeView view = issue("k8");
        lastOtp();
        var foreign = new StepUpChallengePort.VerifyCommand(view.challengeRef(), foreignUserId,
                StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP, "123456".toCharArray(), "corr");

        assertThatThrownBy(() -> challenges.verifyChallenge(foreign))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_CHALLENGE_NOT_FOUND));
        assertThat(((Number) row(view.challengeRef()).get("attempt_count")).intValue()).isZero();
    }

    // ------------------------------------------------------------------ consume

    // T11: consume challenge chưa verify → 428 STEP_UP_REQUIRED kèm challenge công khai.
    @Test
    void t11_consumeUnverifiedReturnsStepUpRequiredWithChallenge() {
        StepUpChallengePort.ChallengeView view = issue("k9");
        String otp = lastOtp();

        assertThatThrownBy(() -> consumeInT1(consume(view.challengeRef(), "k9")))
                .isInstanceOfSatisfying(BankingBusinessException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.STEP_UP_REQUIRED);
                    var challenge = (Map<?, ?>) e.extensions().get("challenge");
                    assertThat(challenge.get("challengeId").toString()).isEqualTo(view.challengeRef());
                    assertThat((Integer) challenge.get("remainingAttempts")).isEqualTo(5);
                    assertThat(challenge.toString()).doesNotContain(otp);
                });
    }

    // T12: consume với binding (hash/key) khác → 409 STEP_UP_CHALLENGE_MISMATCH; không consume.
    @Test
    void t12_consumeWithBindingMismatchRejected() {
        StepUpChallengePort.ChallengeView view = issue("k10");
        String otp = lastOtp();
        challenges.verifyChallenge(verify(view.challengeRef(), otp));

        assertThatThrownBy(() -> consumeInT1(consume(view.challengeRef(), "other-key")))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
        assertThat(row(view.challengeRef()).get("consumed_at")).isNull();
    }

    // T13: consume bởi user khác → 404; T14: consume LOCKED → 423.
    @Test
    void t13_consumeForeignUserAndLockedRejected() {
        StepUpChallengePort.ChallengeView view = issue("k11");
        String otp = lastOtp();
        challenges.verifyChallenge(verify(view.challengeRef(), otp));

        var foreign = new StepUpChallengePort.ConsumeCommand(view.challengeRef(), foreignUserId,
                StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP,
                consume(view.challengeRef(), "k11").requestHash(), "k11");
        assertThatThrownBy(() -> consumeInT1(foreign))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_CHALLENGE_NOT_FOUND));

        tx.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.auth_challenge SET status = 'LOCKED' WHERE challenge_ref = ?::uuid",
                view.challengeRef()));
        assertThatThrownBy(() -> consumeInT1(consume(view.challengeRef(), "k11")))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_CHALLENGE_LOCKED));
    }

    // T15: consume hết hạn → 410 AUTH_CHALLENGE_EXPIRED; T1 rollback nên transition EXPIRED
    // thẩm mỹ không persist theo (enforcement vẫn bảo đảm bởi expires_at check). consumed_at NULL.
    @Test
    void t15_consumeExpiredReturns410AndExpires() {
        StepUpChallengePort.ChallengeView view = issue("k12");
        String otp = lastOtp();
        challenges.verifyChallenge(verify(view.challengeRef(), otp));
        setExpiryPast(view.challengeRef());

        assertThatThrownBy(() -> consumeInT1(consume(view.challengeRef(), "k12")))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_CHALLENGE_EXPIRED));
        assertThat(row(view.challengeRef()).get("consumed_at")).isNull();
        // Retry consume vẫn 410 (expires_at là gate thực); EXPIRED transition thẩm mỹ của
        // VERIFIED-chưa-consume chỉ persist khi expireIfDue chạy ngoài outer TX (documented).
        assertThatThrownBy(() -> consumeInT1(consume(view.challengeRef(), "k12")))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_CHALLENGE_EXPIRED));
    }

    // T16 + single-use: consume T1 thành công → consumed_at, status giữ VERIFIED; consume lại → 409.
    @Test
    void t16_consumeIsSingleUse() {
        StepUpChallengePort.ChallengeView view = issue("k13");
        String otp = lastOtp();
        challenges.verifyChallenge(verify(view.challengeRef(), otp));

        StepUpChallengePort.ConsumedChallenge first =
                consumeInT1(consume(view.challengeRef(), "k13"));
        Map<String, Object> persisted = row(view.challengeRef());
        assertThat(persisted.get("status")).isEqualTo("VERIFIED");
        assertThat(persisted.get("consumed_at")).isNotNull();
        assertThat(first.consumedAt()).isNotNull();

        assertThatThrownBy(() -> consumeInT1(consume(view.challengeRef(), "k13")))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    // T17 + T1 rollback (AC-033): consume trong T1 rồi business fail → rollback hoàn consumed_at;
    // retry sau rollback consume lại được (chưa hết TTL). A committed OTP cannot be consumed again.
    @Test
    void t17_t1RollbackRestoresPreConsumptionState() {
        StepUpChallengePort.ChallengeView view = issue("k14");
        String otp = lastOtp();
        challenges.verifyChallenge(verify(view.challengeRef(), otp));

        assertThatThrownBy(() -> t1.execute(status -> {
            challenges.consumeVerified(consume(view.challengeRef(), "k14"));
            throw new IllegalStateException("business operation failed");
        })).isInstanceOf(IllegalStateException.class);

        Map<String, Object> afterRollback = row(view.challengeRef());
        assertThat(afterRollback.get("status")).isEqualTo("VERIFIED");
        assertThat(afterRollback.get("consumed_at")).isNull();

        // Retry T1 sau rollback: consume thành công và commit.
        t1.executeWithoutResult(ignored ->
                challenges.consumeVerified(consume(view.challengeRef(), "k14")));
        assertThat(row(view.challengeRef()).get("consumed_at")).isNotNull();
        assertThatThrownBy(() -> consumeInT1(consume(view.challengeRef(), "k14")))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    // T18: consumeVerified ngoài transaction (MANDATORY) → fail fast.
    @Test
    void t18_consumeWithoutTransactionFailsFast() {
        StepUpChallengePort.ChallengeView view = issue("k15");
        String otp = lastOtp();
        challenges.verifyChallenge(verify(view.challengeRef(), otp));

        assertThatThrownBy(() -> challenges.consumeVerified(consume(view.challengeRef(), "k15")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(row(view.challengeRef()).get("consumed_at")).isNull();
    }

    // T19 + T-STEP-01: hai consume đồng thời trên cùng challenge → đúng một thắng (200 vs 409).
    @Test
    void t19_concurrentConsumeOnlyOneWins() throws Exception {
        StepUpChallengePort.ChallengeView view = issue("k16");
        String otp = lastOtp();
        challenges.verifyChallenge(verify(view.challengeRef(), otp));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<ErrorCode> consumer = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                t1.executeWithoutResult(ignored ->
                        challenges.consumeVerified(consume(view.challengeRef(), "k16")));
                return null; // thành công
            } catch (Exception failure) {
                Throwable root = failure;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                if (root instanceof BankingBusinessException b) {
                    return b.code();
                }
                throw failure;
            }
        };
        try {
            List<Future<ErrorCode>> results = pool.invokeAll(List.of(consumer, consumer));
            List<ErrorCode> codes = new ArrayList<>();
            for (Future<ErrorCode> future : results) {
                codes.add(future.get(10, TimeUnit.SECONDS));
            }
            long successes = codes.stream().filter(c -> c == null).count();
            long conflicts = codes.stream().filter(c -> c == ErrorCode.INVALID_STATE_TRANSITION).count();
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            Map<String, Object> persisted = row(view.challengeRef());
            assertThat(persisted.get("consumed_at")).isNotNull();
            assertThat(persisted.get("status")).isEqualTo("VERIFIED");
        } finally {
            pool.shutdownNow();
        }
    }

    // T20: 5 verify sai đồng thời không vượt max_attempts; kết cục LOCKED, count = 5.
    @Test
    void t20_concurrentWrongVerifiesCannotBypassMaxAttempts() throws Exception {
        StepUpChallengePort.ChallengeView view = issue("k17");
        lastOtp();

        ExecutorService pool = Executors.newFixedThreadPool(5);
        CyclicBarrier barrier = new CyclicBarrier(5);
        Callable<ErrorCode> wrong = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                challenges.verifyChallenge(verify(view.challengeRef(), "000000"));
                return null;
            } catch (BankingBusinessException e) {
                return e.code();
            }
        };
        try {
            List<Future<ErrorCode>> results = pool.invokeAll(List.of(wrong, wrong, wrong, wrong, wrong));
            List<ErrorCode> codes = new ArrayList<>();
            for (Future<ErrorCode> future : results) {
                codes.add(future.get(15, TimeUnit.SECONDS));
            }
            // Không OTP nào thành công; chỉ 401/423; luôn có LOCKED.
            assertThat(codes).allSatisfy(code -> assertThat(code).isIn(
                    ErrorCode.AUTH_OTP_INVALID, ErrorCode.AUTH_CHALLENGE_LOCKED));
            assertThat(codes).contains(ErrorCode.AUTH_CHALLENGE_LOCKED);
            Map<String, Object> persisted = row(view.challengeRef());
            assertThat(persisted.get("status")).isEqualTo("LOCKED");
            assertThat(((Number) persisted.get("attempt_count")).intValue()).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    // T21: verify đúng + consume đồng thời → state nhất quán (chỉ consume-sau-verify thắng).
    @Test
    void t21_concurrentVerifyAndConsumeStayConsistent() throws Exception {
        StepUpChallengePort.ChallengeView view = issue("k18");
        String otp = lastOtp();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<ErrorCode> verifier = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                challenges.verifyChallenge(verify(view.challengeRef(), otp));
                return null;
            } catch (BankingBusinessException e) {
                return e.code();
            }
        };
        Callable<ErrorCode> consumer = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                t1.executeWithoutResult(ignored ->
                        challenges.consumeVerified(consume(view.challengeRef(), "k18")));
                return null;
            } catch (Exception failure) {
                Throwable root = failure;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                if (root instanceof BankingBusinessException b) {
                    return b.code();
                }
                throw failure;
            }
        };
        try {
            List<Future<ErrorCode>> results = pool.invokeAll(List.of(verifier, consumer));
            ErrorCode verifyOutcome = results.get(0).get(10, TimeUnit.SECONDS);
            ErrorCode consumeOutcome = results.get(1).get(10, TimeUnit.SECONDS);
            Map<String, Object> persisted = row(view.challengeRef());
            if (consumeOutcome == null) {
                // Consume thắng: verify phải đã VERIFIED trước đó trong cùng khoảng thời gian.
                assertThat(persisted.get("status")).isEqualTo("VERIFIED");
                assertThat(persisted.get("consumed_at")).isNotNull();
            } else {
                // Consume thua do verify chưa commit khi consume đọc (READ COMMITTED): T1 rollback,
                // consumed_at NULL. Verify có thể thành công sau đó (kết cục VERIFIED chưa consume)
                // — retry T1 sau verify là kết cục đã duyệt (design/02 §5.1).
                assertThat(consumeOutcome).isEqualTo(ErrorCode.STEP_UP_REQUIRED);
                assertThat((Timestamp) persisted.get("consumed_at")).isNull();
                assertThat((String) persisted.get("status")).isIn("VERIFIED", "CREATED", "SENT");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // T22: expire/consume race — challenge hết hạn đúng lúc consume → không consumed-sau-expired.
    @Test
    void t22_consumedAfterExpiryIsImpossible() {
        StepUpChallengePort.ChallengeView view = issue("k19");
        String otp = lastOtp();
        challenges.verifyChallenge(verify(view.challengeRef(), otp));
        setExpiryPast(view.challengeRef());

        assertThatThrownBy(() -> t1.executeWithoutResult(ignored ->
                challenges.consumeVerified(consume(view.challengeRef(), "k19"))))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_CHALLENGE_EXPIRED));
        assertThat(row(view.challengeRef()).get("consumed_at")).isNull();
    }

    // T23: secrets — response model + audit log + DB không chứa OTP plaintext.
    @Test
    void t23_otpSecretsNeverLeakThroughLogsOrDb() {
        StepUpChallengePort.ChallengeView view = issue("k20");
        String otp = lastOtp();
        challenges.verifyChallenge(verify(view.challengeRef(), otp));
        consumeInT1(consume(view.challengeRef(), "k20"));

        String allLogs = String.join(" ", auditLog.list.stream()
                .map(e -> e.getFormattedMessage()).toList());
        assertThat(allLogs).doesNotContain(otp);
        Map<String, Object> persisted = row(view.challengeRef());
        assertThat((String) persisted.get("otp_hash")).doesNotContain(otp);
        assertThat((String) persisted.get("otp_hash")).startsWith("$argon2id$");
        assertThat(view.toString()).doesNotContain(otp);
    }

}
