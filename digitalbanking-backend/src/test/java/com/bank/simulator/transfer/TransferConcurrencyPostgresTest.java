package com.bank.simulator.transfer;

import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.transfer.application.CreateTransferService;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest
@Testcontainers
class TransferConcurrencyPostgresTest {
    private static final int TIMEOUT_SECONDS = 20;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transfer_concurrency_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("app.security.jwt-secret", () -> "01234567890123456789012345678901");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CreateTransferService service;
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
    void con01ConcurrentTransfersNeverOverdrawSource() throws Exception {
        var fixture = seedCustomer("0912345690", "1000000");
        UUID destinationA = destinationAccount(), destinationB = destinationAccount();
        var first = new CreateTransferService.CreateCommand(fixture.accountId(), destinationA, "600000",
                "VND", "000001", "CON-01 A");
        var second = new CreateTransferService.CreateCommand(fixture.accountId(), destinationB, "600000",
                "VND", "000001", "CON-01 B");

        List<Attempt<CreateTransferService.CreateResult>> outcomes = runConcurrently(List.of(
                () -> attempt(() -> service.create(fixture.actor(), first, UUID.randomUUID().toString())),
                () -> attempt(() -> service.create(fixture.actor(), second, UUID.randomUUID().toString()))));

        long completed = outcomes.stream().filter(outcome -> outcome.value() != null).count();
        long insufficient = outcomes.stream().filter(outcome -> outcome.error() != null
                && "INSUFFICIENT_FUNDS".equals(outcome.error().code())).count();
        assertThat(completed).isEqualTo(1);
        assertThat(insufficient).isEqualTo(1);
        assertThat(balance(fixture.accountId())).isEqualTo("400000");
        assertThat(Integer.parseInt(balance(destinationA)) + Integer.parseInt(balance(destinationB)))
                .isEqualTo(600_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE balance < 0", Integer.class)).isZero();
    }

    @Test
    void con02ConcurrentSameKeyCreatesExactlyOneTransferAndReplaysOthers() throws Exception {
        var fixture = seedCustomer("0912345691", "10000000");
        var destination = destinationAccount();
        var command = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "250000",
                "VND", "000001", "CON-02 same key");
        String key = UUID.randomUUID().toString();

        List<Callable<Attempt<CreateTransferService.CreateResult>>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> attempt(() -> service.create(fixture.actor(), command, key)));
        }
        List<Attempt<CreateTransferService.CreateResult>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        long created = outcomes.stream().filter(outcome -> !outcome.value().replayed()).count();
        long replayed = outcomes.stream().filter(outcome -> outcome.value().replayed()).count();
        assertThat(created).isEqualTo(1);
        assertThat(replayed).isEqualTo(5);
        assertThat(outcomes).extracting(outcome -> outcome.value().transferId()).containsOnly(
                outcomes.getFirst().value().transferId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class)).isEqualTo(1);
        assertThat(balance(fixture.accountId())).isEqualTo("9750000");
        assertThat(balance(destination)).isEqualTo("250000");
    }

    @Test
    void con02ConcurrentSameKeyDifferentPayloadRejectsLoserWithoutMutation() throws Exception {
        var fixture = seedCustomer("0912345692", "10000000");
        var destination = destinationAccount();
        String key = UUID.randomUUID().toString();
        var first = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "100000",
                "VND", "000001", "CON-02 hash A");
        var second = new CreateTransferService.CreateCommand(fixture.accountId(), destination, "200000",
                "VND", "000001", "CON-02 hash B");

        List<Attempt<CreateTransferService.CreateResult>> outcomes = runConcurrently(List.of(
                () -> attempt(() -> service.create(fixture.actor(), first, key)),
                () -> attempt(() -> service.create(fixture.actor(), second, key))));

        assertThat(outcomes.stream().filter(outcome -> outcome.value() != null).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.error() != null
                && "IDEMPOTENCY_KEY_REUSED".equals(outcome.error().code())
                && outcome.error().status() == HttpStatus.CONFLICT).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class)).isEqualTo(1);
        assertThat(balance(destination)).isIn("100000", "200000");
    }

    @Test
    void con03OppositeDirectionTransfersCompleteWithoutDeadlock() throws Exception {
        var first = seedCustomer("0912345693", "1000000");
        var second = seedCustomer("0912345694", "1000000");
        var firstToSecond = new CreateTransferService.CreateCommand(first.accountId(), second.accountId(), "100000",
                "VND", "000001", "CON-03 A to B");
        var secondToFirst = new CreateTransferService.CreateCommand(second.accountId(), first.accountId(), "100000",
                "VND", "000001", "CON-03 B to A");

        List<Attempt<CreateTransferService.CreateResult>> outcomes = runConcurrently(List.of(
                () -> attempt(() -> service.create(first.actor(), firstToSecond, UUID.randomUUID().toString())),
                () -> attempt(() -> service.create(second.actor(), secondToFirst, UUID.randomUUID().toString()))));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        assertThat(outcomes).extracting(outcome -> outcome.value().status()).containsOnly("COMPLETED");
        assertThat(balance(first.accountId())).isEqualTo("1000000");
        assertThat(balance(second.accountId())).isEqualTo("1000000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(2);
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
                    if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException("start latch timed out");
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
                    fail("Concurrent transfer did not finish within " + TIMEOUT_SECONDS + "s: " + error);
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

    private String balance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance::text FROM accounts WHERE id=?", String.class, accountId);
    }

    private UUID destinationAccount() {
        return identities.createDefaultAccount(UUID.randomUUID(), numbers, now).accountId();
    }

    private Fixture seedCustomer(String phone, String initialBalance) {
        UUID user = UUID.randomUUID(), customer = UUID.randomUUID();
        identities.createUser(user, phone, phone + "@concurrency.example.test", "unused-test-hash", "CUSTOMER", now);
        identities.createCustomer(customer, user, "Concurrency customer", null, now);
        identities.setPin(customer, pins.encode("000001"), now);
        UUID accountId = identities.createDefaultAccount(customer, numbers, now).accountId();
        jdbc.update("UPDATE accounts SET balance=? WHERE id=?", new java.math.BigDecimal(initialBalance), accountId);
        return new Fixture(user, accountId);
    }

    private record Attempt<T>(T value, ApiException error) {}

    private record Fixture(UUID userId, UUID accountId) {
        AuthenticatedActor actor() {
            return new AuthenticatedActor(userId, Set.of("CUSTOMER"));
        }
    }
}
