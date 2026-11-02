package com.bank.simulator.audit.web;

import com.bank.simulator.audit.application.AuditPage;
import com.bank.simulator.audit.application.AuditQuery;
import com.bank.simulator.audit.application.ListAuditEventsService;
import com.bank.simulator.identity.domain.AuthenticatedActor;
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

class AuditControllerMvcTest {
    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void bindsAuditFiltersAndReturnsOnlyPageShape() throws Exception {
        UUID actorId = UUID.randomUUID();
        var actor = new AuthenticatedActor(UUID.randomUUID(), Set.of("AUDITOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        var service = mock(ListAuditEventsService.class);
        when(service.list(any(), eq(actor))).thenReturn(new AuditPage(List.of(), null));
        var mvc = MockMvcBuilders.standaloneSetup(new AuditController(service)).build();

        mvc.perform(get("/audit-events?limit=5&eventType=TRANSFER_COMPLETED&actorId=" + actorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        verify(service).list(eq(new AuditQuery(5, null, "TRANSFER_COMPLETED", actorId, null, null)), eq(actor));
    }
}
