package com.bank.simulator.identity.infrastructure.security;

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

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = BankingSimulatorApplication.class)
@Testcontainers
class RefreshSessionPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("refresh_test")
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

    @Autowired RefreshSessionService service;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void rotationRevokesOriginalAndStoresOnlyHash() {
        UUID userId = insertUser();
        RefreshSessionService.IssuedRefreshSession first = service.issue(userId);
        RefreshSessionService.IssuedRefreshSession second = service.rotate(first.rawToken());

        assertThat(second.rawToken()).isNotEqualTo(first.rawToken());
        assertThat(jdbcTemplate.queryForObject("SELECT refresh_token_hash FROM refresh_sessions WHERE id = ?",
                String.class, first.sessionId())).isNotEqualTo(first.rawToken());
        java.sql.Timestamp revokedAt = jdbcTemplate.queryForObject("SELECT revoked_at FROM refresh_sessions WHERE id = ?",
                (rs, row) -> rs.getTimestamp(1), first.sessionId());
        assertThat(revokedAt).isNotNull();
    }

    @Test
    void concurrentRotationAllowsOneWinner() throws Exception {
        UUID userId = insertUser();
        RefreshSessionService.IssuedRefreshSession first = service.issue(userId);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        service.rotate(first.rawToken());
                        success.incrementAndGet();
                    } catch (Exception ignored) {
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(success.get()).isEqualTo(1);
    }

    private UUID insertUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users(id, phone, email_normalized, password_hash) VALUES (?, ?, ?, ?)",
                userId, "091234" + String.format("%04d", Math.abs(userId.hashCode()) % 10000),
                userId + "@example.test", "unused-hash");
        return userId;
    }
}
