package com.bank.simulator.transfer;

import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.otp.OtpMessage;
import com.bank.simulator.identity.infrastructure.otp.OtpSender;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.transfer.application.ConfirmTransferOtpService;
import com.bank.simulator.transfer.application.CreateTransferService;
import com.bank.simulator.transfer.application.TransferFailureInjector;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
@ActiveProfiles("transfer-failure-injection")
class TransferFailureInjectionPostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transfer_failure_injection_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("app.security.jwt-secret", () -> "01234567890123456789012345678901");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CreateTransferService createTransfer;
    @Autowired ConfirmTransferOtpService confirmTransfer;
    @Autowired TransferFailureInjector failureInjector;
    @Autowired TransferJdbcRepository transfers;
    @Autowired IdentityJdbcRepository identities;
    @Autowired PinHashingService pins;
    @Autowired AccountNumberGenerator numbers;
    @MockBean OtpSender sender;

    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");

    @BeforeEach
    void resetDisposableTables() {
        jdbc.execute("TRUNCATE risk_flags, transfers, audit_events, idempotency_records, otp_challenges, " +
                "customer_pins, accounts, customers, user_roles, refresh_sessions, users CASCADE");
    }

    @Test
    void fi01ThrowAfterDebitRollsBackBalancesTransferAuditAndRisk() {
        var fixture = seedCustomer("0912345700");
        UUID destination = destinationAccount();
        var command = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "50000",
                "VND", "000001", "FI-01 rollback");

        assertThatThrownBy(() -> createTransfer.create(fixture.actor(), command, UUID.randomUUID().toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FI-01");

        assertThat(balance(fixture.accountId())).isEqualTo("10000000");
        assertThat(balance(destination)).isEqualTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_flags", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class)).isZero();
    }

    @Test
    void fi01ThrowDuringConfirmRollsBackMoneyAndOtpConsumption() {
        var fixture = seedCustomer("0912345701");
        UUID destination = destinationAccount();
        var command = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "6000000",
                "VND", "000001", "FI-01 confirm rollback");
        var created = createTransfer.create(fixture.actor(), command, UUID.randomUUID().toString());
        UUID challengeId = transfers.find(created.transferId()).otpChallengeId();
        ArgumentCaptor<OtpMessage> captor = ArgumentCaptor.forClass(OtpMessage.class);
        verify(sender).send(captor.capture());

        assertThatThrownBy(() -> confirmTransfer.confirm(fixture.actor(), created.transferId(),
                captor.getValue().code()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FI-01");

        var row = transfers.find(created.transferId());
        assertThat(row.status()).isEqualTo("AWAITING_OTP");
        assertThat(row.expiresAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM otp_challenges WHERE id=?", Object.class, challengeId))
                .isNull();
        assertThat(balance(fixture.accountId())).isEqualTo("10000000");
        assertThat(balance(destination)).isEqualTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type='TRANSFER_COMPLETED'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_flags", Integer.class)).isZero();
    }

    @Test
    void testProfileWiresThrowingInjectorInsteadOfNoOp() {
        assertThat(failureInjector).isInstanceOf(ThrowingTransferFailureInjector.class);
    }

    private String balance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance::text FROM accounts WHERE id=?", String.class, accountId);
    }

    private UUID destinationAccount() {
        return identities.createDefaultAccount(UUID.randomUUID(), numbers, now).accountId();
    }

    private Fixture seedCustomer(String phone) {
        UUID user = UUID.randomUUID(), customer = UUID.randomUUID();
        identities.createUser(user, phone, phone + "@fi.example.test", "unused-test-hash", "CUSTOMER", now);
        identities.createCustomer(customer, user, "Failure injection customer", null, now);
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
