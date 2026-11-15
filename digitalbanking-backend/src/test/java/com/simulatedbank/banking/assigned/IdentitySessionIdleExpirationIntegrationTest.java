package com.simulatedbank.banking.assigned;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.identity.application.IdentityService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import({AssignedModulesIntegrationTest.TestPorts.class,
        IdentitySessionIdleExpirationIntegrationTest.MutableClockConfig.class})
class IdentitySessionIdleExpirationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("digital_banking").withUsername("postgres").withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("banking.modules.enabled", () -> true);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.schemas", () -> "banking");
        registry.add("spring.flyway.default-schema", () -> "banking");
    }

    @Autowired IdentityService identity;
    @Autowired AssignedModulesIntegrationTest.CapturingOtp otp;
    @Autowired MutableClock clock;
    @Autowired AppProperties properties;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sessionExpiresAtConfiguredIdleBoundaryUsingControllableTime() {
        assertThat(properties.security().session().idleTimeoutMinutes()).isEqualTo(15);
        long userId = identity.createUser("idle-expiration-owner", "P@ssw0rd!", "CUSTOMER", "CUSTOMER");
        UUID deviceRef = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        IdentityService.LoginResult login = identity.login(new IdentityService.LoginCommand(
                "idle-expiration-owner", "P@ssw0rd!", deviceRef, "Idle test", "idle-fingerprint"));
        IdentityService.SessionGrant session = identity.verifyLoginOtp(login.challenge().challengeId(),
                otp.code(login.challenge().challengeId()));

        assertThat(identity.authenticate(session.rawToken())).isPresent();
        assertThat(jdbc.queryForObject("SELECT idle_expires_at FROM banking.session WHERE session_id = ?",
                java.time.OffsetDateTime.class, session.sessionId()).toInstant())
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));

        clock.advance(Duration.ofMinutes(15));

        assertThat(identity.authenticate(session.rawToken())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM banking.session WHERE session_id = ?",
                String.class, session.sessionId())).isEqualTo("EXPIRED");
        assertThat(userId).isPositive();
    }

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-11-15T12:00:00Z"));
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant initial) {
            now = new AtomicReference<>(initial);
        }

        void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
