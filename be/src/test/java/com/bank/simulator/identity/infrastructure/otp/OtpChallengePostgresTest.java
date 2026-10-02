package com.bank.simulator.identity.infrastructure.otp;

import com.bank.simulator.BankingSimulatorApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = BankingSimulatorApplication.class)
@Testcontainers
class OtpChallengePostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("otp_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.security.jwt-secret", () -> "01234567890123456789012345678901");
        registry.add("spring.flyway.enabled", () -> false);
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OtpChallengeRepository repository;
    @Autowired OtpHashingService hashingService;

    @Test
    void challengePersistsAndOnlyOneConcurrentConsumeSucceeds() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users(id, phone, email_normalized, password_hash) VALUES (?, ?, ?, ?)",
                userId, "0912345678", "otp-test@example.test", "unused-hash");
        UUID challengeId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        String code = "000042";
        repository.create(challengeId, "0912345678", "SMS", "REGISTRATION",
                hashingService.encode(code), now, now.plusSeconds(120), 5);

        OtpChallengeService service = new OtpChallengeService(repository, hashingService,
                message -> { }, Clock.fixed(now, ZoneOffset.UTC));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger valid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        if (service.consume(challengeId, "0912345678", "SMS", "REGISTRATION", code)
                                == OtpChallengeService.ConsumeResult.VALID) valid.incrementAndGet();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(valid.get()).isEqualTo(1);
        Integer attempts = jdbcTemplate.queryForObject("SELECT attempts FROM otp_challenges WHERE id = ?", Integer.class, challengeId);
        Instant consumed = jdbcTemplate.queryForObject("SELECT consumed_at FROM otp_challenges WHERE id = ?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), challengeId);
        assertThat(attempts).isZero();
        assertThat(consumed).isNotNull();
    }
}
