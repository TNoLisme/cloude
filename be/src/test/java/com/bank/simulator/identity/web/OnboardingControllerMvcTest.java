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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OnboardingControllerMvcTest {

    private final OnboardingService service = mock(OnboardingService.class);
    private final RecoveryService recovery = mock(RecoveryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OnboardingController(service, recovery,
            Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC)))
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
    void operatorLookupRejectsInvalidEmailFilter() throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        new com.bank.simulator.identity.domain.AuthenticatedActor(UUID.randomUUID(),
                                java.util.Set.of("OPERATOR")), null, java.util.List.of()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/operator/customers")
                        .param("email", "invalid-email"))
                .andExpect(status().isOk());
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
