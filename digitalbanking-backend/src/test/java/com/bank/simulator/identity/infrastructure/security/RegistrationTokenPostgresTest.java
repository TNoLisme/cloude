package com.bank.simulator.identity.infrastructure.security;

import com.bank.simulator.BankingSimulatorApplication;
import com.bank.simulator.identity.application.RegistrationVerificationService;
import com.bank.simulator.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = BankingSimulatorApplication.class)
@Testcontainers
class RegistrationTokenPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("registration_test").withUsername("test").withPassword("test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", postgres::getUsername); r.add("spring.datasource.password", postgres::getPassword);
        r.add("app.security.jwt-secret", () -> "01234567890123456789012345678901");
    }
    @Autowired RegistrationTokenRepository tokens;
    @Autowired RecoveryTokenCodec codec;
    @Autowired RegistrationVerificationService verification;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Test void oneTimeConsumptionIsPhoneBoundAndStoresOnlyHash() {
        String raw = codec.generate(); String hash = codec.hash(raw); Instant now = Instant.now();
        tokens.create(UUID.randomUUID(), "0912345678", hash, now, now.plusSeconds(300));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM registration_verification_tokens WHERE token_hash = ?", Integer.class, raw)).isZero();
        assertThat(tokens.consume("0912345679", hash, now.plusSeconds(1))).isFalse();
        assertThat(tokens.consume("0912345678", hash, now.plusSeconds(1))).isTrue();
        assertThat(tokens.consume("0912345678", hash, now.plusSeconds(2))).isFalse();
    }
    @Test void invalidatedAndExpiredProofsAreRejected() {
        Instant now = Instant.now(); String expired = codec.hash(codec.generate()); String retired = codec.hash(codec.generate());
        tokens.create(UUID.randomUUID(), "0912345680", expired, now.minusSeconds(301), now.minusSeconds(1));
        tokens.create(UUID.randomUUID(), "0912345680", retired, now, now.plusSeconds(300));
        tokens.invalidate("0912345680", now);
        assertThat(tokens.consume("0912345680", expired, now)).isFalse(); assertThat(tokens.consume("0912345680", retired, now)).isFalse();
    }
    @Test void failedAccountTransactionRollsBackProofConsumption() {
        Instant now = Instant.now(); String hash = codec.hash(codec.generate()); String phone = "0912345681";
        tokens.create(UUID.randomUUID(), phone, hash, now, now.plusSeconds(300));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            tokens.lockPhone(phone); assertThat(tokens.consume(phone, hash, now)).isTrue(); throw new IllegalStateException("Simulated account failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tokens.consume(phone, hash, now.plusSeconds(1))).isTrue();
    }
    @Test void concurrentRegistrationWithOneProofCreatesExactlyOneCustomerAndAccount() throws Exception {
        String raw = codec.generate(); String phone = "0912345682"; Instant now = Instant.now();
        tokens.create(UUID.randomUUID(), phone, codec.hash(raw), now, now.plusSeconds(300));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> register = () -> {
                start.await();
                try {
                    verification.register(phone, "concurrent-registration@example.test", "Test-Registration-Password", "Concurrent QA", null, raw);
                    return true;
                } catch (ApiException rejected) {
                    assertThat(rejected.code()).isEqualTo("REGISTRATION_TOKEN_INVALID");
                    return false;
                }
            };
            var first = executor.submit(register); var second = executor.submit(register); start.countDown();
            assertThat(java.util.List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE phone=?", Integer.class, phone)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts a JOIN customers c ON c.id=a.customer_id JOIN users u ON u.id=c.user_id WHERE u.phone=?", Integer.class, phone)).isEqualTo(1);
    }
}
