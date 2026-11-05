package com.bank.simulator.transfer;

import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.otp.OtpMessage;
import com.bank.simulator.identity.infrastructure.otp.OtpSender;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.transfer.application.ConfirmTransferOtpService;
import com.bank.simulator.transfer.application.CreateTransferService;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
class TransferRiskIntegrationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transfer_risk_test").withUsername("test").withPassword("test");

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
    void af06LargeCompletedTransferGetsLargeTransferFlagAndFiveMillionDoesNot() {
        var large = seedCustomer("0912345820");
        UUID largeDestination = destinationAccount();
        var created = createTransfer(large, largeDestination, "6000000", "Risk large");
        confirmTransfer.confirm(large.actor(), created.transferId(), capturedOtp().code());

        var flag = onlyFlag(created.transferId());
        assertThat(flag.get("rule_id")).isEqualTo("LARGE_TRANSFER");
        assertThat(flag.get("rule_version")).isEqualTo("1");
        assertThat(flag.get("reason")).asString().isNotBlank();
        assertThat(sourceAccountOf(created.transferId())).isEqualTo(large.accountId());
        assertThat(transfers.find(created.transferId()).status()).isEqualTo("COMPLETED");

        var small = seedCustomer("0912345821");
        UUID smallDestination = destinationAccount();
        var smallTransfer = createTransfer(small, smallDestination, "5000000", "Risk boundary");
        assertThat(transfers.find(smallTransfer.transferId()).status()).isEqualTo("COMPLETED");
        assertThat(flagsOf(smallTransfer.transferId())).isEmpty();
    }

    @Test
    void af06SixthCompletedTransferForTheSameSourceGetsHighFrequencyFlag() {
        var fixture = seedCustomer("0912345822");
        UUID destination = destinationAccount();
        var firstFive = new java.util.ArrayList<UUID>();
        for (int index = 0; index < 5; index++) {
            firstFive.add(createTransfer(fixture, destination, "100000", "Fan-out " + index).transferId());
        }
        firstFive.forEach(transferId -> assertThat(flagsOf(transferId)).isEmpty());

        var sixth = createTransfer(fixture, destination, "100000", "Fan-out 5");

        var flag = onlyFlag(sixth.transferId());
        assertThat(flag.get("rule_id")).isEqualTo("HIGH_FREQUENCY");
        assertThat(flag.get("rule_version")).isEqualTo("1");
        assertThat(sourceAccountOf(sixth.transferId())).isEqualTo(fixture.accountId());
        assertThat(balance(fixture.accountId())).isEqualTo("9400000");
        assertThat(balance(destination)).isEqualTo("600000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE balance < 0", Integer.class)).isZero();
    }

    @Test
    void terminalStatesAreNeverOverwrittenByCompleteExpireOrFail() {
        var completedFixture = seedCustomer("0912345824");
        UUID completedDestination = destinationAccount();
        var completed = createTransfer(completedFixture, completedDestination, "100000", "Terminal completed");
        assertTerminalGuard(completed.transferId(), "COMPLETED", null);

        var expiredFixture = seedCustomer("0912345825");
        UUID expiredDestination = destinationAccount();
        UUID expiredId = createTransfer(expiredFixture, expiredDestination, "6000000", "Terminal expired").transferId();
        transfers.expire(expiredId, now);
        assertThat(transfers.find(expiredId).status()).isEqualTo("EXPIRED");
        assertTerminalGuard(expiredId, "EXPIRED", null);

        var failedFixture = seedCustomer("0912345826");
        UUID failedDestination = destinationAccount();
        doThrow(new IllegalStateException("sms gateway down")).when(sender).send(any());
        assertThatThrownBy(() -> createTransfer(failedFixture, failedDestination, "6000000", "Terminal failed"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        UUID failedId = transferIdByMemo("Terminal failed");
        assertThat(transfers.find(failedId).status()).isEqualTo("FAILED");
        assertTerminalGuard(failedId, "FAILED", "OTP_DISPATCH_FAILED");

        assertThatThrownBy(() -> jdbc.update("UPDATE transfers SET status='AWAITING_OTP' WHERE id=?", failedId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE transfers SET status='AWAITING_OTP' WHERE id=?", expiredId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertTerminalGuard(UUID transferId, String status, String failureCode) {
        var before = transfers.find(transferId);
        assertThat(before.status()).isEqualTo(status);
        assertThat(before.failureCode()).isEqualTo(failureCode);
        Instant completedAt = before.completedAt();
        Instant expiresAt = before.expiresAt();

        transfers.complete(transferId, now.plusSeconds(30));
        transfers.expire(transferId, now.plusSeconds(31));
        transfers.fail(transferId, "MUTATION_ATTEMPT", now.plusSeconds(32));

        var after = transfers.find(transferId);
        assertThat(after.status()).isEqualTo(status);
        assertThat(after.failureCode()).isEqualTo(failureCode);
        assertThat(after.completedAt()).isEqualTo(completedAt);
        assertThat(after.expiresAt()).isEqualTo(expiresAt);
    }

    private Map<String, Object> onlyFlag(UUID transferId) {
        List<Map<String, Object>> flags = flagsOf(transferId);
        assertThat(flags).hasSize(1);
        return flags.getFirst();
    }

    private List<Map<String, Object>> flagsOf(UUID transferId) {
        return jdbc.queryForList("""
                SELECT rule_id, rule_version, reason FROM risk_flags WHERE transfer_id = ? ORDER BY rule_id
                """, transferId);
    }

    private UUID sourceAccountOf(UUID transferId) {
        return jdbc.queryForObject("SELECT source_account_id FROM transfers WHERE id = ?", UUID.class, transferId);
    }

    private CreateTransferService.CreateResult createTransfer(Fixture fixture, UUID destination, String amount,
                                                              String memo) {
        return createTransfer.create(fixture.actor(), new CreateTransferService.CreateCommand(fixture.accountId(),
                destination, amount, "VND", "000001", memo), UUID.randomUUID().toString());
    }

    private UUID transferIdByMemo(String memo) {
        return jdbc.queryForObject("SELECT id FROM transfers WHERE memo = ?", UUID.class, memo);
    }

    private OtpMessage capturedOtp() {
        ArgumentCaptor<OtpMessage> captor = ArgumentCaptor.forClass(OtpMessage.class);
        verify(sender, atLeastOnce()).send(captor.capture());
        List<OtpMessage> all = captor.getAllValues();
        return all.getLast();
    }

    private String balance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance::text FROM accounts WHERE id=?", String.class, accountId);
    }

    private UUID destinationAccount() {
        return identities.createDefaultAccount(UUID.randomUUID(), numbers, now).accountId();
    }

    private Fixture seedCustomer(String phone) {
        UUID user = UUID.randomUUID(), customer = UUID.randomUUID();
        identities.createUser(user, phone, phone + "@risk.example.test", "unused-test-hash", "CUSTOMER", now);
        identities.createCustomer(customer, user, "Risk customer", null, now);
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
