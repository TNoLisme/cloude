package com.simulatedbank.banking.common.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simulatedbank.banking.common.config.RequestContextConfig;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.common.error.GlobalExceptionHandler;
import com.simulatedbank.banking.common.error.ProblemWriter;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = ContextHttpTest.Fixture.class)
@Import({ContextHttpTest.Fixture.class, ContextHttpTest.SecurityHarness.class,
        RequestContextConfig.class, ProblemWriter.class, GlobalExceptionHandler.class})
class ContextHttpTest {
    @Autowired MockMvc mvc;

    @Test
    void successValidationMvcAndSecurityFailuresShareTheOuterTrace() throws Exception {
        for (String route : List.of("/fixture/ok", "/fixture/throw", "/fixture/missing", "/fixture/private")) {
            var result = mvc.perform(get(route).header("X-Correlation-ID", "flow:shared")).andReturn();
            assertThat(result.getResponse().getHeader("X-Correlation-ID")).isEqualTo("flow:shared");
            assertThat(result.getResponse().getStatus()).isEqualTo(switch (route) {
                case "/fixture/ok" -> 200;
                case "/fixture/throw" -> 500;
                case "/fixture/missing" -> 404;
                default -> 401;
            });
            assertThat(result.getResponse().getContentAsString()).contains("flow:shared");
            RequestContextTest.assertClean();
        }
        var forbidden = mvc.perform(get("/fixture/private").with(user("synthetic").roles("USER"))
                .header("X-Correlation-ID", "flow:denied")).andExpect(status().isForbidden()).andReturn();
        assertThat(forbidden.getResponse().getContentAsString()).contains("AUTH_FORBIDDEN", "flow:denied");
        var invalid = mvc.perform(get("/fixture/private").header("X-Correlation-ID", ""))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(invalid.getResponse().getContentAsString()).contains("VALIDATION_ERROR");
        RequestContextTest.assertClean();
    }

    @Test
    void realMvcAsyncCompletionRetainsTraceAndCleansTheCallingThread() throws Exception {
        var started = mvc.perform(get("/fixture/async").header("X-Correlation-ID", "flow:async")).andReturn();
        assertThat(started.getRequest().isAsyncStarted()).isTrue();
        RequestContextTest.assertClean();
        var completed = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
        assertThat(completed.getResponse().getHeader("X-Correlation-ID")).isEqualTo("flow:async");
        assertThat(completed.getResponse().getContentAsString()).contains("flow:async");
        RequestContextTest.assertClean();
    }

    @Test
    void asyncFailureRetainsTraceAndCleansWorkerAndDispatchThread() throws Exception {
        var started = mvc.perform(get("/fixture/async-failure")
                .header("X-Correlation-ID", "flow:async-failure")).andReturn();
        RequestContextTest.assertClean();
        var completed = mvc.perform(asyncDispatch(started)).andExpect(status().isInternalServerError()).andReturn();
        assertThat(completed.getResponse().getHeader("X-Correlation-ID")).isEqualTo("flow:async-failure");
        assertThat(completed.getResponse().getContentAsString()).contains("INTERNAL_ERROR", "flow:async-failure")
                .doesNotContain("synthetic internal cause");
        RequestContextTest.assertClean();
    }

    @RestController
    static class Fixture {
        @GetMapping("/fixture/ok")
        Map<String, String> ok() {
            var context = RequestContextHolder.current().orElseThrow();
            assertThat(MDC.get("correlationId")).isEqualTo(context.correlationId());
            assertThat(MDC.get("requestId")).isEqualTo(context.requestId());
            return Map.of("correlationId", context.correlationId(), "requestId", context.requestId());
        }

        @GetMapping("/fixture/async")
        Callable<Map<String, String>> async() {
            var captured = RequestContextHolder.current().orElseThrow();
            return () -> {
                RequestContextTest.assertClean();
                try (var scope = RequestContextHolder.bind(captured)) {
                    return ok();
                } finally {
                    RequestContextTest.assertClean();
                }
            };
        }

        @GetMapping("/fixture/throw")
        void fail() { throw new IllegalStateException("synthetic internal cause"); }

        @GetMapping("/fixture/async-failure")
        Callable<Void> asyncFailure() {
            var captured = RequestContextHolder.current().orElseThrow();
            return () -> {
                RequestContextTest.assertClean();
                try (var scope = RequestContextHolder.bind(captured)) {
                    fail();
                    return null;
                } finally {
                    RequestContextTest.assertClean();
                }
            };
        }
    }

    @TestConfiguration
    static class SecurityHarness {
        @Bean Clock clock() { return Clock.systemUTC(); }

        @Bean
        SecurityFilterChain chain(HttpSecurity http, ProblemWriter writer) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.requestMatchers("/fixture/private").hasRole("ADMIN")
                            .anyRequest().permitAll())
                    .exceptionHandling(errors -> errors
                            .authenticationEntryPoint((request, response, exception) -> writer.write(response,
                                    writer.create(ErrorCode.AUTH_SESSION_EXPIRED, "Authentication required",
                                            request, List.of(), Map.of())))
                            .accessDeniedHandler((request, response, exception) -> writer.write(response,
                                    writer.create(ErrorCode.AUTH_FORBIDDEN, "Access denied",
                                            request, List.of(), Map.of()))))
                    .build();
        }
    }
}
