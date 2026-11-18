package com.simulatedbank.banking.limit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
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
@Import(LimitCustomerMissingProviderIT.SessionProvider.class)
class LimitCustomerMissingProviderIT {
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
    }

    @Autowired MockMvc mvc;
    @MockitoBean AccountQuery accounts;

    @Test
    void selectedAccountWithoutProviderFailsClosed() throws Exception {
        org.mockito.Mockito.when(accounts.findAccount(21))
                .thenThrow(new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE));
        mvc.perform(get("/api/v1/limits").param("accountId", "21").header("Authorization", "Bearer customer"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @TestConfiguration
    static class SessionProvider {
        @Bean @org.springframework.context.annotation.Primary SessionAuthenticator sessions() {
            return token -> Optional.of(new SessionAuthenticator.Session("1", "11",
                    Set.of("ROLE_CUSTOMER", "LIMIT_READ_SELF"), "0".repeat(64)));
        }
    }
}
