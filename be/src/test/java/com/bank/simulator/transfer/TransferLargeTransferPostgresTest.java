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
import com.bank.simulator.transfer.application.TransferQueryService;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
class TransferLargeTransferPostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transfer_large_test").withUsername("test").withPassword("test");

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
    @Autowired TransferQueryService queries;
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
    void largeTransferLifecycleCompletesOnceAndReplaysCreateAndConfirm() {
        var fixture = seedCustomer("0912345680");
        var destination = destinationAccount();
        var command = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "6000000", "VND",
                "000001", "Large lifecycle");
        String key = UUID.randomUUID().toString();

        var created = createTransfer.create(fixture.actor(), command, key);

        assertThat(created.challengeRequired()).isTrue();
        assertThat(created.status()).isEqualTo("AWAITING_OTP");
        assertThat(created.expiresInSeconds()).isEqualTo(120);
        assertThat(balance(fixture.accountId())).isEqualTo("10000000");
        assertThat(balance(destination)).isEqualTo("0");
        var awaiting = transfers.find(created.transferId());
        assertThat(awaiting.otpChallengeId()).isNotNull();
        assertThat(awaiting.expiresAt()).isAfter(Instant.now());
        assertThat(challenge(awaiting.otpChallengeId()).purpose()).isEqualTo("TRANSFER_STEP_UP");
        assertThat(challenge(awaiting.otpChallengeId()).attempts()).isZero();

        String otp = capturedOtp().code();

        var replayWhileAwaiting = createTransfer.create(fixture.actor(), command, key);
        assertThat(replayWhileAwaiting.replayed()).isTrue();
        assertThat(replayWhileAwaiting.status()).isEqualTo("AWAITING_OTP");
        assertThat(replayWhileAwaiting.challengeRequired()).isTrue();
        verify(sender, times(1)).send(any());

        var confirmed = confirmTransfer.confirm(fixture.actor(), created.transferId(), otp);

        assertThat(confirmed.replayed()).isFalse();
        assertThat(balance(fixture.accountId())).isEqualTo("4000000");
        assertThat(balance(destination)).isEqualTo("6000000");
        var completed = transfers.find(created.transferId());
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.expiresAt()).isNull();
        assertThat(challenge(awaiting.otpChallengeId()).consumedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type='TRANSFER_COMPLETED' " +
                "AND target_id=? AND outcome='SUCCESS'", Integer.class, created.transferId())).isEqualTo(1);

        var confirmReplay = confirmTransfer.confirm(fixture.actor(), created.transferId(), otp);
        assertThat(confirmReplay.replayed()).isTrue();
        assertThat(balance(fixture.accountId())).isEqualTo("4000000");
        assertThat(balance(destination)).isEqualTo("6000000");

        var createReplay = createTransfer.create(fixture.actor(), command, key);
        assertThat(createReplay.replayed()).isTrue();
        assertThat(createReplay.status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class)).isEqualTo(1);
        verify(sender, times(1)).send(any());
    }

    @Test
    void wrongOtpAttemptsAreCommittedThenFifthFailsTransfer() {
        var fixture = seedCustomer("0912345681");
        var destination = destinationAccount();
        var command = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "6000000", "VND",
                "000001", "Wrong otp");
        var created = createTransfer.create(fixture.actor(), command, UUID.randomUUID().toString());
        UUID challengeId = transfers.find(created.transferId()).otpChallengeId();
        String wrong = capturedOtp().code().equals("000000") ? "000001" : "000000";

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThatThrownBy(() -> confirmTransfer.confirm(fixture.actor(), created.transferId(), wrong))
                    .isInstanceOf(ApiException.class)
                    .satisfies(error -> {
                        assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(((ApiException) error).code()).isEqualTo("OTP_INVALID");
                    });
            assertThat(transfers.find(created.transferId()).status()).isEqualTo("AWAITING_OTP");
            assertThat(challenge(challengeId).attempts()).isEqualTo(attempt);
            assertThat(challenge(challengeId).invalidatedAt()).isNull();
        }
        assertThat(balance(fixture.accountId())).isEqualTo("10000000");

        assertThatThrownBy(() -> confirmTransfer.confirm(fixture.actor(), created.transferId(), wrong))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("STATE_CONFLICT");
                });
        var failed = transfers.find(created.transferId());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureCode()).isEqualTo("OTP_ATTEMPTS_EXCEEDED");
        assertThat(challenge(challengeId).attempts()).isEqualTo(5);
        assertThat(challenge(challengeId).invalidatedAt()).isNotNull();

        assertThatThrownBy(() -> confirmTransfer.confirm(fixture.actor(), created.transferId(), capturedOtp().code()))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("STATE_CONFLICT"));
        assertThat(challenge(challengeId).attempts()).isEqualTo(5);
        assertThat(balance(fixture.accountId())).isEqualTo("10000000");
        assertThat(balance(destination)).isEqualTo("0");
    }

    @Test
    void lazyExpiryMarksReadsExpiredAndLateOtpCannotMoveMoney() {
        var fixture = seedCustomer("0912345682");
        var destination = destinationAccount();
        var command = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "6000000", "VND",
                "000001", "Lazy expiry");
        var created = createTransfer.create(fixture.actor(), command, UUID.randomUUID().toString());
        UUID challengeId = transfers.find(created.transferId()).otpChallengeId();
        String otp = capturedOtp().code();
        jdbc.update("UPDATE transfers SET expires_at=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), created.transferId());

        assertThat(transfers.find(created.transferId()).status()).isEqualTo("AWAITING_OTP");
        assertThat(queries.get(fixture.actor(), created.transferId()).status()).isEqualTo("EXPIRED");
        var page = queries.list(fixture.actor(), new TransferQueryService.HistoryQuery(10, null, "EXPIRED", null, null));
        assertThat(page.items()).extracting(TransferQueryService.TransferView::transferId).contains(created.transferId());
        assertThat(transfers.find(created.transferId()).status()).isEqualTo("AWAITING_OTP");

        assertThatThrownBy(() -> confirmTransfer.confirm(fixture.actor(), created.transferId(), otp))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("TRANSFER_EXPIRED");
                });
        var expired = transfers.find(created.transferId());
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.expiresAt()).isNull();
        assertThat(challenge(challengeId).consumedAt()).isNull();
        assertThat(balance(fixture.accountId())).isEqualTo("10000000");
        assertThat(balance(destination)).isEqualTo("0");
    }

    @Test
    void dispatchFailureCompensatesTransferAndChallengeAndReplaySeesFailedState() {
        var fixture = seedCustomer("0912345683");
        var destination = destinationAccount();
        var command = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "6000000", "VND",
                "000001", "Dispatch failure");
        String key = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("sms gateway down")).when(sender).send(any());

        assertThatThrownBy(() -> createTransfer.create(fixture.actor(), command, key))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((ApiException) error).code()).isEqualTo("SERVICE_UNAVAILABLE");
                });

        var failed = jdbc.queryForMap("SELECT id, status, failure_code, otp_challenge_id FROM transfers");
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("failure_code")).isEqualTo("OTP_DISPATCH_FAILED");
        UUID challengeId = (UUID) failed.get("otp_challenge_id");
        assertThat(challenge(challengeId).invalidatedAt()).isNotNull();
        assertThat(balance(fixture.accountId())).isEqualTo("10000000");
        assertThat(balance(destination)).isEqualTo("0");

        var replay = createTransfer.create(fixture.actor(), command, key);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo("FAILED");
        verify(sender, times(1)).send(any());
    }

    @Test
    void completedTerminalCannotBeUpdatedBackToAwaitingOtp() {
        var fixture = seedCustomer("0912345684");
        var destination = destinationAccount();
        var command = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "6000000", "VND",
                "000001", "SM-01");
        var created = createTransfer.create(fixture.actor(), command, UUID.randomUUID().toString());
        confirmTransfer.confirm(fixture.actor(), created.transferId(), capturedOtp().code());

        assertThatThrownBy(() -> jdbc.update("UPDATE transfers SET status='AWAITING_OTP' WHERE id=?",
                created.transferId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        var row = transfers.find(created.transferId());
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.completedAt()).isNotNull();
    }

    @Test
    void compensationFailAfterCompletionTouchesNoRows() {
        var fixture = seedCustomer("0912345686");
        var destination = destinationAccount();
        var command = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "6000000", "VND",
                "000001", "AF-04 guard");
        var created = createTransfer.create(fixture.actor(), command, UUID.randomUUID().toString());
        confirmTransfer.confirm(fixture.actor(), created.transferId(), capturedOtp().code());
        Instant completedAt = transfers.find(created.transferId()).completedAt();

        transfers.fail(created.transferId(), "OTP_DISPATCH_FAILED", completedAt.plusSeconds(1));

        var row = transfers.find(created.transferId());
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.completedAt()).isEqualTo(completedAt);
        assertThat(row.failureCode()).isNull();
        assertThat(row.expiresAt()).isNull();
    }

    @Test
    void expiredTransferRejectsConfirmWithoutConsumingChallenge() {
        var fixture = seedCustomer("0912345685");
        var destination = destinationAccount();
        var command = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "6000000", "VND",
                "000001", "SM-03");
        var created = createTransfer.create(fixture.actor(), command, UUID.randomUUID().toString());
        UUID challengeId = transfers.find(created.transferId()).otpChallengeId();
        String otp = capturedOtp().code();
        jdbc.update("UPDATE transfers SET status='EXPIRED', expires_at=NULL WHERE id=?", created.transferId());

        assertThatThrownBy(() -> confirmTransfer.confirm(fixture.actor(), created.transferId(), otp))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("TRANSFER_EXPIRED");
                });
        assertThat(challenge(challengeId).consumedAt()).isNull();
        assertThat(balance(fixture.accountId())).isEqualTo("10000000");
    }

    private OtpMessage capturedOtp() {
        ArgumentCaptor<OtpMessage> captor = ArgumentCaptor.forClass(OtpMessage.class);
        verify(sender, atLeastOnce()).send(captor.capture());
        List<OtpMessage> all = captor.getAllValues();
        return all.getLast();
    }

    private ChallengeRow challenge(UUID challengeId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT attempts, max_attempts, purpose, consumed_at, invalidated_at, expires_at
                FROM otp_challenges WHERE id=?
                """, challengeId);
        return new ChallengeRow((Integer) row.get("attempts"), (Integer) row.get("max_attempts"),
                (String) row.get("purpose"), (Timestamp) row.get("consumed_at"),
                (Timestamp) row.get("invalidated_at"), (Timestamp) row.get("expires_at"));
    }

    private String balance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance::text FROM accounts WHERE id=?", String.class, accountId);
    }

    private UUID destinationAccount() {
        return identities.createDefaultAccount(UUID.randomUUID(), numbers, now).accountId();
    }

    private Fixture seedCustomer(String phone) {
        UUID user = UUID.randomUUID(), customer = UUID.randomUUID();
        identities.createUser(user, phone, phone + "@large.example.test", "unused-test-hash", "CUSTOMER", now);
        identities.createCustomer(customer, user, "Large transfer customer", null, now);
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

    private record ChallengeRow(int attempts, int maxAttempts, String purpose, Timestamp consumedAt,
                                Timestamp invalidatedAt, Timestamp expiresAt) {}
}
