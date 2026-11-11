package com.simulatedbank.banking.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class PostgreSqlFlywayMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("digital_banking")
            .withUsername("postgres")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "?currentSchema=banking");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", () -> postgres.getJdbcUrl() + "?currentSchema=banking");
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.schemas", () -> "banking");
        registry.add("spring.flyway.default-schema", () -> "banking");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliesV1MigrationAndFoundationProbeTableIsOperational() {
        // Verify that the table was created by Flyway
        Integer tableExists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'banking' AND table_name = 'foundation_probe'",
                Integer.class
        );
        assertThat(tableExists).isNotNull().isEqualTo(1);

        // Verify write and read capabilities on the foundation_probe table
        jdbcTemplate.update("INSERT INTO banking.foundation_probe (id) VALUES (?)", 42L);

        Long probeId = jdbcTemplate.queryForObject(
                "SELECT id FROM banking.foundation_probe WHERE id = ?",
                Long.class,
                42L
        );
        assertThat(probeId).isEqualTo(42L);
    }
}

