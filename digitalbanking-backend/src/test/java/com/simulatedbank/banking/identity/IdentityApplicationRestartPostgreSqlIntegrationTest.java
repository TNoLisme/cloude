package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.BackendApplication;
import com.simulatedbank.banking.common.config.RequestRateLimiter;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.identity.adapter.in.AuthController;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.identity.adapter.out.IdentitySessionAuthenticator;
import com.simulatedbank.banking.identity.adapter.out.SecurityLogAuditAdapter;
import com.simulatedbank.banking.identity.api.IdentityRoleCommand;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.application.AuthAuditPort;
import com.simulatedbank.banking.identity.application.OtpChallengeService;
import com.simulatedbank.banking.notification.api.OtpGateway;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** BE-ID-TEST-02: real Spring wiring plus state recovery after full bean/context recreation. */
@Testcontainers
class IdentityApplicationRestartPostgreSqlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("identity_restart");

    @Test
    void realIdentityBeansAndPersistentSecurityStateSurviveApplicationRestart() {
        ConfigurableApplicationContext first = startApplication();
        Fixture fixture;
        Object firstAuthenticator;
        Object firstChallengePort;
        try {
            assertActualWiring(first);
            DataSource dataSource = first.getBean(DataSource.class);
            PostgreSqlIntegrationTestSupport.assertContainerDataSource(dataSource, postgres);
            fixture = seedPersistentSecurityState(first, dataSource);

            SessionAuthenticator authenticator = first.getBean(SessionAuthenticator.class);
            assertThat(authenticator.authenticate(fixture.rawSessionToken()))
                    .get()
                    .satisfies(session -> {
                        assertThat(session.userId()).isEqualTo(Long.toString(fixture.userId()));
                        assertThat(session.authorities()).contains("ROLE_CUSTOMER", "ACCOUNT_READ_SELF");
                        assertThat(session.toString()).doesNotContain(fixture.rawSessionToken(), fixture.rawCsrfToken());
                    });

            RequestRateLimiter limiter = first.getBean(RequestRateLimiter.class);
            assertThat(limiter.acquire("restart-proof", "synthetic-subject", 1, Duration.ofHours(1))).isZero();
            assertThat(limiter.acquire("restart-proof", "synthetic-subject", 1, Duration.ofHours(1))).isPositive();
            firstAuthenticator = authenticator;
            firstChallengePort = first.getBean(StepUpChallengePort.class);
        } finally {
            first.close();
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            assertActualWiring(second);
            DataSource dataSource = second.getBean(DataSource.class);
            PostgreSqlIntegrationTestSupport.assertContainerDataSource(dataSource, postgres);
            assertThat(second.getBean(SessionAuthenticator.class)).isNotSameAs(firstAuthenticator);
            assertThat(second.getBean(StepUpChallengePort.class)).isNotSameAs(firstChallengePort);

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Map<String, Object> user = jdbc.queryForMap(
                    "SELECT status, failed_attempt_count, password_hash, role_assignment_version "
                            + "FROM banking.app_user WHERE user_id=?", fixture.userId());
            assertThat(user.get("status")).isEqualTo("ACTIVE");
            assertThat(((Number) user.get("failed_attempt_count")).intValue()).isEqualTo(4);
            assertThat(user.get("password_hash").toString())
                    .startsWith("$argon2id$")
                    .doesNotContain(fixture.rawPassword());
            assertThat(((Number) user.get("role_assignment_version")).longValue()).isZero();

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM banking.password_history WHERE user_id=?", Integer.class,
                    fixture.userId())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM banking.device WHERE user_id=? AND trusted", Integer.class,
                    fixture.userId())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM banking.auth_attempt WHERE user_id=?", Integer.class,
                    fixture.userId())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT session_token_hash FROM banking.session WHERE user_id=?", String.class,
                    fixture.userId())).isEqualTo(HmacService.sha256Hex(fixture.rawSessionToken()));
            assertThat(jdbc.queryForObject(
                    "SELECT csrf_token_hash FROM banking.session WHERE user_id=?", String.class,
                    fixture.userId())).isEqualTo(HmacService.sha256Hex(fixture.rawCsrfToken()));

            var recoveredSession =
                    second.getBean(SessionAuthenticator.class).authenticate(fixture.rawSessionToken());
            assertThat(recoveredSession).isPresent();
            assertThat(recoveredSession.orElseThrow().authorities())
                    .contains("ROLE_CUSTOMER", "TRANSFER_CREATE");

            RequestRateLimiter recreatedLimiter = second.getBean(RequestRateLimiter.class);
            assertThat(recreatedLimiter.acquire(
                    "restart-proof", "synthetic-subject", 1, Duration.ofHours(1))).isZero();

            StepUpChallengePort challengePort = second.getBean(StepUpChallengePort.class);
            TransactionTemplate transaction =
                    new TransactionTemplate(second.getBean(PlatformTransactionManager.class));
            var consumed = transaction.execute(status -> challengePort.consumeVerified(
                    new StepUpChallengePort.ConsumeCommand(
                            fixture.challengeRef(), fixture.userId(),
                            StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP,
                            fixture.requestHash(), fixture.bindingKey())));
            assertThat(consumed).isNotNull();
            assertThat(jdbc.queryForObject(
                    "SELECT consumed_at IS NOT NULL FROM banking.auth_challenge "
                            + "WHERE challenge_ref=?::uuid", Boolean.class, fixture.challengeRef()))
                    .isTrue();
        }
    }

    private static ConfigurableApplicationContext startApplication() {
        Map<String, Object> properties =
                PostgreSqlIntegrationTestSupport.springApplicationProperties(postgres);
        properties.put("server.port", "0");
        properties.put("spring.main.register-shutdown-hook", "false");
        properties.put("spring.datasource.hikari.maximum-pool-size", "6");
        properties.put("spring.datasource.hikari.minimum-idle", "1");
        properties.put("app.identity.otp-expiry-sweep-delay", "PT24H");
        properties.put("app.security.rate-limit.read-per-user-per-minute", "10000");
        return new SpringApplicationBuilder(BackendApplication.class)
                .profiles("test")
                .web(WebApplicationType.SERVLET)
                .initializers(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("be-id-test-02-testcontainer", properties)))
                .run();
    }

    private static void assertActualWiring(ConfigurableApplicationContext context) {
        assertThat(context.getBeansOfType(SessionAuthenticator.class))
                .hasSize(1)
                .allSatisfy((name, bean) -> assertThat(bean).isExactlyInstanceOf(IdentitySessionAuthenticator.class));
        assertThat(context.getBeansOfType(StepUpChallengePort.class))
                .hasSize(1)
                .allSatisfy((name, bean) -> assertThat(bean).isInstanceOf(OtpChallengeService.class));
        assertThat(context.getBeansOfType(IdentityRoleCommand.class)).hasSize(1);
        assertThat(context.getBeansOfType(AuthController.class)).hasSize(1);
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
        assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(1);
        assertThat(context.getBeansOfType(AuthAuditPort.class))
                .hasSize(1)
                .allSatisfy((name, bean) -> assertThat(bean).isExactlyInstanceOf(SecurityLogAuditAdapter.class));
        assertThat(context.getBeansOfType(OtpGateway.class)).isEmpty();
    }

    private static Fixture seedPersistentSecurityState(
            ConfigurableApplicationContext context, DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate fixtureTransaction =
                new TransactionTemplate(new JdbcTransactionManager(dataSource));
        Argon2PasswordHasher hasher = context.getBean(Argon2PasswordHasher.class);
        String rawPassword = "RestartOnly-Password-17";
        String rawSessionToken = "restart-session-" + UUID.randomUUID();
        String rawCsrfToken = "restart-csrf-" + UUID.randomUUID();
        String challengeRef = UUID.randomUUID().toString();
        String requestHash = HmacService.sha256Hex("restart-transfer-payload");
        String bindingKey = "restart-idempotency-key";
        char[] otp = "731942".toCharArray();
        String otpHash;
        try {
            otpHash = hasher.hash(otp);
        } finally {
            Arrays.fill(otp, '\0');
        }
        String passwordHash = hasher.hash(rawPassword.toCharArray());
        String passcodeHash = hasher.hash("restart-passcode".toCharArray());

        long userId = fixtureTransaction.execute(status -> {
            Long id = jdbc.queryForObject(
                    "INSERT INTO banking.app_user "
                            + "(username,password_hash,user_type,status,failed_attempt_count,last_failed_at) "
                            + "VALUES (?,?, 'CUSTOMER','ACTIVE',4,now()) RETURNING user_id",
                    Long.class, "restart-user-" + UUID.randomUUID(), passwordHash);
            jdbc.update("INSERT INTO banking.password_history(user_id,password_hash) VALUES (?,?)",
                    id, passwordHash);
            Long deviceId = jdbc.queryForObject(
                    "INSERT INTO banking.device "
                            + "(device_ref,user_id,device_name,device_fingerprint_hash,passcode_hash,trusted,trusted_at) "
                            + "VALUES (?::uuid,?,'Restart fixture',?,?,true,now()) RETURNING device_id",
                    Long.class, UUID.randomUUID().toString(), id,
                    HmacService.sha256Hex("restart-device"), passcodeHash);
            jdbc.update("INSERT INTO banking.user_role(user_id,role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code='CUSTOMER'", id);
            jdbc.update("INSERT INTO banking.session "
                            + "(session_token_hash,csrf_token_hash,user_id,device_id,status,idle_expires_at,absolute_expires_at) "
                            + "VALUES (?,?,?,?, 'ACTIVE',now()+interval '15 minutes',now()+interval '8 hours')",
                    HmacService.sha256Hex(rawSessionToken), HmacService.sha256Hex(rawCsrfToken), id, deviceId);
            jdbc.update("INSERT INTO banking.auth_challenge "
                            + "(challenge_ref,user_id,device_id,purpose,method,channel,otp_hash,request_hash,"
                            + "idempotency_key,status,attempt_count,max_attempts,expires_at,verified_at,correlation_id) "
                            + "VALUES (?::uuid,?,?,'TRANSFER_STEP_UP','OTP','SMS',?,?,?,'VERIFIED',1,5,"
                            + "now()+interval '5 minutes',now(),'restart-correlation')",
                    challengeRef, id, deviceId, otpHash, requestHash, bindingKey);
            jdbc.update("INSERT INTO banking.auth_attempt "
                            + "(user_id,attempt_type,outcome,reason_code,correlation_id) "
                            + "VALUES (?,'LOGIN','FAILURE','INVALID_CREDENTIALS','restart-correlation')", id);
            return id;
        });
        return new Fixture(userId, rawPassword, rawSessionToken, rawCsrfToken,
                challengeRef, requestHash, bindingKey);
    }

    private record Fixture(long userId, String rawPassword, String rawSessionToken,
            String rawCsrfToken, String challengeRef, String requestHash, String bindingKey) {
    }
}
