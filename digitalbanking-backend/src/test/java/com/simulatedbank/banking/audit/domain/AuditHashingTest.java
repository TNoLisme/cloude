package com.simulatedbank.banking.audit.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditHashingTest {
    @Test
    void createsGenesisHashFromCanonicalPayload() {
        var result = AuditHashing.recordHash("{\"action\":\"TRANSFER_COMPLETED\"}", AuditHashing.GENESIS_HASH);
        assertThat(result).isEqualTo("46236c80913a1fe504a70458df6323640ccb4899cfb8f0d273397863bbb565cc");
    }

    @Test
    void canonicalizesAuditEventWithFixedUtcTimestampAndMoney() {
        var event = new AuditEvent("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER",
                "TRANSFER", "TXN20261103A1B2C3D4", Map.of("status", "PENDING"),
                Map.of("status", "COMPLETED"), new BigDecimal("150000.00"), "VND",
                "corr-1", "req-1", "SUCCESS", Map.of());

        var payload = AuditHashing.canonicalPayload(event, Instant.parse("2026-11-03T10:15:31.123456Z"));

        assertThat((String) payload).contains("\"amount\":\"150000.00\"")
                .contains("\"occurred_at\":\"2026-11-03T10:15:31.123456Z\"")
                .contains("\"hash_version\":1");
    }
}
