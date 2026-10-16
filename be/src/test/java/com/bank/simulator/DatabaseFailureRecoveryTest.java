package com.bank.simulator;

import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.identity.infrastructure.otp.OtpSender;
import com.bank.simulator.shared.health.DatabaseHealthIndicator;
import com.bank.simulator.transfer.application.TransferService;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-B resilience harness: disposable-stack persistence and recovery proof.
 *
 * <p>Owner: BE-B (lifecycle, isolation, evidence). Transfer behavior
 * assertions belong to BE-3; auth/OTP assertions belong to BE-A.
 * Does not stop the container: Testcontainers assigns a dynamic port on
 * restart, so stop/start would invalidate the Spring datasource. DB-outage
 * 503 shape is covered at MVC slice by {@code HealthControllerTest}.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DatabaseFailureRecoveryTest {

    private static final int OUTAGE_TIMEOUT_SECONDS = 5;
    static final Network network = Network.newNetwork();

    @Container
    static final PostgreSQLContainer<?> postgres =
            DisposablePostgresTestSupport.newDisposablePostgres("failure_recovery_test")
                    .withNetwork(network)
                    .withNetworkAliases("failure-recovery-postgres");

    @Container
    static final ToxiproxyContainer toxiproxy =
            new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0").withNetwork(network);

    static ToxiproxyContainer.ContainerProxy postgresProxy;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        postgresProxy = toxiproxy.getProxy(postgres, 5432);
        String datasourceUrl = postgresJdbcUrl();
        registry.add("spring.datasource.url", () -> datasourceUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.security.jwt-secret", () -> DisposablePostgresTestSupport.JWT_TEST_SECRET);
        registry.add("spring.datasource.hikari.connection-timeout", () -> OUTAGE_TIMEOUT_SECONDS * 1000);
        registry.add("spring.datasource.hikari.validation-timeout", () -> OUTAGE_TIMEOUT_SECONDS * 1000);
    }

    private static String postgresJdbcUrl() {
        return "jdbc:postgresql://" + toxiproxy.getHost() + ":" + postgresProxy.getProxyPort()
                + "/failure_recovery_test?connectTimeout=2&socketTimeout=2";
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransferService transfers;
    @Autowired TransferJdbcRepository transferRows;
    @Autowired IdentityJdbcRepository identities;
    @Autowired PinHashingService pins;
    @Autowired AccountNumberGenerator numbers;
    @Autowired DatabaseHealthIndicator databaseHealth;

    @MockBean OtpSender sender;

    private final Instant now = Instant.parse("2026-10-08T10:00:00Z");

    @AfterAll
    static void closeDockerNetwork() {
        network.close();
    }

    @BeforeEach
    void resetDisposableTables() {
        DisposablePostgresTestSupport.clearBusinessTables(jdbc, postgres, postgresJdbcUrl());
    }

    @Test
    void databaseOutageFailsHealthAndRestoresWithoutChangingDatasourceAddress() throws Exception {
        assertThat(databaseHealth.isAvailable()).isTrue();
        String datasourceUrl = postgresJdbcUrl();
        postgresProxy.setConnectionCut(true);

        try {
            assertThat(databaseHealth.isAvailable()).isFalse();
        } finally {
            postgresProxy.setConnectionCut(false);
        }

        assertThat(databaseHealth.isAvailable()).isTrue();
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(datasourceUrl);
        }
    }

    @Test
    void committedTransferSurvivesFreshReadsAndSameKeyReplayReturnsOriginal() {
        UUID user = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        identities.createUser(
                user, "0912345678", "recovery@example.test", "unused-test-hash", "CUSTOMER", now);
        identities.createCustomer(customer, user, "Recovery customer", null, now);
        identities.setPin(customer, pins.encode("000001"), now);
        UUID source = identities.createDefaultAccount(customer, numbers, now).accountId();
        UUID destination = identities.createDefaultAccount(UUID.randomUUID(), numbers, now).accountId();
        jdbc.update("UPDATE accounts SET balance=10000000 WHERE id=?", source);

        var actor = new AuthenticatedActor(user, Set.of("CUSTOMER"));
        var command = new TransferService.CreateCommand(
                source, destination, "5000000", "VND", "000001", "Recovery");
        String key = UUID.randomUUID().toString();

        var first = transfers.create(actor, command, key);
        assertThat(first.status()).isEqualTo("COMPLETED");

        // Verify persisted state through a fresh query, independent of the
        // in-memory result returned by the transfer service.
        var persisted = transferRows.find(first.transferId());
        assertThat(persisted).isNotNull();
        assertThat(persisted.status()).isEqualTo("COMPLETED");
        assertThat(persisted.completedAt()).isNotNull();
        assertThat(jdbc.queryForObject(
                        "SELECT balance::text FROM accounts WHERE id=?", String.class, source))
                .isEqualTo("5000000");
        assertThat(jdbc.queryForObject(
                        "SELECT balance::text FROM accounts WHERE id=?", String.class, destination))
                .isEqualTo("5000000");

        // Retry with the same actor, payload, and key returns the original
        // logical result with exactly one debit and one credit.
        var replay = transfers.create(actor, command, key);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transferId()).isEqualTo(first.transferId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT balance::text FROM accounts WHERE id=?", String.class, source))
                .isEqualTo("5000000");
    }

    @Test
    void applicationRestartPreservesCommittedTransferAndSameKeyReplayReturnsOriginal() {
        UUID user = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        identities.createUser(
                user, "0923456789", "restart@example.test", "unused-test-hash", "CUSTOMER", now);
        identities.createCustomer(customer, user, "Restart customer", null, now);
        identities.setPin(customer, pins.encode("000001"), now);
        UUID source = identities.createDefaultAccount(customer, numbers, now).accountId();
        UUID destination = identities.createDefaultAccount(UUID.randomUUID(), numbers, now).accountId();
        jdbc.update("UPDATE accounts SET balance=10000000 WHERE id=?", source);

        var actor = new AuthenticatedActor(user, Set.of("CUSTOMER"));
        var command = new TransferService.CreateCommand(
                source, destination, "4000000", "VND", "000001", "Restart test");
        String key = UUID.randomUUID().toString();

        var first = transfers.create(actor, command, key);
        assertThat(first.status()).isEqualTo("COMPLETED");

        try (var restartedContext = new org.springframework.boot.builder.SpringApplicationBuilder(BankingSimulatorApplication.class)
                .web(org.springframework.boot.WebApplicationType.SERVLET)
                .properties(
                        "server.port=0",
                        "DB_URL=" + postgresJdbcUrl(),
                        "DB_USERNAME=" + postgres.getUsername(),
                        "DB_PASSWORD=" + postgres.getPassword(),
                        "spring.datasource.url=" + postgresJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "spring.flyway.url=" + postgresJdbcUrl(),
                        "spring.flyway.user=" + postgres.getUsername(),
                        "spring.flyway.password=" + postgres.getPassword(),
                        "spring.flyway.enabled=false",
                        "JWT_SECRET=" + DisposablePostgresTestSupport.JWT_TEST_SECRET,
                        "app.security.jwt-secret=" + DisposablePostgresTestSupport.JWT_TEST_SECRET
                )
                .run()) {

            var restartedTransfers = restartedContext.getBean(TransferService.class);
            var restartedJdbc = restartedContext.getBean(JdbcTemplate.class);

            var replay = restartedTransfers.create(actor, command, key);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.transferId()).isEqualTo(first.transferId());
            assertThat(restartedJdbc.queryForObject(
                    "SELECT count(*) FROM transfers", Integer.class))
                    .isEqualTo(1);
            assertThat(restartedJdbc.queryForObject(
                    "SELECT count(*) FROM idempotency_records WHERE idempotency_key=?", Integer.class, key))
                    .isEqualTo(1);
            assertThat(restartedJdbc.queryForObject(
                    "SELECT balance::text FROM accounts WHERE id=?", String.class, source))
                    .isEqualTo("6000000");
            assertThat(restartedJdbc.queryForObject(
                    "SELECT balance::text FROM accounts WHERE id=?", String.class, destination))
                    .isEqualTo("4000000");
        }
    }
}
