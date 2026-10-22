package com.bank.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisposablePostgresTestSupportTest {

    @Test
    void resetRefusesDatasourceUrlThatDoesNotMatchDisposableContainer() throws Exception {
        PostgreSQLContainer<?> postgres = mock(PostgreSQLContainer.class);
        when(postgres.isRunning()).thenReturn(true);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn("jdbc:postgresql://localhost:5432/retained_db");

        assertThatThrownBy(() -> DisposablePostgresTestSupport.clearBusinessTables(
                jdbc, postgres, "jdbc:postgresql://localhost:5432/disposable_db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the disposable PostgreSQL container");
        verify(jdbc, never()).execute(DisposablePostgresTestSupport.BUSINESS_TABLES_TRUNCATE);
    }

    @Test
    void resetRefusesMissingDisposableContainer() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThatThrownBy(() -> DisposablePostgresTestSupport.clearBusinessTables(jdbc, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("running disposable PostgreSQL container");
    }

    @Test
    void resetCoversEveryBusinessTableAndPreservesFlywayHistory() {
        String sql = DisposablePostgresTestSupport.BUSINESS_TABLES_TRUNCATE;

        assertThat(sql).contains(
                "risk_flags", "transfers", "account_seed_records", "audit_events",
                "idempotency_records", "registration_verification_tokens",
                "recovery_reset_tokens", "otp_challenges", "customer_pins",
                "accounts", "customers", "user_roles", "refresh_sessions", "users");
        assertThat(sql).doesNotContain("flyway_schema_history");
    }
}
