package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.common.error.GlobalExceptionHandler;
import com.simulatedbank.banking.common.error.ProblemWriter;
import com.simulatedbank.banking.identity.adapter.in.AuthController;
import com.simulatedbank.banking.identity.application.LogoutService;
import com.simulatedbank.banking.identity.application.OtpChallengeService;
import com.simulatedbank.banking.identity.application.PasswordLoginService;
import com.simulatedbank.banking.identity.application.SessionViewService;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** Real Identity HTTP DTOs and shared advice; service failures are injected only at the application boundary.
 * Security/CSRF and database transactions are exercised by the existing real-server integration suites.
 */
@ExtendWith(OutputCaptureExtension.class)
class IdentityRestErrorMappingTest {
    private static final String SECRET = "SYNTHETIC_CREDENTIAL_NEVER_RETURN";
    private static final String REF = "11111111-2222-4333-8444-555555555555";
    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
    private static final String LOGIN = "{\"username\":\"consumer\",\"password\":\"" + SECRET + "\"}";
    private static final String OTP = "{\"challengeId\":\"" + REF + "\",\"otp\":\"812345\"}";
    private final PasswordLoginService login = mock(PasswordLoginService.class);
    private final OtpChallengeService challenges = mock(OtpChallengeService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        var controller = new AuthController(login, mock(LogoutService.class), mock(SessionViewService.class),
                mock(AppProperties.class), json, challenges);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new ProblemWriter(Clock.fixed(NOW, ZoneOffset.UTC), json)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).setValidator(validator)
                .addFilter((request, response, chain) -> {
                    request.setAttribute("correlationId", "identity-rest-errors");
                    chain.doFilter(request, response);
                }).build();
    }

    @ParameterizedTest
    @CsvSource({"AUTH_OTP_INVALID,401,true", "AUTH_CHALLENGE_NOT_FOUND,404,false",
            "AUTH_OTP_EXPIRED,410,true", "AUTH_CHALLENGE_LOCKED,423,false",
            "INVALID_STATE_TRANSITION,409,false", "OTP_DISPATCH_FAILED,503,true",
            "SERVICE_UNAVAILABLE,503,true", "AUTH_FORBIDDEN,403,false",
            "AUDIT_WRITE_FAILED,500,true", "RATE_LIMITED,429,true"})
    void domainFailuresFromOtpUseTheSharedCatalog(String name, int http, boolean retryable,
            CapturedOutput output) throws Exception {
        when(challenges.verifyLoginChallenge(anyString(), any(), any()))
                .thenThrow(new BankingBusinessException(ErrorCode.valueOf(name)));
        var result = problem(mvc.perform(post("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON).content(OTP)), http, name, retryable);
        if (http == 429 || name.equals("SERVICE_UNAVAILABLE")) {
            result.andExpect(header().string("Retry-After", "2"));
        }
        assertSafe(output.getAll());
    }

    @Test
    void accountLockRetainsOnlyTheApprovedExtension() throws Exception {
        when(login.login(any(), any())).thenThrow(new BankingBusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED,
                ErrorCode.AUTH_ACCOUNT_LOCKED.detail(), Map.of("lockedUntil", NOW.plusSeconds(600)),
                new SQLException(SECRET, "08006")));
        problem(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN)),
                423, "AUTH_ACCOUNT_LOCKED", false)
                .andExpect(jsonPath("$.lockedUntil").value("2026-11-16T10:10:00Z"));
    }

    @Test
    void frameworkAuthenticationAndAuthorizationFailuresAreNotConvertedToBadRequest() throws Exception {
        when(login.login(any(), any())).thenThrow(new BadCredentialsException(SECRET));
        problem(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN)),
                401, "AUTH_SESSION_EXPIRED", true);
        // Re-stub with doThrow: when(login.login(...)) would re-execute the throwing stub first.
        doThrow(new AccessDeniedException(SECRET)).when(login).login(any(), any());
        problem(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN)),
                403, "AUTH_FORBIDDEN", false);
    }

    @Test
    void unexpectedFailureIsInternalAndDoesNotExposeCauseOrCredential(CapturedOutput output) throws Exception {
        when(login.login(any(), any())).thenThrow(new IllegalStateException(SECRET,
                new SQLException("SELECT secret FROM banking.app_user; " + SECRET, "XX000")));
        problem(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN)),
                500, "INTERNAL_ERROR", true);
        assertSafe(output.getAll());
    }

    @Test
    void invalidBodiesNeverReachBusinessServices() throws Exception {
        problem(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":null,\"password\":\"" + SECRET + "\"}")),
                400, "VALIDATION_ERROR", false).andExpect(jsonPath("$.errors[0].field").value("username"));
        problem(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + SECRET)), 400, "MALFORMED_REQUEST", false);
        problem(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"consumer\",\"password\":\"" + SECRET + "\",\"roles\":[\"ADMIN\"]}")),
                400, "MALFORMED_REQUEST", false);
        problem(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content(SECRET)),
                415, "UNSUPPORTED_MEDIA_TYPE", false);
        problem(mvc.perform(get("/api/v1/auth/login")), 405, "METHOD_NOT_ALLOWED", false)
                .andExpect(header().string("Allow", "POST"));
        verifyNoInteractions(login, challenges);
    }

    private ResultActions problem(ResultActions result, int http, String code, boolean retryable) throws Exception {
        result.andExpect(status().is(http))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
                .andExpect(header().string("X-Correlation-ID", "identity-rest-errors"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.status").value(http)).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.type").isString()).andExpect(jsonPath("$.title").isString())
                .andExpect(jsonPath("$.detail").isString()).andExpect(jsonPath("$.instance").isString())
                .andExpect(jsonPath("$.timestamp").value(NOW.toString()))
                .andExpect(jsonPath("$.correlationId").value("identity-rest-errors"))
                .andExpect(jsonPath("$.retryable").value(retryable))
                .andExpect(jsonPath("$.stackTrace").doesNotExist()).andExpect(jsonPath("$.exception").doesNotExist());
        if (http == 401) {
            result.andExpect(header().string("WWW-Authenticate", "Bearer realm=\"digital-banking\""));
        }
        assertSafe(result.andReturn().getResponse().getContentAsString());
        return result;
    }

    private void assertSafe(String text) {
        assertThat(text).doesNotContain(SECRET, "812345", "SELECT secret", "banking.app_user", "java.sql.SQLException");
    }
}
