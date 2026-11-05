package com.bank.simulator.transfer.web;

import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.transfer.api.AccountModuleApi;
import com.bank.simulator.transfer.application.ResolveRecipientService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RecipientControllerMvcTest {
    private static final String ACCOUNT_NUMBER = "123456789012";

    private final AccountModuleApi accounts = mock(AccountModuleApi.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new RecipientController(new ResolveRecipientService(accounts)))
            .setValidator(validator())
            .setControllerAdvice(new com.bank.simulator.shared.error.GlobalExceptionHandler())
            .build();
    private UUID actorId;

    private static LocalValidatorFactoryBean validator() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }

    @BeforeEach
    void actor() {
        actorId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(actorId, Set.of("CUSTOMER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, java.util.List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesActiveDifferentCustomerWithMaskedNumber() throws Exception {
        UUID recipientUserId = UUID.randomUUID();
        UUID recipientCustomerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(accounts.findByAccountNumber(ACCOUNT_NUMBER)).thenReturn(new AccountModuleApi.AccountView(
                accountId, recipientCustomerId, recipientUserId, "••••9012", "Recipient", "VND", "ACTIVE"));

        mvc.perform(post("/recipients/resolve").contentType("application/json")
                        .content("{\"accountNumber\":\"" + ACCOUNT_NUMBER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.accountNumberMasked").value("••••9012"))
                .andExpect(jsonPath("$.recipientDisplayName").value("Recipient"))
                .andExpect(jsonPath("$.currency").value("VND"));
    }

    @Test
    void unavailableRecipientUsesSameNotFoundCode() throws Exception {
        when(accounts.findByAccountNumber(ACCOUNT_NUMBER)).thenReturn(null);

        mvc.perform(post("/recipients/resolve").contentType("application/json")
                        .content("{\"accountNumber\":\"" + ACCOUNT_NUMBER + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("RECIPIENT_NOT_AVAILABLE"));
    }

    @Test
    void ownAccountConcealedAsUnavailable() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(accounts.findByAccountNumber(ACCOUNT_NUMBER)).thenReturn(new AccountModuleApi.AccountView(
                accountId, customerId, actorId, "••••9012", "Self", "VND", "ACTIVE"));

        mvc.perform(post("/recipients/resolve").contentType("application/json")
                        .content("{\"accountNumber\":\"" + ACCOUNT_NUMBER + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECIPIENT_NOT_AVAILABLE"));
    }

    @Test
    void blockedAccountConcealedAsUnavailable() throws Exception {
        when(accounts.findByAccountNumber(ACCOUNT_NUMBER)).thenReturn(new AccountModuleApi.AccountView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "••••9012", "Blocked", "VND", "BLOCKED"));

        mvc.perform(post("/recipients/resolve").contentType("application/json")
                        .content("{\"accountNumber\":\"" + ACCOUNT_NUMBER + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECIPIENT_NOT_AVAILABLE"));
    }

    @Test
    void foreignCurrencyAccountConcealedAsUnavailable() throws Exception {
        when(accounts.findByAccountNumber(ACCOUNT_NUMBER)).thenReturn(new AccountModuleApi.AccountView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "••••9012", "Foreign", "USD", "ACTIVE"));

        mvc.perform(post("/recipients/resolve").contentType("application/json")
                        .content("{\"accountNumber\":\"" + ACCOUNT_NUMBER + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECIPIENT_NOT_AVAILABLE"));
    }

    @Test
    void accountNumberShorterThanEightDigitsRejected() throws Exception {
        mvc.perform(post("/recipients/resolve").contentType("application/json")
                        .content("{\"accountNumber\":\"1234567\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void accountNumberLongerThanThirtyFourDigitsRejected() throws Exception {
        mvc.perform(post("/recipients/resolve").contentType("application/json")
                        .content("{\"accountNumber\":\"12345678901234567890123456789012345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void nonNumericAccountNumberRejected() throws Exception {
        mvc.perform(post("/recipients/resolve").contentType("application/json")
                        .content("{\"accountNumber\":\"12345678AB\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void operatorRoleIsForbidden() throws Exception {
        SecurityContextHolder.clearContext();
        AuthenticatedActor actor = new AuthenticatedActor(actorId, Set.of("OPERATOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, java.util.List.of()));

        mvc.perform(post("/recipients/resolve").contentType("application/json")
                        .content("{\"accountNumber\":\"" + ACCOUNT_NUMBER + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
