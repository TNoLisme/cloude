package com.bank.simulator.transfer;

import com.bank.simulator.BankingSimulatorApplication;
import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.transfer.application.CreateTransferService;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class TransferReplayPostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transfer_replay_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("app.security.jwt-secret", () -> "01234567890123456789012345678901");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CreateTransferService createTransfer;
    @Autowired TransferJdbcRepository transfers;
    @Autowired IdentityJdbcRepository identities;
    @Autowired PinHashingService pins;
    @Autowired AccountNumberGenerator numbers;

    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");

    @BeforeEach
    void resetDisposableTables() {
        jdbc.execute("TRUNCATE risk_flags, transfers, audit_events, idempotency_records, otp_challenges, " +
                "customer_pins, accounts, customers, user_roles, refresh_sessions, users CASCADE");
    }

    @Test
    void fi02RetryAfterCommitReplaysOriginalOutcomeWithoutASecondDebit() {
        var fixture = seedCustomer("0912345810");
        UUID destination = destinationAccount();
        var command = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "2000000",
                "VND", "000001", "FI-02 timeout retry");
        String key = UUID.randomUUID().toString();

        var first = createTransfer.create(fixture.actor(), command, key);
        assertThat(first.replayed()).isFalse();
        assertThat(first.status()).isEqualTo("COMPLETED");
        Instant completedAt = transfers.find(first.transferId()).completedAt();
        assertThat(completedAt).isNotNull();
        assertThat(idempotencyResponseStatus()).isEqualTo(201);
        assertThat(idempotencyResourceId()).isEqualTo(first.transferId());

        for (int retry = 0; retry < 3; retry++) {
            var replay = createTransfer.create(fixture.actor(), command, key);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.transferId()).isEqualTo(first.transferId());
            assertThat(replay.status()).isEqualTo("COMPLETED");
        }

        assertThat(transfers.find(first.transferId()).completedAt()).isEqualTo(completedAt);
        assertThat(balance(fixture.accountId())).isEqualTo("8000000");
        assertThat(balance(destination)).isEqualTo("2000000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class)).isEqualTo(1);
        assertThat(idempotencyResponseStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type='TRANSFER_COMPLETED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void fi05RetryThroughAFreshApplicationContextStillReplaysTheCommittedResult() {
        var fixture = seedCustomer("0912345811");
        UUID destination = destinationAccount();
        var command = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "2000000",
                "VND", "000001", "FI-05 restart");
        String key = UUID.randomUUID().toString();

        var first = createTransfer.create(fixture.actor(), command, key);
        assertThat(first.replayed()).isFalse();
        assertThat(balance(fixture.accountId())).isEqualTo("8000000");
        assertThat(balance(destination)).isEqualTo("2000000");

        try (ConfigurableApplicationContext restarted = restartedContext()) {
            CreateTransferService restartedCreate = restarted.getBean(CreateTransferService.class);
            var replay = restartedCreate.create(fixture.actor(), command, key);

            assertThat(replay.replayed()).isTrue();
            assertThat(replay.transferId()).isEqualTo(first.transferId());
            assertThat(replay.status()).isEqualTo("COMPLETED");
        }

        assertThat(balance(fixture.accountId())).isEqualTo("8000000");
        assertThat(balance(destination)).isEqualTo("2000000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type='TRANSFER_COMPLETED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void ad03ReplayAfterRetentionWindowIsTreatedAsANewIntent() {
        var fixture = seedCustomer("0912345812");
        UUID destination = destinationAccount();
        var command = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "2000000",
                "VND", "000001", "AD-03 expired key");
        String key = UUID.randomUUID().toString();

        var first = createTransfer.create(fixture.actor(), command, key);
        assertThat(first.replayed()).isFalse();
        jdbc.update("UPDATE idempotency_records SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 days', " +
                "expires_at = CURRENT_TIMESTAMP - INTERVAL '1 day' " +
                "WHERE operation='createTransfer' AND idempotency_key=?", key);

        var second = createTransfer.create(fixture.actor(), command, key);

        assertThat(second.replayed()).isFalse();
        assertThat(second.transferId()).isNotEqualTo(first.transferId());
        assertThat(balance(fixture.accountId())).isEqualTo("6000000");
        assertThat(balance(destination)).isEqualTo("4000000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class)).isEqualTo(1);
    }

    private ConfigurableApplicationContext restartedContext() {
        return new SpringApplicationBuilder(BankingSimulatorApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("spring.main.banner-mode=off", "logging.level.root=WARN")
                .run("--server.port=0",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--app.security.jwt-secret=01234567890123456789012345678901");
    }

    private int idempotencyResponseStatus() {
        return jdbc.queryForObject("SELECT response_status FROM idempotency_records", Integer.class);
    }

    private UUID idempotencyResourceId() {
        return jdbc.queryForObject("SELECT resource_id FROM idempotency_records", UUID.class);
    }

    private String balance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance::text FROM accounts WHERE id=?", String.class, accountId);
    }

    private UUID destinationAccount() {
        return identities.createDefaultAccount(UUID.randomUUID(), numbers, now).accountId();
    }

    private Fixture seedCustomer(String phone) {
        UUID user = UUID.randomUUID(), customer = UUID.randomUUID();
        identities.createUser(user, phone, phone + "@replay.example.test", "unused-test-hash", "CUSTOMER", now);
        identities.createCustomer(customer, user, "Replay customer", null, now);
        identities.setPin(customer, pins.encode("000001"), now);
        UUID accountId = identities.createDefaultAccount(customer, numbers, now).accountId();
        jdbc.update("UPDATE accounts SET balance=10000000 WHERE id=?", accountId);
        return new Fixture(user, accountId);
    }

    private record Fixture(UUID userId, UUID accountId) {
        AuthenticatedActor actor() {
            return new AuthenticatedActor(userId, Set.of("CUSTOMER"));
        }
    }
}
