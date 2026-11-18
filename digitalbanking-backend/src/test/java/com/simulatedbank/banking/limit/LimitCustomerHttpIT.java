package com.simulatedbank.banking.limit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.support.IntegratedLimitReferences;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(LimitCustomerHttpIT.Providers.class)
class LimitCustomerHttpIT {
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
        properties.add("app.timezone", () -> "Asia/Ho_Chi_Minh");
    }
    @Autowired MockMvc mvc;

    @BeforeEach
    void reset() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE banking.limit_config,banking.limit_usage RESTART IDENTITY CASCADE");
            IntegratedLimitReferences.seed(connection, false);
            statement.execute("""
                INSERT INTO banking.limit_config(customer_id,account_id,per_transaction_limit,daily_limit,currency,
                  effective_from,effective_to,status,version,created_by,approved_by,approved_at)
                VALUES (11,NULL,100,200,'VND','2026-11-01T00:00:00Z',NULL,'ACTIVE',1,1,2,now()),
                       (11,21,80,150,'VND','2026-11-01T00:00:00Z',NULL,'ACTIVE',1,1,2,now()),
                       (11,22,1,2,'VND','2026-11-01T00:00:00Z',NULL,'ACTIVE',1,1,2,now()),
                       (12,NULL,1,2,'VND','2026-11-01T00:00:00Z',NULL,'ACTIVE',1,1,2,now())
                """);
            statement.execute("""
                INSERT INTO banking.limit_usage(customer_id,account_id,usage_date,used_amount,transfer_count)
                VALUES (11,NULL,'2026-11-17',50,1),(11,21,'2026-11-17',140,1),
                       (11,21,'2026-11-16',9,1)
                """);
        }
    }

    @Test
    void noAccountOnlyReturnsCustomerScopeAndBusinessDate() throws Exception {
        mvc.perform(get("/api/v1/limits").header("Authorization", "Bearer customer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerId").value("11"))
                .andExpect(jsonPath("$.usageDate").value("2026-11-17"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].scope").value("CUSTOMER"))
                .andExpect(jsonPath("$.items[0].usedToday").value("50.00"))
                .andExpect(jsonPath("$.effectiveRemainingDaily").value("150.00"));
    }

    @Test
    void selectedAccountUsesEachScopeRemaining() throws Exception {
        mvc.perform(get("/api/v1/limits").param("accountId", "21").header("Authorization", "Bearer customer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].accountId").value("21"))
                .andExpect(jsonPath("$.items[1].remainingDaily").value("10.00"))
                .andExpect(jsonPath("$.effectiveRemainingDaily").value("10.00"))
                .andExpect(jsonPath("$.effectivePerTransactionLimit").value("80.00"));
    }

    @Test
    void deniesForeignAccountAndInvalidCredentialsOrInput() throws Exception {
        mvc.perform(get("/api/v1/limits")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/limits").header("Authorization", "Bearer no-permission"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/limits").param("accountId", "22").header("Authorization", "Bearer customer"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/limits").param("customerId", "12").header("Authorization", "Bearer customer"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/limits").param("accountId", "999").header("Authorization", "Bearer customer"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/limits").param("accountId", "-1").header("Authorization", "Bearer customer"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyConfigReturnsNullEffectiveFieldsWithoutCreatingCounters() throws Exception {
        sql("DELETE FROM banking.limit_config WHERE customer_id=11");
        long before = countUsage();
        mvc.perform(get("/api/v1/limits").header("Authorization", "Bearer customer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.effectivePerTransactionLimit").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.effectiveRemainingDaily").value(org.hamcrest.Matchers.nullValue()));
        org.assertj.core.api.Assertions.assertThat(countUsage()).isEqualTo(before);
    }

    @Test
    void clipsRemainingAndUsesZeroWhenNoAccountCounter() throws Exception {
        sql("UPDATE banking.limit_usage SET used_amount=250 WHERE customer_id=11 AND account_id IS NULL");
        sql("DELETE FROM banking.limit_usage WHERE account_id=21 AND usage_date='2026-11-17'");
        mvc.perform(get("/api/v1/limits").param("accountId", "21").header("Authorization", "Bearer customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].remainingDaily").value("0.00"))
                .andExpect(jsonPath("$.items[1].usedToday").value("0.00"))
                .andExpect(jsonPath("$.effectiveRemainingDaily").value("0.00"));
        org.assertj.core.api.Assertions.assertThat(countUsage()).isEqualTo(2);
    }

    @Test
    void effectiveFromInclusiveAndEffectiveToExclusive() throws Exception {
        sql("UPDATE banking.limit_config SET effective_from='2026-11-16T18:00:00Z' WHERE customer_id=11 AND account_id IS NULL");
        sql("UPDATE banking.limit_config SET effective_to='2026-11-16T18:00:00Z' WHERE account_id=21");
        mvc.perform(get("/api/v1/limits").param("accountId", "21").header("Authorization", "Bearer customer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].scope").value("CUSTOMER"));
    }

    @Test
    void pendingAndFutureConfigurationsAreExcludedAndAccountOnlyWorks() throws Exception {
        sql("UPDATE banking.limit_config SET effective_from='2026-11-17T00:00:00Z' WHERE customer_id=11 AND account_id IS NULL");
        mvc.perform(get("/api/v1/limits").param("accountId", "21").header("Authorization", "Bearer customer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].scope").value("ACCOUNT"));
        sql("UPDATE banking.limit_config SET status='PENDING_APPROVAL' WHERE account_id=21");
        mvc.perform(get("/api/v1/limits").param("accountId", "21").header("Authorization", "Bearer customer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void readsCommittedUsageWithoutChangingIt() throws Exception {
        sql("UPDATE banking.limit_usage SET used_amount=75 WHERE customer_id=11 AND account_id IS NULL");
        mvc.perform(get("/api/v1/limits").header("Authorization", "Bearer customer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].usedToday").value("75.00"))
                .andExpect(jsonPath("$.effectiveRemainingDaily").value("125.00"));
        org.assertj.core.api.Assertions.assertThat(countUsage()).isEqualTo(3);
    }

    @Test
    void readDoesNotWaitForAnUncommittedUsageRowUpdate() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("UPDATE banking.limit_usage SET used_amount=90 WHERE customer_id=11 AND account_id IS NULL");
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () ->
                    mvc.perform(get("/api/v1/limits").header("Authorization", "Bearer customer"))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].usedToday").value("50.00")));
            connection.rollback();
        }
    }

    private void sql(String text) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(text);
        }
    }

    private long countUsage() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM banking.limit_usage")) {
            result.next();
            return result.getLong(1);
        }
    }

    @TestConfiguration
    static class Providers {
        @Bean @Primary Clock testClock() { return Clock.fixed(Instant.parse("2026-11-16T18:00:00Z"), ZoneOffset.UTC); }
        @Bean @Primary SessionAuthenticator sessions() {
            return token -> switch (token) {
                case "customer" -> Optional.of(new SessionAuthenticator.Session("1", "11", Set.of("ROLE_CUSTOMER", "LIMIT_READ_SELF"), "0".repeat(64)));
                case "no-permission" -> Optional.of(new SessionAuthenticator.Session("1", "11", Set.of("ROLE_CUSTOMER"), "0".repeat(64)));
                default -> Optional.empty();
            };
        }
        @Bean @Primary AccountQuery accounts() {
            AccountQuery provider = mock(AccountQuery.class);
            when(provider.findAccount(21)).thenReturn(Optional.of(new AccountQuery.AccountSnapshot(21, 11,
                    AccountQuery.Status.ACTIVE, "VND", "**** **** 0021", "0021")));
            when(provider.findAccount(22)).thenReturn(Optional.of(new AccountQuery.AccountSnapshot(22, 12,
                    AccountQuery.Status.ACTIVE, "VND", "**** **** 0022", "0022")));
            return provider;
        }
    }
}
