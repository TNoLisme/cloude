package com.simulatedbank.banking.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** BE-INT-02: forward upgrade removes broad runtime UPDATE without rewriting applied history. */
@Testcontainers
class ApprovalPrivilegesV18MigrationPostgreSqlIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("limit/init-roles.sql");

    @Test
    void upgradePreservesV17ChecksumsAndRestrictsApprovalUpdates() throws Exception {
        Flyway before = migration("17");
        assertThat(before.migrate().migrationsExecuted).isEqualTo(17);
        List<String> history = historyThroughV17();
        assertThat(columnUpdate("maker_id")).isTrue();

        Flyway latest = migration(null);
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(7);
        latest.validate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("24");
        assertThat(historyThroughV17()).isEqualTo(history);
        for (String column : List.of("approval_id", "maker_id", "request_type", "target_type", "target_id",
                "created_at", "correlation_id")) {
            assertThat(columnUpdate(column)).as("UPDATE %s", column).isFalse();
        }
        for (String column : List.of("payload", "status", "checker_id", "decision_note", "decided_at", "applied_at")) {
            assertThat(columnUpdate(column)).as("UPDATE %s", column).isTrue();
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "banking_app", "test_app");
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT has_table_privilege(current_user, 'banking.approval_request', 'DELETE')")) {
            rows.next();
            assertThat(rows.getBoolean(1)).isFalse();
        }
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }

    private static Flyway migration(String target) {
        var config = Flyway.configure().dataSource(postgres.getJdbcUrl(), "banking_migrator", "test_migrator")
                .schemas("banking").defaultSchema("banking").locations("classpath:db/migration")
                .validateOnMigrate(true);
        if (target != null) config.target(target);
        return config.load();
    }

    private static List<String> historyThroughV17() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT version || ':' || checksum FROM banking.flyway_schema_history "
                     + "WHERE version::integer <= 17 ORDER BY installed_rank")) {
            List<String> history = new ArrayList<>();
            while (rows.next()) history.add(rows.getString(1));
            return history;
        }
    }

    private static boolean columnUpdate(String column) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "banking_app", "test_app");
             var statement = connection.prepareStatement(
                     "SELECT has_column_privilege(current_user, 'banking.approval_request', ?, 'UPDATE')")) {
            statement.setString(1, column);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }
}
