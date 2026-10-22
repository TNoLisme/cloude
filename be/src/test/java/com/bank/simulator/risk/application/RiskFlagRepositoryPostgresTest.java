package com.bank.simulator.risk.application;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RiskFlagRepositoryPostgresTest {

    @Test
    void repeatedEvaluationOfSameRuleVersionDoesNotDuplicateFlag() throws Exception {
        try (PostgreSQLContainer<?> postgres =
                     new PostgreSQLContainer<>("postgres:16-alpine")
                             .withDatabaseName("risk_flag_test")
                             .withUsername("test")
                             .withPassword("test")) {
            postgres.start();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            try (var connection = postgres.createConnection("")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V4__risk_flags.sql"));
            }
            var repository = new RiskFlagRepository(jdbc);
            UUID transferId = UUID.randomUUID();
            Instant detectedAt = Instant.parse("2026-10-11T00:00:00Z");

            repository.insert(transferId, "LARGE_TRANSFER", "1", "Threshold exceeded", detectedAt);
            repository.insert(transferId, "LARGE_TRANSFER", "1", "Threshold exceeded", detectedAt);

            assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_flags", Integer.class)).isEqualTo(1);
        }
    }
}
