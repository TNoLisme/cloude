package com.bank.simulator.identity.web;

import com.bank.simulator.identity.application.OnboardingService;
import com.bank.simulator.identity.application.RecoveryService;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OnboardingControllerMvcTest {

    private final OnboardingService service = mock(OnboardingService.class);
    private final RecoveryService recovery = mock(RecoveryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OnboardingController(service, recovery,
            Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC)))
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                    org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                            .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
            .setControllerAdvice(new com.bank.simulator.shared.error.GlobalExceptionHandler())
            .build();

    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsMalformedRegistrationWithProblem() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"bad","email":"not-email","password":"short","fullName":"","otp":"x"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    void operatorCreateRequiresOperatorOrAdminRole() throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        new com.bank.simulator.identity.domain.AuthenticatedActor(UUID.randomUUID(),
                                java.util.Set.of("AUDITOR")), null, java.util.List.of()));
        doThrow(new com.bank.simulator.shared.error.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                "FORBIDDEN", "Request is forbidden.")).when(service).sendOperatorOtp(any(), eq("0912345678"));

        mvc.perform(post("/operator/customers/send-otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"0912345678\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void operatorLookupRejectsBothFilters() throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        new com.bank.simulator.identity.domain.AuthenticatedActor(UUID.randomUUID(),
                                java.util.Set.of("OPERATOR")), null, java.util.List.of()));
        doThrow(new com.bank.simulator.shared.error.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR", "Exactly one lookup filter is required.")).when(service)
                .lookup(any(), eq("0912345678"), eq("person@example.test"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/operator/customers")
                        .param("phone", "0912345678").param("email", "person@example.test"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void pinConfirmationMismatchReturnsValidationProblem() throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        new com.bank.simulator.identity.domain.AuthenticatedActor(UUID.randomUUID(),
                                java.util.Set.of("CUSTOMER")), null, java.util.List.of()));

        mvc.perform(post("/customers/me/pin/setup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"001234\",\"confirmPin\":\"009999\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(service, never()).setupPin(any(), anyString());
    }

    @Test
    void registrationOtpRequiresPhoneAndPurpose() throws Exception {
        mvc.perform(post("/auth/register/send-otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"0912345678\",\"purpose\":\"INVALID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    void operatorLookupPreservesCustomerNotFoundForUnmatchedEmail() throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        new com.bank.simulator.identity.domain.AuthenticatedActor(UUID.randomUUID(),
                                java.util.Set.of("OPERATOR")), null, java.util.List.of()));
        doThrow(new com.bank.simulator.shared.error.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                "CUSTOMER_NOT_FOUND", "Customer is not available.")).when(service)
                .lookup(any(), isNull(), eq("invalid-email"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/operator/customers")
                        .param("email", "invalid-email"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    void operatorLookupReturnsMaskedProjectionForEveryAccount() throws Exception {
        var actor = new com.bank.simulator.identity.domain.AuthenticatedActor(UUID.randomUUID(), java.util.Set.of("OPERATOR"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor, null, java.util.List.of()));
        UUID customerId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-02T00:00:00Z");
        var first = new IdentityJdbcRepository.AccountRecord(UUID.randomUUID(), "123456789012",
                "CHECKING", "ACTIVE", "15000000", "VND", createdAt);
        var second = new IdentityJdbcRepository.AccountRecord(UUID.randomUUID(), "987654321098",
                "CHECKING", "BLOCKED", "0", "VND", createdAt.plusSeconds(60));
        when(service.lookup(actor, "0912345678", null)).thenReturn(new OnboardingService.OperatorCustomerView(
                customerId, "Person", "0912345678", "person@example.test", true, createdAt, java.util.List.of(first, second)));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/operator/customers")
                        .param("phone", "0912345678"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.fullName").value("Person"))
                .andExpect(jsonPath("$.phone").value("0912345678"))
                .andExpect(jsonPath("$.email").value("person@example.test"))
                .andExpect(jsonPath("$.isPinSet").value(true))
                .andExpect(jsonPath("$.createdAt").value(createdAt.toString()))
                .andExpect(jsonPath("$.accounts.length()").value(2))
                .andExpect(jsonPath("$.accounts[0].accountId").value(first.accountId().toString()))
                .andExpect(jsonPath("$.accounts[0].accountNumberMasked").value("••••9012"))
                .andExpect(jsonPath("$.accounts[0].accountType").value("CHECKING"))
                .andExpect(jsonPath("$.accounts[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.accounts[0].balance").value("15000000"))
                .andExpect(jsonPath("$.accounts[0].currency").value("VND"))
                .andExpect(jsonPath("$.accounts[0].openedAt").value(createdAt.toString()))
                .andExpect(jsonPath("$.accounts[1].accountId").value(second.accountId().toString()))
                .andExpect(jsonPath("$.accounts[1].accountNumberMasked").value("••••1098"))
                .andExpect(jsonPath("$.accounts[1].status").value("BLOCKED"))
                .andExpect(jsonPath("$.accounts[1].balance").value("0"))
                .andExpect(jsonPath("$.accounts[1].openedAt").value(second.openedAt().toString()))
                .andExpect(jsonPath("$.accounts[*].accountNumber").doesNotExist())
                .andExpect(content().string(not(containsString(first.accountNumber()))))
                .andExpect(content().string(not(containsString(second.accountNumber()))));
        verify(service).lookup(actor, "0912345678", null);
    }

    @Test
    void operatorLookupByEmailKeepsEmptyAccountList() throws Exception {
        var actor = new com.bank.simulator.identity.domain.AuthenticatedActor(UUID.randomUUID(), java.util.Set.of("ADMIN"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor, null, java.util.List.of()));
        when(service.lookup(actor, null, "person@example.test")).thenReturn(new OnboardingService.OperatorCustomerView(
                UUID.randomUUID(), "Person", "0912345678", "person@example.test", false,
                Instant.parse("2026-10-02T00:00:00Z"), java.util.List.of()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/operator/customers")
                        .param("email", "person@example.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPinSet").value(false))
                .andExpect(jsonPath("$.accounts").isEmpty());
        verify(service).lookup(actor, null, "person@example.test");
    }

    @Test
    void operatorLookupPreservesServiceAuthorizationError() throws Exception {
        var actor = new com.bank.simulator.identity.domain.AuthenticatedActor(UUID.randomUUID(), java.util.Set.of("AUDITOR"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor, null, java.util.List.of()));
        doThrow(new com.bank.simulator.shared.error.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                "FORBIDDEN", "Request is forbidden.")).when(service).lookup(actor, "0912345678", null);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/operator/customers")
                        .param("phone", "0912345678"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void registrationReturnsCreatedAccountProjection() throws Exception {
        UUID customerId = UUID.randomUUID();
        var account = new IdentityJdbcRepository.AccountRecord(UUID.randomUUID(), "123456789012",
                "CHECKING", "ACTIVE", "0", "VND", Instant.parse("2026-10-02T00:00:00Z"));
        when(service.register("0912345678", "person@example.test", "password-123456",
                "Person", "District 1", "012345")).thenReturn(new OnboardingService.RegistrationResult(
                customerId, "0912345678", "person@example.test", "Person", account,
                Instant.parse("2026-10-02T00:00:00Z")));

        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"phone":"0912345678","email":"person@example.test","password":"password-123456",
                         "fullName":"Person","address":"District 1","otp":"012345"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.account.accountNumberMasked").value("••••9012"))
                .andExpect(jsonPath("$.account.balance").value("0"));
    }

    @Test
    void recoveryInitiateReturnsGenericResponseWithoutRevealingMatch() throws Exception {
        when(recovery.initiate("missing@example.test", "EMAIL"))
                .thenReturn(new RecoveryService.InitiateResult("missing@example.test", "EMAIL", 120,
                        "If the information is registered, an OTP has been sent to the selected channel."));

        mvc.perform(post("/auth/recover/initiate").contentType(MediaType.APPLICATION_JSON).content("""
                        {"identifier":"missing@example.test","channel":"EMAIL"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("If the information is registered")));
    }

    @Test
    void recoveryVerifyUnknownIdentifierReturnsOtpInvalidProblem() throws Exception {
        when(recovery.verify("missing@example.test", "EMAIL", "012345"))
                .thenReturn(RecoveryService.VerifyResult.invalid());

        mvc.perform(post("/auth/recover/verify").contentType(MediaType.APPLICATION_JSON).content("""
                        {"identifier":"missing@example.test","channel":"EMAIL","otp":"012345"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("OTP_INVALID"));
    }

    @Test
    void recoveryVerifyReturnsResetTokenOnlyAfterValidOtp() throws Exception {
        when(recovery.verify("0912345678", "SMS", "012345"))
                .thenReturn(new RecoveryService.VerifyResult("opaque-reset-token", 300));

        mvc.perform(post("/auth/recover/verify").contentType(MediaType.APPLICATION_JSON).content("""
                        {"identifier":"0912345678","channel":"SMS","otp":"012345"}
                        """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.resetToken").value("opaque-reset-token"))
                .andExpect(jsonPath("$.expiresInSeconds").value(300));
    }

    @Test
    void recoveryConfirmRejectsOldOtpAndPasswordPayload() throws Exception {
        doThrow(new com.bank.simulator.shared.error.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "RECOVERY_TOKEN_INVALID", "Recovery token is invalid or expired."))
                .when(recovery).confirm(isNull(), eq("new-password-1234"));

        mvc.perform(post("/auth/recover/confirm").contentType(MediaType.APPLICATION_JSON).content("""
                        {"identifier":"0912345678","channel":"SMS","otp":"012345",
                         "newPassword":"new-password-1234"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECOVERY_TOKEN_INVALID"));
    }
}
