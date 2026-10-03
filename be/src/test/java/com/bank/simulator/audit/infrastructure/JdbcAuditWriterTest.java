package com.bank.simulator.audit.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcAuditWriterTest {

    @Test
    void insertsNullableTargetAndSuppliedCorrelationIdWithoutSensitiveMetadata() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcAuditWriter writer = new JdbcAuditWriter(jdbc);
        UUID correlationId = UUID.randomUUID();

        writer.record(null, null, "PASSWORD_RECOVERY_INITIATED", "USER", null,
                "NO_MATCH", correlationId, "Password recovery initiated.");

        verify(jdbc).update(anyString(), any(UUID.class), isNull(), isNull(),
                eq("PASSWORD_RECOVERY_INITIATED"), eq("USER"), isNull(), eq("NO_MATCH"),
                eq(correlationId), eq("Password recovery initiated."));
    }
}
