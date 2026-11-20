package com.simulatedbank.banking.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** BE-INT-09: forward upgrade, least privilege and real two-connection catalogue locking. */
@Testcontainers
class IdentityRoleLockV23PostgreSqlIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("limit/init-roles.sql");

    @Test
    void upgradePreservesHistoryAndAllowsOnlyTheCatalogueReadLock() throws Exception {
        assertThat(migration("22").migrate().migrationsExecuted).isEqualTo(22);
        List<String> history = history();
        var latest = migration(null);
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(history()).isEqualTo(history);

        try (var app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "banking_app", "test_app");
             var owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "banking_migrator", "test_migrator");
             var sql = app.createStatement();
             var other = owner.createStatement()) {
            try (var rows = sql.executeQuery("SELECT has_any_column_privilege(current_user,'banking.role','UPDATE'), "
                    + "has_function_privilege(current_user,'banking.lock_role_for_assignment(text)','EXECUTE')")) {
                rows.next();
                assertThat(rows.getBoolean(1)).isFalse();
                assertThat(rows.getBoolean(2)).isTrue();
            }
            try (var rows = other.executeQuery("SELECT p.prosecdef, p.proconfig::text, "
                    + "NOT EXISTS (SELECT 1 FROM aclexplode(p.proacl) a WHERE a.grantee=0 AND a.privilege_type='EXECUTE') "
                    + "FROM pg_proc p WHERE p.oid='banking.lock_role_for_assignment(text)'::regprocedure")) {
                rows.next();
                assertThat(rows.getBoolean(1)).isTrue();
                assertThat(rows.getString(2)).contains("search_path=pg_catalog");
                assertThat(rows.getBoolean(3)).isTrue();
            }
            app.setAutoCommit(false);
            try (var rows = sql.executeQuery("SELECT role_code,status FROM banking.lock_role_for_assignment('AUDITOR')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("AUDITOR");
                assertThat(rows.getString(2)).isEqualTo("ACTIVE");
            }
            other.execute("SET lock_timeout='100ms'");
            assertThatThrownBy(() -> other.executeUpdate("UPDATE banking.role SET status='DISABLED' WHERE role_code='AUDITOR'"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55P03"));
            app.rollback();
            assertThat(other.executeUpdate("UPDATE banking.role SET status='ACTIVE' WHERE role_code='AUDITOR'")).isEqualTo(1);
            app.setAutoCommit(true);
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE banking.role SET status='DISABLED' WHERE role_code='AUDITOR'"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
            try (var query = app.prepareStatement("SELECT * FROM banking.lock_role_for_assignment(?)")) {
                query.setString(1, "AUDITOR' OR true --");
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isFalse();
                }
            }
        }
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }

    private static Flyway migration(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "banking_migrator", "test_migrator")
                .schemas("banking").defaultSchema("banking").locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    private static List<String> history() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "banking_migrator", "test_migrator");
             var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT version || ':' || checksum FROM banking.flyway_schema_history "
                     + "WHERE version::int<=22 ORDER BY installed_rank")) {
            List<String> history = new ArrayList<>();
            while (rows.next()) history.add(rows.getString(1));
            return history;
        }
    }
}
