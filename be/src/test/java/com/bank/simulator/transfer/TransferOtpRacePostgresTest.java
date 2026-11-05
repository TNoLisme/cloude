package com.bank.simulator.transfer;

import com.bank.simulator.account.application.AccountStatusService;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
class TransferOtpRacePostgresTest {
    private static final int TIMEOUT_SECONDS = 20;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transfer_otp_race_test").withUsername("test").withPassword("test");

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
    @Autowired AccountStatusService accountStatuses;
    @MockBean OtpSender sender;

    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");

    @BeforeEach
    void resetDisposableTables() {
        jdbc.execute("TRUNCATE risk_flags, transfers, audit_events, idempotency_records, otp_challenges, " +
                "customer_pins, accounts, customers, user_roles, refresh_sessions, users CASCADE");
    }

    @Test
    void con04ConcurrentDuplicateConfirmDebitsOnceAndReplaysTheOther() throws Exception {
        var fixture = seedCustomer("0912345800");
        UUID destination = destinationAccount();
        var created = createLargeTransfer(fixture, destination, "6000000", "CON-04 duplicate");
        String otp = capturedOtp().code();
        UUID challengeId = transfers.find(created.transferId()).otpChallengeId();

        List<Attempt<ConfirmTransferOtpService.ConfirmResult>> outcomes = runConcurrently(List.of(
                () -> attempt(() -> confirmTransfer.confirm(fixture.actor(), created.transferId(), otp)),
                () -> attempt(() -> confirmTransfer.confirm(fixture.actor(), created.transferId(), otp))));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        assertThat(outcomes.stream().filter(outcome -> !outcome.value().replayed()).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.value().replayed()).count()).isEqualTo(1);

        assertThat(balance(fixture.accountId())).isEqualTo("4000000");
        assertThat(balance(destination)).isEqualTo("6000000");
        assertThat(transfers.find(created.transferId()).status()).isEqualTo("COMPLETED");
        assertThat(transfers.find(created.transferId()).completedAt()).isNotNull();
        assertThat(challenge(challengeId).consumedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_challenges", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE balance < 0", Integer.class)).isZero();
        assertThat(Long.parseLong(balance(fixture.accountId())) + Long.parseLong(balance(destination)))
                .isEqualTo(10_000_000L);
    }

    @Test
    void con04ReplayAfterCompletionWithWrongOtpDoesNotCountAnAttempt() {
        var fixture = seedCustomer("0912345801");
        UUID destination = destinationAccount();
        var created = createLargeTransfer(fixture, destination, "6000000", "CON-04 replay");
        String otp = capturedOtp().code();
        UUID challengeId = transfers.find(created.transferId()).otpChallengeId();
        confirmTransfer.confirm(fixture.actor(), created.transferId(), otp);
        int attemptsAfterCompletion = challenge(challengeId).attempts();

        var replay = confirmTransfer.confirm(fixture.actor(), created.transferId(), wrongOtp(otp));

        assertThat(replay.replayed()).isTrue();
        assertThat(challenge(challengeId).attempts()).isEqualTo(attemptsAfterCompletion);
        assertThat(challenge(challengeId).consumedAt()).isNotNull();
        assertThat(balance(fixture.accountId())).isEqualTo("4000000");
        assertThat(balance(destination)).isEqualTo("6000000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(1);
    }

    @Test
    void con05BlockBeforeConfirmFailsTransferAsTerminalWithoutMovingMoney() {
        var fixture = seedCustomer("0912345802");
        UUID destination = destinationAccount();
        var created = createLargeTransfer(fixture, destination, "6000000", "CON-05 block first");
        UUID challengeId = transfers.find(created.transferId()).otpChallengeId();
        String otp = capturedOtp().code();

        accountStatuses.change(operator(), fixture.accountId(), "BLOCKED", "Fraud investigation");

        assertThatThrownBy(() -> confirmTransfer.confirm(fixture.actor(), created.transferId(), otp))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) error).code()).isEqualTo("ACCOUNT_NOT_ELIGIBLE");
                });

        var row = transfers.find(created.transferId());
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.failureCode()).isEqualTo("ACCOUNT_NOT_ELIGIBLE");
        assertThat(row.completedAt()).isNull();
        assertThat(row.expiresAt()).isNull();
        assertThat(challenge(challengeId).consumedAt()).isNotNull();
        assertThat(balance(fixture.accountId())).isEqualTo("10000000");
        assertThat(balance(destination)).isEqualTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_flags", Integer.class)).isZero();
    }

    @Test
    void con05ConfirmBeforeBlockKeepsCompletedTransferAndBlockOnlyGuardsFutureTransfers() {
        var fixture = seedCustomer("0912345803");
        UUID destination = destinationAccount();
        var created = createLargeTransfer(fixture, destination, "6000000", "CON-05 confirm first");

        var confirmed = confirmTransfer.confirm(fixture.actor(), created.transferId(), capturedOtp().code());
        assertThat(confirmed.replayed()).isFalse();
        Instant completedAt = transfers.find(created.transferId()).completedAt();

        accountStatuses.change(operator(), fixture.accountId(), "BLOCKED", "Post-completion review");

        var row = transfers.find(created.transferId());
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.completedAt()).isEqualTo(completedAt);
        assertThat(row.failureCode()).isNull();
        assertThat(accountStatus(fixture.accountId())).isEqualTo("BLOCKED");
        assertThat(balance(fixture.accountId())).isEqualTo("4000000");
        assertThat(balance(destination)).isEqualTo("6000000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE balance < 0", Integer.class)).isZero();
    }

    @Test
    void con05ConcurrentBlockAndConfirmAlwaysLeaveAConsistentOutcome() throws Exception {
        var fixture = seedCustomer("0912345804");
        UUID destination = destinationAccount();
        var created = createLargeTransfer(fixture, destination, "6000000", "CON-05 race");
        UUID challengeId = transfers.find(created.transferId()).otpChallengeId();
        String otp = capturedOtp().code();

        List<Callable<Attempt<Object>>> tasks = new ArrayList<>();
        tasks.add(() -> attempt(() -> (Object) confirmTransfer.confirm(fixture.actor(), created.transferId(), otp)));
        tasks.add(() -> attempt(() -> (Object) accountStatuses.change(operator(), fixture.accountId(), "BLOCKED", "Race")));
        List<Attempt<Object>> outcomes = runConcurrently(tasks);

        assertThat(outcomes.get(1).error()).isNull();
        if (outcomes.get(0).error() != null) {
            assertThat(outcomes.get(0).error().status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(outcomes.get(0).error().code()).isEqualTo("ACCOUNT_NOT_ELIGIBLE");
        }
        assertThat(accountStatus(fixture.accountId())).isEqualTo("BLOCKED");
        assertThat(challenge(challengeId).consumedAt()).isNotNull();

        var row = transfers.find(created.transferId());
        if ("COMPLETED".equals(row.status())) {
            assertThat(balance(fixture.accountId())).isEqualTo("4000000");
            assertThat(balance(destination)).isEqualTo("6000000");
            assertThat(row.failureCode()).isNull();
        } else {
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.failureCode()).isEqualTo("ACCOUNT_NOT_ELIGIBLE");
            assertThat(balance(fixture.accountId())).isEqualTo("10000000");
            assertThat(balance(destination)).isEqualTo("0");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE balance < 0", Integer.class)).isZero();
    }

    @Test
    void fi04ConfirmWinsBeforeCompensationAndCompensationIsANoop() throws Exception {
        var fixture = seedCustomer("0912345805");
        UUID destination = destinationAccount();
        var command = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "6000000",
                "VND", "000001", "FI-04 confirm wins");
        String key = UUID.randomUUID().toString();
        var dispatch = new ControlledDispatch();
        doAnswer(dispatch).when(sender).send(any());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Attempt<CreateTransferService.CreateResult>> createFuture = executor.submit(
                    () -> attempt(() -> createTransfer.create(fixture.actor(), command, key)));
            assertThat(dispatch.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            UUID transferId = onlyTransferId();
            UUID challengeId = transfers.find(transferId).otpChallengeId();

            Future<Attempt<ConfirmTransferOtpService.ConfirmResult>> confirmFuture = executor.submit(
                    () -> attempt(() -> confirmTransfer.confirm(fixture.actor(), transferId, dispatch.otp.get())));
            Attempt<ConfirmTransferOtpService.ConfirmResult> confirmed = confirmFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(confirmed.error()).isNull();
            assertThat(confirmed.value().replayed()).isFalse();

            dispatch.release.countDown();
            Attempt<CreateTransferService.CreateResult> created = createFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(created.error()).isNotNull();
            assertThat(created.error().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(created.error().code()).isEqualTo("SERVICE_UNAVAILABLE");

            var row = transfers.find(transferId);
            assertThat(row.status()).isEqualTo("COMPLETED");
            assertThat(row.completedAt()).isNotNull();
            assertThat(row.failureCode()).isNull();
            assertThat(row.expiresAt()).isNull();
            assertThat(challenge(challengeId).consumedAt()).isNotNull();
            assertThat(balance(fixture.accountId())).isEqualTo("4000000");
            assertThat(balance(destination)).isEqualTo("6000000");
            verify(sender).send(any());
        } finally {
            dispatch.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void fi04CompensationWinsThenConfirmRejectsDeadOtpWithoutMovingMoney() throws Exception {
        var fixture = seedCustomer("0912345806");
        UUID destination = destinationAccount();
        var command = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "6000000",
                "VND", "000001", "FI-04 compensation wins");
        String key = UUID.randomUUID().toString();
        var dispatch = new ControlledDispatch();
        doAnswer(dispatch).when(sender).send(any());
        ExecutorService executor = Executors.newFixedThreadPool(1);

        try {
            Future<Attempt<CreateTransferService.CreateResult>> createFuture = executor.submit(
                    () -> attempt(() -> createTransfer.create(fixture.actor(), command, key)));
            assertThat(dispatch.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            dispatch.release.countDown();

            Attempt<CreateTransferService.CreateResult> created = createFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(created.error()).isNotNull();
            assertThat(created.error().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

            UUID transferId = onlyTransferId();
            UUID challengeId = transfers.find(transferId).otpChallengeId();
            var failed = transfers.find(transferId);
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.failureCode()).isEqualTo("OTP_DISPATCH_FAILED");
            assertThat(challenge(challengeId).invalidatedAt()).isNotNull();
            assertThat(challenge(challengeId).consumedAt()).isNull();
            assertThat(balance(fixture.accountId())).isEqualTo("10000000");
            assertThat(balance(destination)).isEqualTo("0");

            assertThatThrownBy(() -> confirmTransfer.confirm(fixture.actor(), transferId, dispatch.otp.get()))
                    .isInstanceOf(ApiException.class)
                    .satisfies(error -> {
                        assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(((ApiException) error).code()).isEqualTo("STATE_CONFLICT");
                    });
            assertThat(transfers.find(transferId).status()).isEqualTo("FAILED");
            assertThat(challenge(challengeId).consumedAt()).isNull();
            assertThat(balance(fixture.accountId())).isEqualTo("10000000");
            assertThat(balance(destination)).isEqualTo("0");
            verify(sender).send(any());
        } finally {
            dispatch.release.countDown();
            executor.shutdownNow();
        }
    }

    private CreateTransferService.CreateResult createLargeTransfer(Fixture fixture, UUID destination, String amount,
                                                                   String memo) {
        return createTransfer.create(fixture.actor(), new CreateTransferService.CreateCommand(fixture.accountId(),
                destination, amount, "VND", "000001", memo), UUID.randomUUID().toString());
    }

    private UUID onlyTransferId() {
        return jdbc.queryForObject("SELECT id FROM transfers", UUID.class);
    }

    private static String wrongOtp(String correct) {
        return "000000".equals(correct) ? "000001" : "000000";
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

    private String accountStatus(UUID accountId) {
        return jdbc.queryForObject("SELECT status FROM accounts WHERE id=?", String.class, accountId);
    }

    private UUID destinationAccount() {
        return identities.createDefaultAccount(UUID.randomUUID(), numbers, now).accountId();
    }

    private AuthenticatedActor operator() {
        return new AuthenticatedActor(UUID.randomUUID(), Set.of("OPERATOR"));
    }

    private Fixture seedCustomer(String phone) {
        UUID user = UUID.randomUUID(), customer = UUID.randomUUID();
        identities.createUser(user, phone, phone + "@otp-race.example.test", "unused-test-hash", "CUSTOMER", now);
        identities.createCustomer(customer, user, "OTP race customer", null, now);
        identities.setPin(customer, pins.encode("000001"), now);
        UUID accountId = identities.createDefaultAccount(customer, numbers, now).accountId();
        jdbc.update("UPDATE accounts SET balance=10000000 WHERE id=?", accountId);
        return new Fixture(user, accountId);
    }

    private <T> List<Attempt<T>> runConcurrently(List<Callable<Attempt<T>>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Attempt<T>>> futures = new ArrayList<>();
            for (Callable<Attempt<T>> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start latch timed out");
                    }
                    return task.call();
                }));
            }
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Attempt<T>> outcomes = new ArrayList<>();
            for (Future<Attempt<T>> future : futures) {
                try {
                    outcomes.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (Exception error) {
                    fail("Concurrent operation did not finish within " + TIMEOUT_SECONDS + "s: " + error);
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private <T> Attempt<T> attempt(Callable<T> call) {
        try {
            return new Attempt<>(call.call(), null);
        } catch (ApiException error) {
            return new Attempt<>(null, error);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private final class ControlledDispatch implements org.mockito.stubbing.Answer<Void> {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<String> otp = new AtomicReference<>();

        @Override
        public Void answer(org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
            OtpMessage message = invocation.getArgument(0);
            otp.set(message.code());
            started.countDown();
            if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("dispatch release timed out");
            }
            throw new IllegalStateException("sms gateway down");
        }
    }

    private record Attempt<T>(T value, ApiException error) {}

    private record Fixture(UUID userId, UUID accountId) {
        AuthenticatedActor actor() {
            return new AuthenticatedActor(userId, Set.of("CUSTOMER"));
        }
    }

    private record ChallengeRow(int attempts, int maxAttempts, String purpose, Timestamp consumedAt,
                                Timestamp invalidatedAt, Timestamp expiresAt) {}
}
