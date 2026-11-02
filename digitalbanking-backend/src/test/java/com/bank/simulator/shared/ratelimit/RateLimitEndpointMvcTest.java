package com.bank.simulator.shared.ratelimit;

import com.bank.simulator.shared.api.Problem;
import com.bank.simulator.identity.application.OnboardingService;
import com.bank.simulator.identity.application.RecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.security.jwt-secret=01234567890123456789012345678901",
        "app.rate-limit.login-limit=1", "app.rate-limit.login-window=60s",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"})
@AutoConfigureMockMvc(addFilters = false)
@Import(RateLimitEndpointMvcTest.Properties.class)
class RateLimitEndpointMvcTest {

    @Autowired MockMvc mockMvc;
    @MockBean OnboardingService onboardingService;
    @MockBean RecoveryService recoveryService;
    @MockBean JdbcTemplate jdbcTemplate;
    @MockBean PlatformTransactionManager transactionManager;

    @Test
    void returnsProblem429AndRetryAfterWhenLoginBucketExhausted() throws Exception {
        when(onboardingService.login("0912345678", "not-a-real-password"))
                .thenThrow(new com.bank.simulator.shared.error.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "CREDENTIALS_INVALID", "Phone or password is invalid."));
        String body = "{\"phone\":\"0912345678\",\"password\":\"not-a-real-password\"}";
        mockMvc.perform(post("/auth/login").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/login").contentType("application/json").content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(result -> {
                    Problem problem = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(result.getResponse().getContentAsString(), Problem.class);
                    org.assertj.core.api.Assertions.assertThat(problem.code()).isEqualTo("RATE_LIMITED");
                });
    }

    @Test
    void recoveryVerifyRejectsWhenIpBucketIsExhaustedAcrossIdentifiers() throws Exception {
        when(recoveryService.verify(anyString(), anyString(), anyString()))
                .thenReturn(RecoveryService.VerifyResult.invalid());
        for (int n = 0; n < 5; n++) {
            String body = "{\"identifier\":\"ip-test-" + n + "@example.test\",\"channel\":\"EMAIL\",\"otp\":\"123456\"}";
            mockMvc.perform(post("/auth/recover/verify").contentType("application/json").content(body)
                    .with(request -> { request.setRemoteAddr("192.0.2.61"); request.setAttribute("cachedRequestBody", body); return request; }))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/auth/recover/verify").contentType("application/json")
                        .content("{\"identifier\":\"ip-test-5@example.test\",\"channel\":\"EMAIL\",\"otp\":\"123456\"}")
                        .with(request -> { request.setRemoteAddr("192.0.2.61"); request.setAttribute("cachedRequestBody", "{\"identifier\":\"ip-test-5@example.test\"}"); return request; }))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void recoveryVerifyRejectsWhenIdentifierBucketIsExhaustedAcrossIps() throws Exception {
        when(recoveryService.verify(anyString(), anyString(), anyString()))
                .thenReturn(RecoveryService.VerifyResult.invalid());
        String body = "{\"identifier\":\"same@example.test\",\"channel\":\"EMAIL\",\"otp\":\"123456\"}";
        for (int n = 1; n <= 5; n++) {
            String ip = "192.0.2." + n;
            mockMvc.perform(post("/auth/recover/verify").contentType("application/json").content(body)
                    .with(request -> { request.setRemoteAddr(ip); request.setAttribute("cachedRequestBody", body); return request; }))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/auth/recover/verify").contentType("application/json").content(body)
                        .with(request -> { request.setRemoteAddr("192.0.2.6"); request.setAttribute("cachedRequestBody", body); return request; }))
                .andExpect(status().isTooManyRequests());
    }

    @TestConfiguration
    static class Properties {
        @Bean
        @org.springframework.context.annotation.Primary
        RateLimitInterceptor rateLimitInterceptor(RateLimitProperties properties) {
            return new RateLimitInterceptor(properties);
        }

        @Bean
        @org.springframework.context.annotation.Primary
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(1, Duration.ofSeconds(60), 3, Duration.ofSeconds(300),
                    3, Duration.ofSeconds(300), 5, Duration.ofSeconds(300), 10, Duration.ofSeconds(300),
                    30, Duration.ofSeconds(60), 30, Duration.ofSeconds(60), 1000);
        }
    }
}
