package com.simulatedbank.banking.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.limit.api.LimitConfigMutationPort;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(LimitAdminMissingProviderIT.SessionFixture.class)
class LimitAdminMissingProviderIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("limit/init-roles.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", () -> "banking_app");
        properties.add("spring.datasource.password", () -> "test_app");
        properties.add("spring.flyway.url", postgres::getJdbcUrl);
        properties.add("spring.flyway.user", () -> "banking_migrator");
        properties.add("spring.flyway.password", () -> "test_migrator");
        properties.add("app.limit.policy.max-per-transaction-vnd", () -> "1000");
        properties.add("app.limit.policy.max-daily-vnd", () -> "2000");
    }

    @Autowired MockMvc mvc;
    @MockitoBean LimitConfigMutationPort limits;

    @Test
    void authenticatedRequestWithoutRequiredProvidersFailsClosed() throws Exception {
        // The integrated application now has providers; simulate an unavailable provider, not a missing module.
        org.mockito.Mockito.when(limits.currentVersion(11, null))
                .thenThrow(new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE));
        mvc.perform(post("/api/v1/admin/limits").header("Authorization", "Bearer maker")
                .header("Idempotency-Key", "unavailable-limit-provider")
                .contentType("application/json").content("""
                    {"customerId":"11","perTransactionLimit":"100.00","dailyLimit":"200.00"}
                    """))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @TestConfiguration
    static class SessionFixture {
        @Bean @org.springframework.context.annotation.Primary SessionAuthenticator sessionAuthenticator() {
            return token -> "maker".equals(token) ? Optional.of(new SessionAuthenticator.Session("1", null,
                    Set.of("ROLE_OPERATIONS", "LIMIT_MAKER"), "0".repeat(64))) : Optional.empty();
        }
    }
}
