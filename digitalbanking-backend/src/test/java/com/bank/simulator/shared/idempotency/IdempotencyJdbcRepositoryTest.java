package com.bank.simulator.shared.idempotency;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotencyJdbcRepositoryTest {
    @Test
    void createsClaimWithInsertReturningId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID id = UUID.randomUUID();
        RowMapper<UUID> mapper = (rs, row) -> id;
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UUID>>any(), any(Object[].class)))
                .thenReturn(List.of(id));
        var repository = new IdempotencyJdbcRepository(jdbc);

        var claim = repository.createOrFind(UUID.randomUUID(), "transfer", "abcdefghijklmnop", "hash",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));

        assertThat(claim.created()).isTrue();
        assertThat(claim.record().id()).isEqualTo(id);
        verify(jdbc).update(contains("DELETE FROM idempotency_records"), any(), any(), any(), any());
    }
}
