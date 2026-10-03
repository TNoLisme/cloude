package com.bank.simulator.transfer.web;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RecipientControllerMvcTest {
    @Test
    void unavailableRecipientUsesSameNotFoundCode() throws Exception {
        var accounts = mock(AccountJdbcRepository.class);
        var identities = mock(IdentityJdbcRepository.class);
        var controller = new RecipientController(accounts, identities);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new com.bank.simulator.shared.error.GlobalExceptionHandler()).build();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), Set.of("CUSTOMER"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, java.util.List.of()));
        when(accounts.findByAccountNumber("123456789012")).thenReturn(null);

        mvc.perform(post("/recipients/resolve").contentType("application/json")
                        .content("{\"accountNumber\":\"123456789012\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("RECIPIENT_NOT_AVAILABLE"));
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesActiveDifferentCustomerWithMaskedNumber() throws Exception {
        var accounts = mock(AccountJdbcRepository.class);
        var identities = mock(IdentityJdbcRepository.class);
        var controller = new RecipientController(accounts, identities);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        UUID actorId = UUID.randomUUID();
        UUID recipientUserId = UUID.randomUUID();
        UUID recipientCustomerId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(actorId, Set.of("CUSTOMER"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, java.util.List.of()));
        UUID accountId = UUID.randomUUID();
        when(accounts.findByAccountNumber("123456789012")).thenReturn(new AccountJdbcRepository.AccountRow(
                accountId, recipientCustomerId, "123456789012", "CHECKING", "ACTIVE", "0", "VND", Instant.EPOCH));
        when(identities.findCustomerById(recipientCustomerId)).thenReturn(new IdentityJdbcRepository.CustomerRecord(
                recipientCustomerId, recipientUserId, "Recipient", null, Instant.EPOCH, "0912345678", "r@example.test", true));

        mvc.perform(post("/recipients/resolve").contentType("application/json")
                        .content("{\"accountNumber\":\"123456789012\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumberMasked").value("••••9012"))
                .andExpect(jsonPath("$.recipientDisplayName").value("Recipient"));
        SecurityContextHolder.clearContext();
    }
}
