package com.simulatedbank.banking.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** BE-ID-TEST-02: complete Flyway chain from an empty, disposable PostgreSQL database. */
@Testcontainers
class PostgreSqlFreshMigrationIntegrationTest {

    private static final List<String> IDENTITY_TABLES = List.of(
            "app_user", "device", "session", "auth_challenge", "auth_attempt",
            "password_history", "role", "permission", "user_role", "role_permission",
            "user_privilege");

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("identity_migration");

    @Test
    void completeMigrationChainIsOrderedRepeatableAndPreservesUnrelatedSchema() throws Exception {
        try (Connection connection = connection()) {
            assertThat(singleInt(connection,
                    "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'banking'"))
                    .isZero();
            execute(connection, "CREATE TABLE public.be_id_test_02_sentinel (id integer PRIMARY KEY, value text NOT NULL)");
            execute(connection, "INSERT INTO public.be_id_test_02_sentinel VALUES (1, 'preserve-me')");
        }

        var flyway = PostgreSqlIntegrationTestSupport.flyway(postgres);
        var first = flyway.migrate();

        // Số migration kỳ vọng suy từ resource hiện hành thay vì hard-code: nhánh Identity-only
        // có V1..V4 (V5 Customer thuộc BE-CUS, merge sau); sau khi V5 vào branch test vẫn đúng.
        List<String> expectedVersions;
        try (var resources = java.nio.file.Files.list(
                java.nio.file.Path.of("src/main/resources/db/migration"))) {
            expectedVersions = resources
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("V\\d+__.*\\.sql"))
                    .map(name -> name.substring(1, name.indexOf("__")))
                    .distinct()
                    .sorted(java.util.Comparator.comparingInt(Integer::parseInt))
                    .toList();
        }
        assertThat(expectedVersions).isNotEmpty();
        assertThat(first.migrationsExecuted).isEqualTo(expectedVersions.size());
        try (Connection connection = connection()) {
            assertThat(queryStrings(connection,
                    "SELECT version FROM banking.flyway_schema_history "
                            + "WHERE version IS NOT NULL AND success ORDER BY installed_rank"))
                    .containsExactlyElementsOf(expectedVersions);
            assertThat(singleInt(connection,
                    "SELECT count(*) FROM banking.flyway_schema_history "
                            + "WHERE version IS NOT NULL AND (NOT success OR checksum IS NULL)"))
                    .isZero();
            assertThat(queryStrings(connection,
                    "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = 'banking' AND table_name = ANY (ARRAY["
                            + quoted(IDENTITY_TABLES) + "]) ORDER BY table_name"))
                    .containsExactlyElementsOf(IDENTITY_TABLES.stream().sorted().toList());

            assertThat(queryStrings(connection,
                    "SELECT table_name || '.' || column_name FROM information_schema.columns "
                            + "WHERE table_schema = 'banking' AND (table_name, column_name) IN ("
                            + "('device','fido_public_key_cose'),('device','fido_user_handle'),"
                            + "('device','fido_sign_count'),('auth_challenge','fido_nonce'),"
                            + "('app_user','role_assignment_version')) ORDER BY 1"))
                    .containsExactly(
                            "app_user.role_assignment_version",
                            "auth_challenge.fido_nonce",
                            "device.fido_public_key_cose",
                            "device.fido_sign_count",
                            "device.fido_user_handle");

            assertThat(queryStrings(connection,
                    "SELECT conname FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace "
                            + "WHERE n.nspname = 'banking' ORDER BY conname"))
                    .contains(
                            "app_user_pkey", "uq_app_user_username",
                            "fk_session_user", "fk_session_device",
                            "fk_auth_challenge_user", "fk_auth_challenge_device",
                            "pk_user_role", "pk_role_permission",
                            "ck_auth_challenge_attempts_within_max",
                            "ck_device_fido_sign_count",
                            "ck_app_user_role_assignment_version");

            assertThat(queryStrings(connection,
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'banking' ORDER BY indexname"))
                    .contains(
                            "uq_session_token_hash", "uq_auth_challenge_ref",
                            "uq_auth_challenge_active_stepup", "ix_auth_challenge_expires",
                            "ix_session_user_status", "ix_role_permission_permission");

            assertThat(singleString(connection,
                    "SELECT column_default FROM information_schema.columns "
                            + "WHERE table_schema='banking' AND table_name='app_user' "
                            + "AND column_name='role_assignment_version'"))
                    .isEqualTo("0");
            assertThat(singleString(connection,
                    "SELECT column_default FROM information_schema.columns "
                            + "WHERE table_schema='banking' AND table_name='device' "
                            + "AND column_name='fido_sign_count'"))
                    .isEqualTo("0");
            assertThat(singleString(connection,
                    "SELECT value FROM public.be_id_test_02_sentinel WHERE id=1"))
                    .isEqualTo("preserve-me");

            List<String> beforeRerun = schemaFingerprint(connection);
            var second = flyway.migrate();
            assertThat(second.migrationsExecuted).isZero();
            assertThat(schemaFingerprint(connection)).containsExactlyElementsOf(beforeRerun);
            assertThat(singleString(connection,
                    "SELECT value FROM public.be_id_test_02_sentinel WHERE id=1"))
                    .isEqualTo("preserve-me");
        }
    }

    private static List<String> schemaFingerprint(Connection connection) throws SQLException {
        return queryStrings(connection, "SELECT item FROM ("
                + "SELECT 'T|' || table_name AS item FROM information_schema.tables "
                + "WHERE table_schema='banking' UNION ALL "
                + "SELECT 'C|' || table_name || '|' || column_name || '|' || data_type || '|' "
                + "|| is_nullable || '|' || coalesce(column_default, '') FROM information_schema.columns "
                + "WHERE table_schema='banking' UNION ALL "
                + "SELECT 'K|' || c.conname || '|' || pg_get_constraintdef(c.oid) "
                + "FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace "
                + "WHERE n.nspname='banking' UNION ALL "
                + "SELECT 'I|' || indexname || '|' || indexdef FROM pg_indexes WHERE schemaname='banking'"
                + ") schema_items ORDER BY item");
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int singleInt(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static String singleString(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static List<String> queryStrings(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private static String quoted(List<String> values) {
        return values.stream().map(value -> "'" + value + "'").reduce((left, right) -> left + "," + right)
                .orElseThrow();
    }
}
