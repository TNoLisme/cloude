package com.bank.simulator.transfer;

import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.otp.OtpMessage;
import com.bank.simulator.identity.infrastructure.otp.OtpSender;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.transfer.application.TransferService;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
class TransferSmallTransferPostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transfer_small_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("app.security.jwt-secret", () -> "01234567890123456789012345678901");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransferService service;
    @Autowired TransferJdbcRepository transfers;
    @Autowired IdentityJdbcRepository identities;
    @Autowired PinHashingService pins;
    @Autowired AccountNumberGenerator numbers;
    @MockBean OtpSender sender;

    private final Instant now = Instant.parse("2026-10-21T10:00:00Z");

    @BeforeEach
    void resetDisposableTables() {
        jdbc.execute("TRUNCATE risk_flags, transfers, audit_events, idempotency_records, otp_challenges, " +
                "customer_pins, accounts, customers, user_roles, refresh_sessions, users CASCADE");
    }

    @Test
    void fiftyThousandTransferCompletesImmediatelyWithBalancesAuditAndReplay() {
        var fixture = seedCustomer("0912345670", now);
        var destination = identities.createDefaultAccount(UUID.randomUUID(), numbers, now).accountId();
        var command = new TransferService.CreateCommand(fixture.accountId(), destination, "50000", "VND", "000001", "W1 happy path");
        String key = UUID.randomUUID().toString();

        var result = service.create(new AuthenticatedActor(fixture.userId(), Set.of("CUSTOMER")), command, key);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(transfers.find(result.transferId()).completedAt()).isNotNull();
        assertThat(balance(fixture.accountId())).isEqualTo("9950000");
        assertThat(balance(destination)).isEqualTo("50000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type='TRANSFER_COMPLETED' " +
                "AND target_type='TRANSFER' AND target_id=? AND outcome='SUCCESS'", Integer.class, result.transferId()))
                .isEqualTo(1);

        var replay = service.create(new AuthenticatedActor(fixture.userId(), Set.of("CUSTOMER")), command, key);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transferId()).isEqualTo(result.transferId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(1);
        assertThat(balance(fixture.accountId())).isEqualTo("9950000");
        assertThat(balance(destination)).isEqualTo("50000");
    }

    @Test
    void fiveMillionBoundaryStillUsesSmallPathAndCompletesImmediately() {
        var fixture = seedCustomer("0912345671", now);
        var destination = identities.createDefaultAccount(UUID.randomUUID(), numbers, now).accountId();
        var command = new TransferService.CreateCommand(fixture.accountId(), destination, "5000000", "VND", "000001", "SM-04");

        var result = service.create(new AuthenticatedActor(fixture.userId(), Set.of("CUSTOMER")), command,
                UUID.randomUUID().toString());

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.challengeRequired()).isFalse();
        assertThat(balance(fixture.accountId())).isEqualTo("5000000");
        assertThat(balance(destination)).isEqualTo("5000000");
    }

    @Test
    void fiveMillionPlusOneUsesLargePathAwaitingOtpWithoutMovingMoney() {
        var fixture = seedCustomer("0912345672", now);
        var destination = identities.createDefaultAccount(UUID.randomUUID(), numbers, now).accountId();
        var command = new TransferService.CreateCommand(fixture.accountId(), destination, "5000001", "VND", "000001", "SM-05");

        var result = service.create(new AuthenticatedActor(fixture.userId(), Set.of("CUSTOMER")), command,
                UUID.randomUUID().toString());

        assertThat(result.status()).isEqualTo("AWAITING_OTP");
        assertThat(result.challengeRequired()).isTrue();
        assertThat(result.expiresAt()).isNotNull();
        assertThat(balance(fixture.accountId())).isEqualTo("10000000");
        assertThat(balance(destination)).isEqualTo("0");
        var row = transfers.find(result.transferId());
        assertThat(row.otpChallengeId()).isNotNull();
        assertThat(row.expiresAt()).isNotNull();
        ArgumentCaptor<OtpMessage> message = ArgumentCaptor.forClass(OtpMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().code()).matches("\\d{6}");
        assertThat(message.getValue().identifier()).isEqualTo("0912345672");
        assertThat(message.getValue().challengeId()).isEqualTo(row.otpChallengeId());
    }

    @Test
    void sameCreatedAtTieBreaksByIdDescending() {
        UUID source = UUID.randomUUID(), destination = UUID.randomUUID();
        Instant sameAt = Instant.parse("2026-10-21T09:00:00Z");
        UUID lowerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higherId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        for (UUID id : List.of(lowerId, higherId)) {
            jdbc.update("INSERT INTO transfers (id, source_account_id, destination_account_id, amount, currency, " +
                            "status, created_at, completed_at, updated_at) VALUES (?,?,?,'2000','VND','COMPLETED',?,?,?)",
                    id, source, destination, Timestamp.from(sameAt), Timestamp.from(sameAt), Timestamp.from(sameAt));
        }

        assertThat(transfers.findHistory(List.of(source), null, null, null, null, null, 10))
                .extracting(TransferJdbcRepository.TransferRow::id)
                .containsExactly(higherId, lowerId);
    }

    private String balance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance::text FROM accounts WHERE id=?", String.class, accountId);
    }

    private Fixture seedCustomer(String phone, Instant at) {
        UUID user = UUID.randomUUID(), customer = UUID.randomUUID();
        identities.createUser(user, phone, phone + "@integration.example.test", "unused-test-hash", "CUSTOMER", at);
        identities.createCustomer(customer, user, "Integration customer", null, at);
        identities.setPin(customer, pins.encode("000001"), at);
        UUID accountId = identities.createDefaultAccount(customer, numbers, at).accountId();
        jdbc.update("UPDATE accounts SET balance=10000000 WHERE id=?", accountId);
        return new Fixture(user, customer, accountId);
    }

    private record Fixture(UUID userId, UUID customerId, UUID accountId) {}
}
