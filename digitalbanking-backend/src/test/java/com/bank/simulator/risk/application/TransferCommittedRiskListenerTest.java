package com.bank.simulator.risk.application;

import com.bank.simulator.transfer.application.TransferCommittedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransferCommittedRiskListenerTest {
    @Test
    void catchesRiskEvaluationFailureAfterCommit() {
        var service = mock(RiskEvaluationService.class);
        doThrow(new RuntimeException("db failure")).when(service).evaluate(any());
        var listener = new TransferCommittedRiskListener(service);

        listener.onTransferCommitted(new TransferCommittedEvent(UUID.randomUUID(), UUID.randomUUID(), "6000000",
                Instant.EPOCH, UUID.randomUUID()));

        verify(service).evaluate(any());
    }
}
