package com.bank.simulator.risk.application;

import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.pagination.CursorCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RiskEvaluationServiceListTest {
    @Test
    void listsFlagsWithRoleAndCursor() {
        var flags = mock(RiskFlagRepository.class);
        var counts = mock(TransferCompletedCountRepository.class);
        var service = new RiskEvaluationService(flags, new CursorCodec(new ObjectMapper()), counts,
                new com.bank.simulator.risk.config.RiskProperties());
        var first = new RiskFlagView(UUID.randomUUID(), UUID.randomUUID(), "LARGE_TRANSFER", "1", "reason", Instant.EPOCH);
        var second = new RiskFlagView(UUID.randomUUID(), UUID.randomUUID(), "HIGH_FREQUENCY", "1", "reason", Instant.EPOCH.minusSeconds(1));
        when(flags.find(any(), isNull(), eq(2))).thenReturn(List.of(first, second));

        var page = service.list(new RiskFlagQuery(1, null, null, null, null, null),
                new AuthenticatedActor(UUID.randomUUID(), Set.of("OPERATOR")));

        assertThat(page.items()).containsExactly(first);
        assertThat(page.nextCursor()).isNotBlank();
    }

    @Test
    void deniesCustomerRiskQuery() {
        var service = new RiskEvaluationService(mock(RiskFlagRepository.class), new CursorCodec(new ObjectMapper()),
                mock(TransferCompletedCountRepository.class), new com.bank.simulator.risk.config.RiskProperties());

        assertThatThrownBy(() -> service.list(new RiskFlagQuery(20, null, null, null, null, null),
                new AuthenticatedActor(UUID.randomUUID(), Set.of("CUSTOMER"))))
                .isInstanceOf(com.bank.simulator.shared.error.ApiException.class);
    }
}
