package com.bank.simulator.risk.web;

import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.risk.application.RiskEvaluationService;
import com.bank.simulator.risk.application.RiskFlagPage;
import com.bank.simulator.risk.application.RiskFlagQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RiskFlagControllerMvcTest {
    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void bindsFiltersAndReturnsRiskFlagPage() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        var actor = new AuthenticatedActor(actorId, Set.of("AUDITOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        var service = mock(RiskEvaluationService.class);
        when(service.list(any(), eq(actor))).thenReturn(new RiskFlagPage(List.of(), null));
        var mvc = MockMvcBuilders.standaloneSetup(new RiskFlagController(service)).build();

        mvc.perform(get("/operator/risk-flags?limit=5&ruleId=LARGE_TRANSFER&transferId=" + transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        verify(service).list(eq(new RiskFlagQuery(5, null, "LARGE_TRANSFER", transferId, null, null)), eq(actor));
    }
}
