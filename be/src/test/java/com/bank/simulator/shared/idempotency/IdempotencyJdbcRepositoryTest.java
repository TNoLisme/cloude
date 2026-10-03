package com.bank.simulator.shared.idempotency;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotencyJdbcRepositoryTest {
    @Test
    void createsClaimWithInsertReturningId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID id = UUID.randomUUID();
        when(jdbc.query(anyString(), ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<UUID>>any(),
                any(Object[].class))).thenReturn(java.util.List.of(id));
        var repository = new IdempotencyJdbcRepository(jdbc);

        var claim = repository.createOrFind(UUID.randomUUID(), "transfer", "abcdefghijklmnop", "hash",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));

        assertThat(claim.created()).isTrue();
        assertThat(claim.record().id()).isEqualTo(id);
    }
}
