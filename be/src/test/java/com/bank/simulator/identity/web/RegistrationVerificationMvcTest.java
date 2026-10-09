package com.bank.simulator.identity.web;

import com.bank.simulator.identity.application.*;
import com.bank.simulator.shared.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RegistrationVerificationMvcTest {
    private final OnboardingService onboarding = mock(OnboardingService.class);
    private final RegistrationVerificationService verification = mock(RegistrationVerificationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OnboardingController(onboarding, mock(RecoveryService.class), Clock.systemUTC(), verification))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    @Test void validOtpReturnsNoStoreProof() throws Exception {
        when(verification.verify("0912345678", "000001")).thenReturn(new RegistrationVerificationService.VerifyResult("opaque-proof", 300));
        mvc.perform(post("/auth/register/verify-otp").contentType(MediaType.APPLICATION_JSON).content("""
                {"phone":"0912345678","otp":"000001"}
                """)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.registrationToken").value("opaque-proof")).andExpect(jsonPath("$.expiresInSeconds").value(300));
        verifyNoInteractions(onboarding);
    }
    @Test void invalidOtpReturnsProblem() throws Exception {
        when(verification.verify("0912345678", "000001")).thenReturn(new RegistrationVerificationService.VerifyResult(null, 0));
        mvc.perform(post("/auth/register/verify-otp").contentType(MediaType.APPLICATION_JSON).content("""
                {"phone":"0912345678","otp":"000001"}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
    }
    @Test void badShapeNeverCallsService() throws Exception {
        mvc.perform(post("/auth/register/verify-otp").contentType(MediaType.APPLICATION_JSON).content("""
                {"phone":"bad","otp":"123"}
                """)).andExpect(status().isBadRequest()); verifyNoInteractions(verification);
    }
    @Test void registerRejectsNeitherOrBothProofs() throws Exception {
        for (String extra : new String[] { "", ",\"otp\":\"000001\",\"registrationToken\":\"proof\"" }) {
            mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"phone\":\"0912345678\",\"email\":\"person@example.test\",\"password\":\"long-password-123\",\"fullName\":\"Person\"" + extra + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(onboarding, verification);
    }
    @Test void tokenPathCannotFallBackToLegacyOtp() throws Exception {
        doThrow(new com.bank.simulator.shared.error.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "REGISTRATION_TOKEN_INVALID", "Invalid proof"))
                .when(verification).register("0912345678", "person@example.test", "long-password-123", "Person", null, "proof");
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"phone":"0912345678","email":"person@example.test","password":"long-password-123","fullName":"Person","registrationToken":"proof"}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REGISTRATION_TOKEN_INVALID")); verifyNoInteractions(onboarding);
    }
}
