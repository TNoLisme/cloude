package com.simulatedbank.banking.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.simulatedbank.banking.limit.adapter.out.LimitJdbcRepository;
import com.simulatedbank.banking.limit.api.LimitConfiguration;
import com.simulatedbank.banking.limit.api.LimitEnforcement;
import com.simulatedbank.banking.limit.application.LimitEnforcementService;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class LimitBusinessDateIT {
    private static final Instant BEFORE = Instant.parse("2026-11-15T16:59:59Z");
    private static final Instant AFTER = Instant.parse("2026-11-15T17:00:00Z");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("limit/init-roles.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "banking_app");
        registry.add("spring.datasource.password", () -> "test_app");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "banking_migrator");
        registry.add("spring.flyway.password", () -> "test_migrator");
    }

    @Autowired LimitEnforcement enforcement;
    @MockitoSpyBean LimitJdbcRepository repository;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ZoneId businessZoneId;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    private LimitJdbcRepository timeSource;

    @BeforeEach
    void reset() throws Exception {
        timeSource = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(repository);
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE banking.limit_config,banking.limit_usage RESTART IDENTITY");
        }
        transactions.executeWithoutResult(tx -> {
            for (Long account : new Long[]{null, 21L}) {
                repository.insertConfiguration(new LimitConfiguration(null, 11, account,
                        new BigDecimal("100"), new BigDecimal("100"), "VND",
                        Instant.parse("2020-01-01T00:00:00Z"), null, LimitConfiguration.Status.ACTIVE,
                        1, 1, 2L, null, null, BEFORE.minusSeconds(3600), null));
            }
        });
    }

    @Test
    void transactionCrossingMidnightRetainsDateAndNextTransactionUsesNewDay() {
        assertThat(businessZoneId).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
        doReturn(BEFORE).when(timeSource).transactionStart();
        transactions.executeWithoutResult(tx -> {
            var check = enforcement.lockAndCheck(11, 21, new BigDecimal("100"));
            assertThat(check.asOf()).isEqualTo(BEFORE);
            assertThat(check.usageDate()).isEqualTo(LocalDate.of(2026, 11, 15));
            doReturn(AFTER).when(timeSource).transactionStart();
            enforcement.recordCompleted(check);
        });
        transactions.executeWithoutResult(tx -> {
            var check = enforcement.lockAndCheck(11, 21, new BigDecimal("100"));
            assertThat(check.usageDate()).isEqualTo(LocalDate.of(2026, 11, 16));
            enforcement.recordCompleted(check);
        });
        verify(timeSource, times(2)).transactionStart();
        var rows = jdbc.queryForList("SELECT usage_date,used_amount,transfer_count FROM banking.limit_usage ORDER BY usage_date");
        assertThat(rows).hasSize(4);
        for (var row : rows) {
            assertThat((BigDecimal) row.get("used_amount")).isEqualByComparingTo("100");
            assertThat(row.get("transfer_count")).isEqualTo(1);
        }
    }

    @Test
    void providerUsesConfiguredZoneRatherThanHardcodedVietnamOrJvmZone() {
        doReturn(AFTER).when(timeSource).transactionStart();
        // Same implementation with the configured ZoneId dependency changed, no replacement persistence.
        var utc = new LimitEnforcementService(repository, ZoneId.of("UTC"), entityManagerFactory);
        transactions.executeWithoutResult(tx -> {
            var check = utc.lockAndCheck(11, 21, new BigDecimal("1"));
            assertThat(check.usageDate()).isEqualTo(LocalDate.of(2026, 11, 15));
            utc.recordCompleted(check);
        });
        assertThat(jdbc.queryForObject("SELECT DISTINCT usage_date FROM banking.limit_usage", LocalDate.class))
                .isEqualTo(LocalDate.of(2026, 11, 15));
    }

    @Test
    void realDatabaseTimeIsTransactionStartAndDoesNotAdvanceWithWallClock() {
        doCallRealMethod().when(timeSource).transactionStart();
        transactions.executeWithoutResult(tx -> {
            Instant started = jdbc.queryForObject("SELECT transaction_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
            jdbc.execute("SELECT pg_sleep(0.02)");
            var check = enforcement.lockAndCheck(11, 21, new BigDecimal("1"));
            assertThat(check.asOf()).isEqualTo(started);
            assertThat(check.usageDate()).isEqualTo(started.atZone(businessZoneId).toLocalDate());
            jdbc.execute("SELECT pg_sleep(0.02)");
            assertThat(repository.transactionStart()).isEqualTo(started);
            assertThat(jdbc.queryForObject("SELECT clock_timestamp() > transaction_timestamp()", Boolean.class)).isTrue();
            enforcement.recordCompleted(check);
        });
    }
}
