package com.simulatedbank.banking.migration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class PostgreSqlFlywayMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("digital_banking")
            .withUsername("postgres")
            .withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.schemas", () -> "banking");
        registry.add("spring.flyway.default-schema", () -> "banking");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void flywayAppliesV1SchemaAndJpaEntityManagerIsOperational() {
        Integer schemaExists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'banking'",
                Integer.class
        );
        assertThat(schemaExists).isEqualTo(1);

        Integer v1Applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM banking.flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class
        );
        assertThat(v1Applied).isEqualTo(1);

        Integer probeExists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'banking' AND table_name = 'foundation_probe'",
                Integer.class
        );
        assertThat(probeExists).isZero();

        String currentSchema = (String) entityManager
                .createNativeQuery("SELECT current_schema()")
                .getSingleResult();
        assertThat(currentSchema).isEqualTo("banking");
    }
}
