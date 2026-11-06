package com.bank.simulator.identity.infrastructure.security;

import com.bank.simulator.BankingSimulatorApplication;
import com.bank.simulator.identity.application.OnboardingService;
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
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired RefreshSessionService service;
    @Autowired OnboardingService onboarding;
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

    @Test
    void refreshBootstrapUsesCurrentRolesAndKeepsOldCookieWhenUserIsInactive() {
        UUID userId = insertUser();
        jdbcTemplate.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'OPERATOR')", userId);
        RefreshSessionService.IssuedRefreshSession original = service.issue(userId);
        jdbcTemplate.update("UPDATE users SET is_active = FALSE WHERE id = ?", userId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> onboarding.refreshSession(original.rawToken()))
                .isInstanceOf(IllegalArgumentException.class);
        java.sql.Timestamp revokedAt = jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM refresh_sessions WHERE id = ?",
                (rs, row) -> rs.getTimestamp(1), original.sessionId());
        assertThat(revokedAt).isNull();

        jdbcTemplate.update("UPDATE users SET is_active = TRUE WHERE id = ?", userId);
        var refreshed = onboarding.refreshSession(original.rawToken());

        assertThat(refreshed.user().userId()).isEqualTo(userId);
        assertThat(refreshed.user().customerId()).isEqualTo(userId);
        assertThat(refreshed.user().roles()).containsExactly("OPERATOR");
        assertThat(refreshed.user().isPinSet()).isFalse();
        assertThat(refreshed.user().phone()).isNotBlank();
    }

    @Test
    void reuseAfterGracePeriodRevokesTokenFamilyAndCommitsToPostgres() {
        UUID userId = insertUser();
        RefreshSessionService.IssuedRefreshSession s1 = service.issue(userId);
        RefreshSessionService.IssuedRefreshSession s2 = service.rotate(s1.rawToken());
        RefreshSessionService.IssuedRefreshSession s3 = service.rotate(s2.rawToken());

        // Simulate s1 rotated 20 seconds ago (> 10s grace period)
        jdbcTemplate.update("UPDATE refresh_sessions SET revoked_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(20)), s1.sessionId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> onboarding.refreshSession(s1.rawToken()))
                .isInstanceOf(RefreshSessionReuseException.class);

        // s2 was already revoked by rotation; s3 was active but must now be revoked by family revocation
        java.sql.Timestamp s3RevokedAt = jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM refresh_sessions WHERE id = ?",
                (rs, row) -> rs.getTimestamp(1), s3.sessionId());
        assertThat(s3RevokedAt).isNotNull();

        // Further attempt to rotate s3 must also fail
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.rotate(s3.rawToken()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reuseWithinGracePeriodRejectsWithoutRevokingNewToken() {
        UUID userId = insertUser();
        RefreshSessionService.IssuedRefreshSession s1 = service.issue(userId);
        RefreshSessionService.IssuedRefreshSession s2 = service.rotate(s1.rawToken());

        // Simulate s1 rotated 3 seconds ago (<= 10s grace period)
        jdbcTemplate.update("UPDATE refresh_sessions SET revoked_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3)), s1.sessionId());

        // Reusing s1 must be rejected, but NOT as reuse attack
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.rotate(s1.rawToken()))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(RefreshSessionReuseException.class);

        // s2 must STILL be active
        java.sql.Timestamp s2RevokedAt = jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM refresh_sessions WHERE id = ?",
                (rs, row) -> rs.getTimestamp(1), s2.sessionId());
        assertThat(s2RevokedAt).isNull();

        // s2 can still rotate successfully to s3
        RefreshSessionService.IssuedRefreshSession s3 = service.rotate(s2.rawToken());
        assertThat(s3).isNotNull();
    }

    @Test
    void gracePeriodBoundaryCheck() {
        UUID userId = insertUser();

        // 1. Exactly at boundary (10s) -> considered within grace period (not reuse attack)
        RefreshSessionService.IssuedRefreshSession a1 = service.issue(userId);
        RefreshSessionService.IssuedRefreshSession a2 = service.rotate(a1.rawToken());
        jdbcTemplate.update("UPDATE refresh_sessions SET revoked_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(9)), a1.sessionId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.rotate(a1.rawToken()))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(RefreshSessionReuseException.class);
        java.sql.Timestamp a2Revoked = jdbcTemplate.queryForObject("SELECT revoked_at FROM refresh_sessions WHERE id = ?",
                (rs, row) -> rs.getTimestamp(1), a2.sessionId());
        assertThat(a2Revoked).isNull();

        // 2. Beyond boundary (11s) -> reuse attack detected and family revoked
        RefreshSessionService.IssuedRefreshSession b1 = service.issue(userId);
        RefreshSessionService.IssuedRefreshSession b2 = service.rotate(b1.rawToken());
        jdbcTemplate.update("UPDATE refresh_sessions SET revoked_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(11)), b1.sessionId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.rotate(b1.rawToken()))
                .isInstanceOf(RefreshSessionReuseException.class);
        java.sql.Timestamp b2Revoked = jdbcTemplate.queryForObject("SELECT revoked_at FROM refresh_sessions WHERE id = ?",
                (rs, row) -> rs.getTimestamp(1), b2.sessionId());
        assertThat(b2Revoked).isNotNull();
    }

    @Test
    void reuseDoesNotAffectOtherDeviceSessionsOfSameUser() {
        UUID userId = insertUser();
        // Device A session chain
        RefreshSessionService.IssuedRefreshSession devA1 = service.issue(userId);
        RefreshSessionService.IssuedRefreshSession devA2 = service.rotate(devA1.rawToken());

        // Device B session chain
        RefreshSessionService.IssuedRefreshSession devB1 = service.issue(userId);

        // Simulate devA1 rotated 25s ago
        jdbcTemplate.update("UPDATE refresh_sessions SET revoked_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(25)), devA1.sessionId());

        // Reuse devA1
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.rotate(devA1.rawToken()))
                .isInstanceOf(RefreshSessionReuseException.class);

        // devA2 is revoked
        java.sql.Timestamp devA2Revoked = jdbcTemplate.queryForObject("SELECT revoked_at FROM refresh_sessions WHERE id = ?",
                (rs, row) -> rs.getTimestamp(1), devA2.sessionId());
        assertThat(devA2Revoked).isNotNull();

        // devB1 is STILL active and untouched
        java.sql.Timestamp devB1Revoked = jdbcTemplate.queryForObject("SELECT revoked_at FROM refresh_sessions WHERE id = ?",
                (rs, row) -> rs.getTimestamp(1), devB1.sessionId());
        assertThat(devB1Revoked).isNull();

        // devB1 can rotate normally
        RefreshSessionService.IssuedRefreshSession devB2 = service.rotate(devB1.rawToken());
        assertThat(devB2).isNotNull();
    }

    @Test
    void loggedOutTokenDoesNotTriggerFamilyRevocation() {
        UUID userId = insertUser();
        RefreshSessionService.IssuedRefreshSession s1 = service.issue(userId);
        service.revoke(s1.rawToken()); // logout

        // s1 has revoked_at != null and replaced_by_session_id == null
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.rotate(s1.rawToken()))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(RefreshSessionReuseException.class);
    }

    @Test
    void concurrentRefreshRequestsKeepWinnerSessionValid() throws Exception {
        UUID userId = insertUser();
        RefreshSessionService.IssuedRefreshSession first = service.issue(userId);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<RefreshSessionService.IssuedRefreshSession> winnerSession = new java.util.concurrent.atomic.AtomicReference<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        var rotated = service.rotate(first.rawToken());
                        winnerSession.set(rotated);
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
        assertThat(winnerSession.get()).isNotNull();

        // Winner session must NOT be revoked by the loser's concurrent attempt
        java.sql.Timestamp winnerRevokedAt = jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM refresh_sessions WHERE id = ?",
                (rs, row) -> rs.getTimestamp(1), winnerSession.get().sessionId());
        assertThat(winnerRevokedAt).isNull();
    }

    private UUID insertUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users(id, phone, email_normalized, password_hash) VALUES (?, ?, ?, ?)",
                userId, "091234" + String.format("%04d", Math.abs(userId.hashCode()) % 10000),
                userId + "@example.test", "unused-hash");
        return userId;
    }
}
