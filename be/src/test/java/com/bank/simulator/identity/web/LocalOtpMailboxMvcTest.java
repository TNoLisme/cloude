package com.bank.simulator.identity.web;

import com.bank.simulator.identity.infrastructure.otp.LocalMailboxOtpSender;
import com.bank.simulator.identity.infrastructure.otp.OtpSender;
import com.bank.simulator.shared.ratelimit.RateLimitInterceptor;
import com.bank.simulator.shared.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.security.jwt-secret=01234567890123456789012345678901",
        "app.otp.mailbox-guard-token=test-guard", "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration", "spring.profiles.active=local"})
@AutoConfigureMockMvc
@Import(LocalOtpMailboxMvcTest.PolicyConfig.class)
class LocalOtpMailboxMvcTest {

    @Autowired MockMvc mockMvc;
    @MockBean LocalMailboxOtpSender mailbox;
    @MockBean JdbcTemplate jdbcTemplate;
    @MockBean RateLimitInterceptor rateLimitInterceptor;

    @Test
    void localMailboxRequiresGuardAndReturnsContractShape() throws Exception {
        when(mailbox.findCode("0912345678")).thenReturn("000042");

        mockMvc.perform(get("/__local/otp-mailbox").param("identifier", "0912345678"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/__local/otp-mailbox").param("identifier", "0912345678")
                        .header("X-Local-Mailbox-Token", "test-guard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identifier").value("0912345678"))
                .andExpect(jsonPath("$.code").value("000042"));
    }

    @TestConfiguration
    static class PolicyConfig {
        @Bean
        @Primary
        OtpSender otpSender() {
            return message -> { };
        }

        @Bean
        @Primary
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(5, Duration.ofSeconds(60), 3, Duration.ofSeconds(300),
                    3, Duration.ofSeconds(300), 10, Duration.ofSeconds(300),
                    30, Duration.ofSeconds(60), 30, Duration.ofSeconds(60), 1000);
        }
    }
}
