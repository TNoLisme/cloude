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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = BankingSimulatorApplication.class)
@Testcontainers
class RecoveryTokenPostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("recovery_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.security.jwt-secret", () -> "01234567890123456789012345678901");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired RecoveryTokenRepository tokens;
    @Autowired RecoveryTokenCodec codec;
    @Autowired JdbcTemplate jdbc;

    @Test
    void migrationStoresOnlyHashAndTokenCanBeConsumedOnce() {
        UUID userId = insertUser();
        String raw = codec.generate();
        String hash = codec.hash(raw);
        Instant now = Instant.now();
        UUID tokenId = UUID.randomUUID();

        tokens.create(tokenId, userId, hash, now, now.plusSeconds(300));

        assertThat(tokens.findUserId(hash)).isEqualTo(userId);
        assertThat(tokens.lock(hash).expiresAt()).isAfter(now);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recovery_reset_tokens WHERE token_hash = ?", Integer.class, raw))
                .isZero();
        tokens.consume(tokenId, now.plusSeconds(1));
        assertThat(tokens.lock(hash).consumedAt()).isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tokens.consume(tokenId, now.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidationMarksAllActiveProofsForUser() {
        UUID userId = insertUser();
        Instant now = Instant.now();
        String first = codec.hash(codec.generate());
        String second = codec.hash(codec.generate());
        tokens.create(UUID.randomUUID(), userId, first, now, now.plusSeconds(300));
        tokens.create(UUID.randomUUID(), userId, second, now, now.plusSeconds(300));

        tokens.invalidateActiveForUser(userId, now.plusSeconds(1));

        assertThat(tokens.lock(first).invalidatedAt()).isNotNull();
        assertThat(tokens.lock(second).invalidatedAt()).isNotNull();
    }

    private UUID insertUser() {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, phone, email_normalized, password_hash) VALUES (?, ?, ?, ?)",
                userId, "091234" + String.format("%04d", Math.abs(userId.hashCode()) % 10000),
                userId + "@example.test", "unused-hash");
        return userId;
    }
}
