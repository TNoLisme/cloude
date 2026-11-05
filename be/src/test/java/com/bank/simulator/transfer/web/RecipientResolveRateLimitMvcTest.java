package com.bank.simulator.transfer.web;

import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.api.Problem;
import com.bank.simulator.shared.ratelimit.RateLimitInterceptor;
import com.bank.simulator.shared.ratelimit.RateLimitProperties;
import com.bank.simulator.transfer.application.ResolveRecipientService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.security.jwt-secret=01234567890123456789012345678901",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"})
@AutoConfigureMockMvc(addFilters = false)
@Import(RecipientResolveRateLimitMvcTest.Properties.class)
class RecipientResolveRateLimitMvcTest {

    @Autowired MockMvc mockMvc;
    @MockBean ResolveRecipientService resolveRecipient;
    @MockBean JdbcTemplate jdbcTemplate;
    @MockBean PlatformTransactionManager transactionManager;

    @BeforeEach
    void actor() {
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), Set.of("CUSTOMER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, java.util.List.of()));
        when(resolveRecipient.resolve(any(), any())).thenReturn(new ResolveRecipientService.Recipient(
                UUID.randomUUID(), "••••9012", "Recipient", "VND"));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsProblem429WhenActorResolveBucketExhausted() throws Exception {
        String body = "{\"accountNumber\":\"123456789012\"}";
        for (int n = 0; n < 30; n++) {
            mockMvc.perform(post("/recipients/resolve").contentType("application/json").content(body))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/recipients/resolve").contentType("application/json").content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(result -> {
                    Problem problem = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(result.getResponse().getContentAsString(), Problem.class);
                    org.assertj.core.api.Assertions.assertThat(problem.code()).isEqualTo("RATE_LIMITED");
                });
    }

    @TestConfiguration
    static class Properties {
        @Bean
        @Primary
        RateLimitInterceptor rateLimitInterceptor(RateLimitProperties properties) {
            return new RateLimitInterceptor(properties);
        }

        @Bean
        @Primary
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(1, Duration.ofSeconds(60), 3, Duration.ofSeconds(300),
                    3, Duration.ofSeconds(300), 5, Duration.ofSeconds(300), 10, Duration.ofSeconds(300),
                    30, Duration.ofSeconds(60), 30, Duration.ofSeconds(60), 1000);
        }
    }
}
