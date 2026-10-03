package com.bank.simulator.transfer.web;

import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.transfer.application.TransferQueryService;
import com.bank.simulator.transfer.application.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TransferControllerMvcTest {
    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsImmediateTransferWithContractStatusHeaderAndBody() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        var transfers = mock(TransferService.class);
        var queries = mock(TransferQueryService.class);
        authenticate(actorId);
        when(transfers.create(any(), any(), eq("abcdefghijklmnop"))).thenReturn(
                new TransferService.CreateResult(false, transferId, "COMPLETED", null, null, 0, null, false,
                        null, null, null, null));
        when(queries.get(any(), eq(transferId))).thenReturn(view(transferId, "COMPLETED"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TransferController(transfers, queries)).build();

        mvc.perform(post("/transfers").header("Idempotency-Key", "abcdefghijklmnop")
                        .contentType("application/json")
                        .content("{\"sourceAccountId\":\"" + UUID.randomUUID() + "\",\"destinationAccountId\":\"" + UUID.randomUUID() + "\",\"amount\":\"2000\",\"currency\":\"VND\",\"pin\":\"001234\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void createsOtpChallengeWithChallengeShape() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        authenticate(actorId);
        var transfers = mock(TransferService.class);
        var queries = mock(TransferQueryService.class);
        when(transfers.create(any(), any(), eq("abcdefghijklmnop"))).thenReturn(
                new TransferService.CreateResult(true, transferId, "AWAITING_OTP", "OTP sent", Instant.parse("2026-01-01T00:02:00Z"), 120, null, false,
                        UUID.randomUUID(), UUID.randomUUID(), null, null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TransferController(transfers, queries)).build();

        mvc.perform(post("/transfers").header("Idempotency-Key", "abcdefghijklmnop")
                        .contentType("application/json")
                        .content("{\"sourceAccountId\":\"" + UUID.randomUUID() + "\",\"destinationAccountId\":\"" + UUID.randomUUID() + "\",\"amount\":\"6000000\",\"currency\":\"VND\",\"pin\":\"001234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.status").value("AWAITING_OTP"))
                .andExpect(jsonPath("$.expiresInSeconds").value(120));
    }

    @Test
    void listsTransfersWithQueryFiltersAndCursor() throws Exception {
        UUID actorId = UUID.randomUUID();
        authenticate(actorId);
        var transfers = mock(TransferService.class);
        var queries = mock(TransferQueryService.class);
        when(queries.list(any(), any())).thenReturn(new TransferQueryService.HistoryPage(List.of(), null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TransferController(transfers, queries)).build();

        mvc.perform(get("/transfers?limit=10&status=COMPLETED&cursor=cursor&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        verify(queries).list(any(), eq(new TransferQueryService.HistoryQuery(10, "cursor", "COMPLETED",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"))));
    }

    private void authenticate(UUID actorId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedActor(actorId, Set.of("CUSTOMER")), null, List.of()));
    }

    private TransferQueryService.TransferView view(UUID id, String status) {
        return new TransferQueryService.TransferView(id, status, null, UUID.randomUUID(), UUID.randomUUID(),
                "2000", "VND", null, Instant.EPOCH, null, Instant.EPOCH, "OUTGOING", "••••9012", "Recipient");
    }
}
