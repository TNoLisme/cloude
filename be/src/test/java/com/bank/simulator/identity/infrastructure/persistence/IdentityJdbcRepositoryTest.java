package com.bank.simulator.identity.infrastructure.persistence;

import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeService;
import com.bank.simulator.identity.infrastructure.security.JwtAccessTokenCodec;
import com.bank.simulator.identity.infrastructure.security.PasswordHashingService;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.identity.infrastructure.security.RefreshSessionRepository;
import com.bank.simulator.identity.infrastructure.security.RefreshSessionService;
import com.bank.simulator.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityJdbcRepositoryTest {

    @Test
    void retriesAccountNumberCollisionThenCreatesDefaultCheckingAccount() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        IdentityJdbcRepository repository = new IdentityJdbcRepository(jdbc);
        AccountNumberGenerator numbers = mock(AccountNumberGenerator.class);
        when(numbers.generate()).thenReturn("123456789012", "123456789013");
        when(jdbc.update(eq("INSERT INTO accounts (id, account_number, customer_id, account_type, balance, currency, status, is_default, opened_at, updated_at) VALUES (?, ?, ?, 'CHECKING', 0, 'VND', 'ACTIVE', TRUE, ?, ?)"),
                any(), eq("123456789012"), any(), any(), any())).thenThrow(new DuplicateKeyException("collision"));
        when(jdbc.update(eq("INSERT INTO accounts (id, account_number, customer_id, account_type, balance, currency, status, is_default, opened_at, updated_at) VALUES (?, ?, ?, 'CHECKING', 0, 'VND', 'ACTIVE', TRUE, ?, ?)"),
                any(), eq("123456789013"), any(), any(), any())).thenReturn(1);

        var result = repository.createDefaultAccount(UUID.randomUUID(), numbers, Instant.parse("2026-10-02T00:00:00Z"));

        assertThat(result.accountNumber()).isEqualTo("123456789013");
        assertThat(result.status()).isEqualTo("ACTIVE");
        verify(numbers, times(2)).generate();
    }
}
