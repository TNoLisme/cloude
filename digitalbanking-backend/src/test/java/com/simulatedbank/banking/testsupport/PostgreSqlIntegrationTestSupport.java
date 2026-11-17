package com.simulatedbank.banking.testsupport;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/** Shared, isolated PostgreSQL 16 configuration for integration tests. */
public final class PostgreSqlIntegrationTestSupport {

    private static final String IMAGE = "postgres:16-alpine";

    private PostgreSqlIntegrationTestSupport() {
    }

    public static PostgreSQLContainer<?> newContainer(String logicalName) {
        if (logicalName == null || !logicalName.matches("[a-z0-9_]{1,32}")) {
            throw new IllegalArgumentException("Invalid integration-test database name");
        }
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("it_" + logicalName)
                .withUsername("it_" + suffix.substring(0, 12))
                .withPassword(suffix)
                .withUrlParam("currentSchema", "banking");
    }

    public static void registerSpringProperties(
            DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.schemas", () -> "banking");
        registry.add("spring.flyway.default-schema", () -> "banking");
    }

    public static Map<String, Object> springApplicationProperties(PostgreSQLContainer<?> postgres) {
        requireRunning(postgres);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", postgres.getJdbcUrl());
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        properties.put("spring.flyway.url", postgres.getJdbcUrl());
        properties.put("spring.flyway.user", postgres.getUsername());
        properties.put("spring.flyway.password", postgres.getPassword());
        properties.put("spring.flyway.schemas", "banking");
        properties.put("spring.flyway.default-schema", "banking");
        return properties;
    }

    public static Flyway flyway(PostgreSQLContainer<?> postgres) {
        requireRunning(postgres);
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("banking")
                .defaultSchema("banking")
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .outOfOrder(false)
                .cleanDisabled(false)
                .load();
    }

    /** Fails before an assertion can accidentally exercise a non-container datasource. */
    public static void assertContainerDataSource(DataSource dataSource, PostgreSQLContainer<?> postgres) {
        requireRunning(postgres);
        try (var connection = dataSource.getConnection()) {
            String actual = withoutParameters(connection.getMetaData().getURL());
            String expected = withoutParameters(postgres.getJdbcUrl());
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Integration test datasource is not the owned Testcontainer");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot verify integration test datasource", failure);
        }
    }

    private static void requireRunning(PostgreSQLContainer<?> postgres) {
        if (postgres == null || !postgres.isRunning()) {
            throw new IllegalStateException("PostgreSQL Testcontainer is not running");
        }
    }

    private static String withoutParameters(String jdbcUrl) {
        int parameters = jdbcUrl.indexOf('?');
        return parameters < 0 ? jdbcUrl : jdbcUrl.substring(0, parameters);
    }
}
