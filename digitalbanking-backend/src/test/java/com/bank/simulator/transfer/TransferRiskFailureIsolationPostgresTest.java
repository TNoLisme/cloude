package com.bank.simulator.transfer;

import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.risk.application.RiskEvaluationService;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.transfer.application.CreateTransferService;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
class TransferRiskFailureIsolationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transfer_risk_isolation_test").withUsername("test").withPassword("test");

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
    @MockBean RiskEvaluationService risk;

    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");

    @BeforeEach
    void resetDisposableTables() {
        jdbc.execute("TRUNCATE risk_flags, transfers, audit_events, idempotency_records, otp_challenges, " +
                "customer_pins, accounts, customers, user_roles, refresh_sessions, users CASCADE");
    }

    @Test
    void af06RiskFailureAfterCommitLeavesResponseAndMoneyUntouched() {
        var fixture = seedCustomer("0912345830");
        UUID destination = destinationAccount();
        doThrow(new IllegalStateException("risk database timeout")).when(risk).evaluate(any());

        var result = createTransfer.create(fixture.actor(), new CreateTransferService.CreateCommand(fixture.accountId(),
                destination, "100000", "VND", "000001", "AF-06 isolation"), UUID.randomUUID().toString());

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.replayed()).isFalse();
        assertThat(balance(fixture.accountId())).isEqualTo("9900000");
        assertThat(balance(destination)).isEqualTo("100000");
        assertThat(transfers.find(result.transferId()).status()).isEqualTo("COMPLETED");
        assertThat(transfers.find(result.transferId()).completedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type='TRANSFER_COMPLETED'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_flags", Integer.class)).isZero();
        verify(risk).evaluate(any());
    }

    @Test
    void af06TransferThatNeverCommitsDoesNotTriggerRiskEvaluation() {
        var fixture = seedCustomer("0912345831", "1000000");
        UUID destination = destinationAccount();

        assertThatThrownBy(() -> createTransfer.create(fixture.actor(),
                new CreateTransferService.CreateCommand(fixture.accountId(), destination, "2000000", "VND",
                        "000001", "Rejected before commit"), UUID.randomUUID().toString()))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("INSUFFICIENT_FUNDS");
                });

        verify(risk, never()).evaluate(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_flags", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isZero();
        assertThat(balance(fixture.accountId())).isEqualTo("1000000");
        assertThat(balance(destination)).isEqualTo("0");
    }

    private String balance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance::text FROM accounts WHERE id=?", String.class, accountId);
    }

    private UUID destinationAccount() {
        return identities.createDefaultAccount(UUID.randomUUID(), numbers, now).accountId();
    }

    private Fixture seedCustomer(String phone) {
        return seedCustomer(phone, "10000000");
    }

    private Fixture seedCustomer(String phone, String initialBalance) {
        UUID user = UUID.randomUUID(), customer = UUID.randomUUID();
        identities.createUser(user, phone, phone + "@risk-isolation.example.test", "unused-test-hash", "CUSTOMER", now);
        identities.createCustomer(customer, user, "Risk isolation customer", null, now);
        identities.setPin(customer, pins.encode("000001"), now);
        UUID accountId = identities.createDefaultAccount(customer, numbers, now).accountId();
        jdbc.update("UPDATE accounts SET balance=? WHERE id=?", new java.math.BigDecimal(initialBalance), accountId);
        return new Fixture(user, accountId);
    }

    private record Fixture(UUID userId, UUID accountId) {
        AuthenticatedActor actor() {
            return new AuthenticatedActor(userId, Set.of("CUSTOMER"));
        }
    }
}
