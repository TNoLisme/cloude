package com.bank.simulator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared disposable PostgreSQL contract for BE-A and BE-3 integration tests.
 *
 * <p>Owner: BE-B. Consumers call the static helpers only and must not fork a
 * private container or cleanup routine. Existing tests that declare their own
 * {@code @Container} keep working; new PostgreSQL tests should use
 * {@link #newDisposablePostgres(String)} plus
 * {@link #registerDatasource(DynamicPropertyRegistry, PostgreSQLContainer)}
 * and {@link #clearBusinessTables(JdbcTemplate, PostgreSQLContainer)}.
 */
public final class DisposablePostgresTestSupport {

    public static final String IMAGE = "postgres:16-alpine";
    public static final String TEST_USER = "test";
    public static final String TEST_PASSWORD = "test";
    public static final String JWT_TEST_SECRET = "01234567890123456789012345678901";

    /**
     * Business tables reset between tests. Keeps Flyway migration history
     * intact. Matches the legacy regression reset order.
     */
    public static final String BUSINESS_TABLES_TRUNCATE =
            "TRUNCATE risk_flags, transfers, account_seed_records, audit_events, "
                    + "idempotency_records, registration_verification_tokens, recovery_reset_tokens, "
                    + "otp_challenges, customer_pins, accounts, customers, user_roles, "
                    + "refresh_sessions, users CASCADE";

    private DisposablePostgresTestSupport() {
    }

    public static PostgreSQLContainer<?> newDisposablePostgres(String databaseName) {
        return new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName(databaseName)
                .withUsername(TEST_USER)
                .withPassword(TEST_PASSWORD);
    }

    public static void registerDatasource(
            DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.security.jwt-secret", () -> JWT_TEST_SECRET);
        registry.add("spring.flyway.enabled", () -> true);
    }

    /**
     * Resets only disposable business data. Never call against a retained,
     * shared, or production database.
     */
    public static void clearBusinessTables(
            JdbcTemplate jdbc, PostgreSQLContainer<?> postgres) {
        clearBusinessTables(jdbc, postgres, postgres == null ? null : postgres.getJdbcUrl());
    }

    public static void clearBusinessTables(
            JdbcTemplate jdbc, PostgreSQLContainer<?> postgres, String expectedJdbcUrl) {
        if (postgres == null || !postgres.isRunning()) {
            throw new IllegalStateException("Refusing to reset data without a running disposable PostgreSQL container");
        }
        if (expectedJdbcUrl == null || expectedJdbcUrl.isBlank()) {
            throw new IllegalStateException("Refusing to reset data without an expected disposable JDBC URL");
        }
        try (var connection = jdbc.getDataSource().getConnection()) {
            String actualUrl = connection.getMetaData().getURL();
            if (!expectedJdbcUrl.equals(actualUrl)) {
                throw new IllegalStateException("Refusing to reset data outside the disposable PostgreSQL container");
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Unable to verify disposable PostgreSQL datasource", exception);
        }
        jdbc.execute(BUSINESS_TABLES_TRUNCATE);
    }
}
