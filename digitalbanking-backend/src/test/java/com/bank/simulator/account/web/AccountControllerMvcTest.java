package com.bank.simulator.account.web;

import com.bank.simulator.account.application.AccountOperatorService;
import com.bank.simulator.account.application.AccountQueryService;
import com.bank.simulator.account.application.AccountStatusService;
import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountControllerMvcTest {
    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void listsOwnedAccounts() throws Exception {
        var actor = authenticate("CUSTOMER");
        var queries = mock(AccountQueryService.class);
        when(queries.myAccounts(actor)).thenReturn(List.of(account(UUID.randomUUID(), UUID.randomUUID(), "1000", "ACTIVE")));
        var mvc = MockMvcBuilders.standaloneSetup(new AccountController(queries, mock(AccountOperatorService.class), mock(AccountStatusService.class))).build();

        mvc.perform(get("/accounts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
    }

    @Test
    void bindsSeedReplayHeaderAndBody() throws Exception {
        var actor = authenticate("OPERATOR");
        UUID accountId = UUID.randomUUID(), seedId = UUID.randomUUID();
        var operator = mock(AccountOperatorService.class);
        when(operator.seed(eq(actor), eq(accountId), any(), eq("abcdefghijklmnop")))
                .thenReturn(new AccountOperatorService.SeedBalanceResult(seedId, accountId, "2000", "VND", "12000", Instant.EPOCH, true));
        var mvc = MockMvcBuilders.standaloneSetup(new AccountController(mock(AccountQueryService.class), operator, mock(AccountStatusService.class))).build();

        mvc.perform(post("/operator/accounts/{id}/seed-balance", accountId)
                        .header("Idempotency-Key", "abcdefghijklmnop").contentType("application/json")
                        .content("{\"amount\":\"2000\",\"currency\":\"VND\",\"reference\":\"demo\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.seedTransactionId").value(seedId.toString()));
    }

    @Test
    void bindsBlockAndUnblockReasons() throws Exception {
        UUID accountId = UUID.randomUUID();
        var actor = authenticate("OPERATOR");
        var statuses = mock(AccountStatusService.class);
        when(statuses.change(eq(actor), eq(accountId), anyString(), eq("safety")))
                .thenReturn(account(accountId, UUID.randomUUID(), "1000", "BLOCKED"));
        var mvc = MockMvcBuilders.standaloneSetup(new AccountController(mock(AccountQueryService.class), mock(AccountOperatorService.class), statuses)).build();

        mvc.perform(post("/operator/accounts/{id}/block", accountId).contentType("application/json").content("{\"reason\":\"safety\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BLOCKED"));
        verify(statuses).change(eq(actor), eq(accountId), eq("BLOCKED"), eq("safety"));
    }

    private AuthenticatedActor authenticate(String role) {
        var actor = new AuthenticatedActor(UUID.randomUUID(), Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        return actor;
    }

    private AccountJdbcRepository.AccountRow account(UUID id, UUID customerId, String balance, String status) {
        return new AccountJdbcRepository.AccountRow(id, customerId, "123456789012", "CHECKING", status, balance, "VND", Instant.EPOCH);
    }
}
