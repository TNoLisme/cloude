package com.bank.simulator.risk.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RiskEvaluationServiceTest {
    @Test
    void flagsAmountAboveThresholdAndDoesNotFlagEqualThreshold() {
        var flags = mock(RiskFlagRepository.class);
        var counts = mock(TransferCompletedCountRepository.class);
        when(counts.countSince(any(), any(), any())).thenReturn(0);
        var service = new RiskEvaluationService(flags,
                new com.bank.simulator.shared.pagination.CursorCodec(new com.fasterxml.jackson.databind.ObjectMapper()), counts,
                new com.bank.simulator.risk.config.RiskProperties());
        var at = Instant.parse("2026-10-03T12:00:00Z");

        service.evaluate(new TransferRiskSnapshot(UUID.randomUUID(), UUID.randomUUID(), "5000000", at, UUID.randomUUID()));
        service.evaluate(new TransferRiskSnapshot(UUID.randomUUID(), UUID.randomUUID(), "5000001", at, UUID.randomUUID()));

        verify(flags, times(1)).insert(any(), eq("LARGE_TRANSFER"), eq("1"), anyString(), any());
    }

    @Test
    void flagsFrequencyOnlyWhenCountExceedsFive() {
        var flags = mock(RiskFlagRepository.class);
        var counts = mock(TransferCompletedCountRepository.class);
        when(counts.countSince(any(), any(), any())).thenReturn(6);
        var service = new RiskEvaluationService(flags,
                new com.bank.simulator.shared.pagination.CursorCodec(new com.fasterxml.jackson.databind.ObjectMapper()), counts,
                new com.bank.simulator.risk.config.RiskProperties());

        service.evaluate(new TransferRiskSnapshot(UUID.randomUUID(), UUID.randomUUID(), "2000",
                Instant.parse("2026-10-03T12:00:00Z"), UUID.randomUUID()));

        verify(flags).insert(any(), eq("HIGH_FREQUENCY"), eq("1"), anyString(), any());
    }
}
