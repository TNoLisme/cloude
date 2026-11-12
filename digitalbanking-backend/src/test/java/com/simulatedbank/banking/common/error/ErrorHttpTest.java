package com.simulatedbank.banking.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = ErrorHttpTest.TestController.class)
@Import({GlobalExceptionHandler.class, ProblemWriter.class, ErrorHttpTest.HarnessConfig.class,
        ErrorHttpTest.TestController.class})
@ExtendWith(OutputCaptureExtension.class)
class ErrorHttpTest {

    // Synthetic only; none of these values may escape via an exception/validation body or error-layer log.
    static final String SENSITIVE = "SYNTHETIC_PASSWORD_123 otp=812345 email=private@example.test "
            + "account=123456789012 SQL=SELECT_secret schema=private constraint=uq_private provider=raw";

    @Autowired MockMvc mvc;

    @Test
    void successIsNotWrapped() throws Exception {
        mvc.perform(get("/fixture/success"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.meta").doesNotExist());
    }

    @Test
    void frameworkFailuresHaveTheContractFormat(CapturedOutput output) throws Exception {
        check(mvc.perform(post("/fixture/body").contentType(MediaType.APPLICATION_JSON)
                .content("{\"secret\":\"" + SENSITIVE + "\"}")), 400, "VALIDATION_ERROR", false)
                .andExpect(jsonPath("$.errors[0].field").value("secret"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        check(mvc.perform(post("/fixture/body").contentType(MediaType.APPLICATION_JSON)
                .content("{\"secret\":\"" + SENSITIVE)), 400, "MALFORMED_REQUEST", false);
        check(mvc.perform(post("/fixture/body").contentType(MediaType.TEXT_PLAIN).content(SENSITIVE)),
                415, "UNSUPPORTED_MEDIA_TYPE", false);
        check(mvc.perform(get("/fixture/body")), 405, "METHOD_NOT_ALLOWED", false)
                .andExpect(header().string("Allow", "POST"));
        check(mvc.perform(get("/fixture/missing")), 404, "NOT_FOUND", false);
        check(mvc.perform(get("/fixture/number").param("count", "invalid")), 400, "VALIDATION_ERROR", false);
        check(mvc.perform(get("/fixture/number")), 400, "VALIDATION_ERROR", false);
        check(mvc.perform(get("/fixture/number").param("count", "0")), 400, "VALIDATION_ERROR", false)
                .andExpect(jsonPath("$.errors[0].field").value("count"));
        check(mvc.perform(get("/fixture/return")), 500, "INTERNAL_ERROR", true)
                .andExpect(jsonPath("$.errors").doesNotExist());
        assertSafe(output.getAll());
    }

    @Test
    void mvcAuthenticationAuthorizationAndUnexpectedFailuresAreSafe(CapturedOutput output) throws Exception {
        check(mvc.perform(get("/fixture/authentication")), 401, "AUTH_SESSION_EXPIRED", true)
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"digital-banking\""));
        check(mvc.perform(get("/fixture/authorization")), 403, "AUTH_FORBIDDEN", false);
        check(mvc.perform(get("/fixture/unexpected")), 500, "INTERNAL_ERROR", true);
        assertSafe(output.getAll());
    }

    @Test
    void filterAndSecurityEntryPointsUseSharedWriter(CapturedOutput output) throws Exception {
        check(mvc.perform(get("/harness/filter")), 413, "REQUEST_TOO_LARGE", false);
        check(mvc.perform(get("/harness/private")), 401, "AUTH_SESSION_EXPIRED", true)
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"digital-banking\""));
        check(mvc.perform(get("/harness/admin").with(user("synthetic").roles("CUSTOMER"))),
                403, "AUTH_FORBIDDEN", false);
        assertSafe(output.getAll());
    }

    @ParameterizedTest
    @CsvSource({"55P03,SERVICE_UNAVAILABLE,503", "40P01,SERVICE_UNAVAILABLE,503",
            "40001,SERVICE_UNAVAILABLE,503", "57014,SERVICE_UNAVAILABLE,503",
            "08006,SERVICE_UNAVAILABLE,503", "08007,SERVICE_UNAVAILABLE,503",
            "23505,INTERNAL_ERROR,500", "23514,INTERNAL_ERROR,500", "XX000,INTERNAL_ERROR,500"})
    void databaseSqlStateMappingDoesNotInferBusinessRules(String sqlState, String code, int http,
            CapturedOutput output) throws Exception {
        var result = check(mvc.perform(get("/fixture/database").param("state", sqlState)), http, code, true);
        if (http == 503) { result.andExpect(header().string("Retry-After", "2")); }
        assertSafe(output.getAll());
    }

    @Test
    void ownerClassificationWinsOverSqlCause(CapturedOutput output) throws Exception {
        check(mvc.perform(get("/fixture/owner").param("code", "IDEMPOTENCY_IN_PROGRESS")),
                409, "IDEMPOTENCY_IN_PROGRESS", true).andExpect(header().string("Retry-After", "2"));
        check(mvc.perform(get("/fixture/owner").param("code", "IDEMPOTENCY_CONFLICT")),
                409, "IDEMPOTENCY_CONFLICT", false).andExpect(header().doesNotExist("Retry-After"));
        check(mvc.perform(get("/fixture/owner").param("code", "ROLE_CONFLICT")),
                409, "ROLE_CONFLICT", false);
        check(mvc.perform(get("/fixture/owner").param("code", "AUDIT_WRITE_FAILED")),
                500, "AUDIT_WRITE_FAILED", true);
        check(mvc.perform(get("/fixture/owner").param("code", "RATE_LIMITED")),
                429, "RATE_LIMITED", true).andExpect(header().string("Retry-After", "2"));
        check(mvc.perform(get("/fixture/db-resource")), 503, "SERVICE_UNAVAILABLE", true);
        check(mvc.perform(get("/fixture/db-no-state")), 500, "INTERNAL_ERROR", true);
        assertSafe(output.getAll());
    }

    private org.springframework.test.web.servlet.ResultActions check(
            org.springframework.test.web.servlet.ResultActions result, int http, String code, boolean retry)
            throws Exception {
        result.andExpect(status().is(http))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
                .andExpect(header().string("X-Correlation-ID", "harness-trace"))
                .andExpect(jsonPath("$.type").isString()).andExpect(jsonPath("$.title").isString())
                .andExpect(jsonPath("$.detail").isString()).andExpect(jsonPath("$.instance").isString())
                .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.status").value(http))
                .andExpect(jsonPath("$.correlationId").value("harness-trace"))
                .andExpect(jsonPath("$.timestamp").value("2026-11-12T07:00:00Z"))
                .andExpect(jsonPath("$.retryable").value(retry))
                .andExpect(jsonPath("$.requestId").doesNotExist());
        assertSafe(result.andReturn().getResponse().getContentAsString());
        return result;
    }

    private void assertSafe(String output) {
        assertThat(output).doesNotContain("SYNTHETIC_PASSWORD_123", "812345", "private@example.test",
                "123456789012", "SELECT_secret", "uq_private", "schema=private", "provider=raw");
        assertThat(output).doesNotContain("at com.simulatedbank.banking.common.error.ErrorHttpTest");
    }

    @RestController
    static class TestController {
        record Input(@NotBlank(message = SENSITIVE) @Pattern(regexp = "safe", message = SENSITIVE) String secret) {}
        @GetMapping("/fixture/success") Map<String, String> success() { return Map.of("status", "UP"); }
        @PostMapping(value = "/fixture/body", consumes = "application/json")
        Input body(@Valid @RequestBody Input input) { return input; }
        @GetMapping("/fixture/number") int number(@RequestParam @Min(value = 1, message = SENSITIVE) int count) { return count; }
        @GetMapping("/fixture/return") @NotBlank(message = SENSITIVE) String invalidReturn() { return ""; }
        @GetMapping("/fixture/authentication") void authentication() { throw new BadCredentialsException(SENSITIVE); }
        @GetMapping("/fixture/authorization") void authorization() { throw new AccessDeniedException(SENSITIVE); }
        @GetMapping("/fixture/unexpected") void unexpected() { throw new IllegalStateException(SENSITIVE); }
        @GetMapping("/fixture/database") void database(@RequestParam String state) {
            throw new DataIntegrityViolationException(SENSITIVE, new SQLException(SENSITIVE, state));
        }
        @GetMapping("/fixture/db-resource") void resourceFailure() { throw new DataAccessResourceFailureException(SENSITIVE); }
        @GetMapping("/fixture/db-no-state") void noState() {
            throw new DataIntegrityViolationException(SENSITIVE, new SQLException(SENSITIVE));
        }
        @GetMapping("/fixture/owner") void classified(@RequestParam ErrorCode code) {
            // Simulates the owner after rollback/retry; not a DB transaction or business implementation.
            var failure = new BankingBusinessException(code, code.detail(), Map.of(),
                    new SQLException(SENSITIVE, code == ErrorCode.AUDIT_WRITE_FAILED ? "40P01" : "55P03"));
            throw new DataIntegrityViolationException(SENSITIVE, failure);
        }
        @GetMapping({"/harness/private", "/harness/admin"}) String protectedRoute() { return "ok"; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HarnessConfig {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-12T07:00:00Z"), ZoneOffset.UTC); }
        @Bean SecurityFilterChain harnessSecurity(HttpSecurity http, ProblemWriter writer) throws Exception {
            Filter filter = (request, response, chain) -> {
                var servletRequest = (jakarta.servlet.http.HttpServletRequest) request;
                servletRequest.setAttribute("correlationId", "harness-trace");
                if (servletRequest.getRequestURI().equals("/harness/filter")) {
                    writer.write((jakarta.servlet.http.HttpServletResponse) response, writer.create(
                            ErrorCode.REQUEST_TOO_LARGE, ErrorCode.REQUEST_TOO_LARGE.detail(), servletRequest,
                            List.of(), Map.of()));
                    return;
                }
                chain.doFilter(request, response);
            };
            return http.csrf(AbstractHttpConfigurer::disable)
                    .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
                    .authorizeHttpRequests(auth -> auth.requestMatchers("/harness/admin").hasRole("ADMIN")
                            .requestMatchers("/harness/private").authenticated().anyRequest().permitAll())
                    .exceptionHandling(handling -> handling
                            .authenticationEntryPoint((request, response, ex) -> writer.write(response,
                                    writer.create(ErrorCode.AUTH_SESSION_EXPIRED, ErrorCode.AUTH_SESSION_EXPIRED.detail(),
                                            request, List.of(), Map.of())))
                            .accessDeniedHandler((request, response, ex) -> writer.write(response,
                                    writer.create(ErrorCode.AUTH_FORBIDDEN, ErrorCode.AUTH_FORBIDDEN.detail(),
                                            request, List.of(), Map.of()))))
                    .build();
        }
    }
}
