package com.simulatedbank.banking.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** BE-INT-02: legacy module-local history must fail closed against the integrated chain. */
@Testcontainers
class LegacyModuleMigrationPostgreSqlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("legacy_history");

    @Test
    void everyLegacyPrefixIsRejectedWithoutChangingHistoryOrBusinessRows() throws Exception {
        for (int lastLegacyVersion = 2; lastLegacyVersion <= 6; lastLegacyVersion++) {
            PostgreSqlIntegrationTestSupport.flyway(postgres).clean();

            Flyway canonicalV1 = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas("banking").defaultSchema("banking")
                    .locations("classpath:db/migration").target("1")
                    .cleanDisabled(false).load();
            assertThat(canonicalV1.migrate().migrationsExecuted).isEqualTo(1);

            Flyway legacy = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas("banking").defaultSchema("banking")
                    .locations("classpath:db/migration-legacy")
                    .target(String.valueOf(lastLegacyVersion))
                    .validateOnMigrate(false).load();
            assertThat(legacy.migrate().migrationsExecuted).isEqualTo(lastLegacyVersion - 1);

            if (lastLegacyVersion == 6) {
                seedLegacyBusinessRows();
            }
            int historyBefore = count("SELECT count(*) FROM banking.flyway_schema_history WHERE version IS NOT NULL");
            int auditBefore = count("SELECT count(*) FROM banking.audit_log");
            int limitBefore = lastLegacyVersion >= 3
                    ? count("SELECT count(*) FROM banking.limit_config") : 0;
            int approvalBefore = lastLegacyVersion >= 6
                    ? count("SELECT count(*) FROM banking.approval_request") : 0;

            Flyway integrated = PostgreSqlIntegrationTestSupport.flyway(postgres);
            assertThatThrownBy(integrated::validate)
                    .as("legacy V2..V%s must not validate as canonical", lastLegacyVersion)
                    .hasMessageContaining("Migration checksum mismatch");
            assertThatThrownBy(integrated::migrate)
                    .as("legacy V2..V%s must not start the application", lastLegacyVersion)
                    .hasMessageContaining("Migration checksum mismatch");

            assertThat(count("SELECT count(*) FROM banking.flyway_schema_history WHERE version IS NOT NULL"))
                    .isEqualTo(historyBefore);
            assertThat(count("SELECT count(*) FROM banking.audit_log")).isEqualTo(auditBefore);
            if (lastLegacyVersion >= 3) {
                assertThat(count("SELECT count(*) FROM banking.limit_config")).isEqualTo(limitBefore);
            }
            if (lastLegacyVersion >= 6) {
                assertThat(count("SELECT count(*) FROM banking.approval_request"))
                        .isEqualTo(approvalBefore);
                restoreBackupAndCheckRows(historyBefore, auditBefore, limitBefore, approvalBefore);
            }
        }
    }

    private static void seedLegacyBusinessRows() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO banking.audit_log (occurred_at, actor_type, actor_role, action,
                        entity_type, entity_id, correlation_id, request_id, outcome, prev_hash,
                        record_hash, hash_version, canonical_payload)
                    VALUES (now(), 'SYSTEM', 'SYSTEM', 'LEGACY_PROBE', 'SYSTEM', '1',
                        'legacy-correlation', 'legacy-request', 'SUCCESS', repeat('0',64),
                        repeat('a',64), 1, '{}'::jsonb)
                    """);
            statement.execute("""
                    INSERT INTO banking.limit_config (customer_id, per_transaction_limit,
                        daily_limit, effective_from, created_by)
                    VALUES (101, 1000, 5000, now(), 201)
                    """);
            statement.execute("""
                    INSERT INTO banking.approval_request (request_type, target_type, target_id,
                        payload, maker_id, correlation_id)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '101', '{}'::jsonb, 201,
                        'legacy-correlation')
                    """);
        }
    }

    private static void restoreBackupAndCheckRows(
            int history, int audit, int limit, int approval) throws Exception {
        String database = postgres.getDatabaseName();
        String username = postgres.getUsername();
        assertCommand("pg_dump", "-U", username, "-d", database, "-n", "banking",
                "-Fc", "-f", "/tmp/be-int-02-legacy.dump");
        assertCommand("createdb", "-U", username, "be_int_02_restored");
        assertCommand("pg_restore", "-U", username, "-d", "be_int_02_restored",
                "/tmp/be-int-02-legacy.dump");

        String restoredUrl = postgres.getJdbcUrl().replace("/" + database + "?", "/be_int_02_restored?");
        try (Connection restored = DriverManager.getConnection(
                restoredUrl, username, postgres.getPassword())) {
            assertThat(count(restored,
                    "SELECT count(*) FROM banking.flyway_schema_history WHERE version IS NOT NULL"))
                    .isEqualTo(history);
            assertThat(count(restored, "SELECT count(*) FROM banking.audit_log")).isEqualTo(audit);
            assertThat(count(restored, "SELECT count(*) FROM banking.limit_config")).isEqualTo(limit);
            assertThat(count(restored, "SELECT count(*) FROM banking.approval_request"))
                    .isEqualTo(approval);
            assertThat(count(restored, "SELECT count(*) FROM banking.audit_log "
                    + "WHERE action = 'LEGACY_PROBE' AND correlation_id = 'legacy-correlation'"))
                    .isEqualTo(1);
        }
    }

    private static void assertCommand(String... command) throws Exception {
        var result = postgres.execInContainer(command);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private static int count(String sql) throws SQLException {
        try (Connection connection = connection()) {
            return count(connection, sql);
        }
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
