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

        assertThat(first.migrationsExecuted).isEqualTo(22);
        try (Connection connection = connection()) {
            assertThat(queryStrings(connection,
                    "SELECT version FROM banking.flyway_schema_history "
                            + "WHERE version IS NOT NULL AND success ORDER BY installed_rank"))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11",
                            "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22");
            assertThat(queryStrings(connection,
                    "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = 'banking' AND table_name = 'account'"))
                    .containsExactly("account");
            assertThat(queryStrings(connection,
                    "SELECT conname FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace "
                            + "WHERE n.nspname = 'banking' AND c.conrelid = 'banking.account'::regclass "
                            + "ORDER BY conname"))
                    .contains("account_pkey", "fk_account_customer", "uq_account_no_hash",
                            "ck_account_status", "ck_account_currency",
                            "ck_account_available_balance_non_negative",
                            "ck_account_ledger_balance_non_negative",
                            "ck_account_balance_within_ledger", "ck_account_closed_at_required");
            assertThat(queryStrings(connection,
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'banking' "
                            + "AND tablename = 'account' ORDER BY indexname"))
                    .containsExactly("account_pkey", "ix_account_customer", "ix_account_status",
                            "uq_account_no_hash");
            assertThat(queryStrings(connection,
                    "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = 'banking' AND table_name = 'approval_request'"))
                    .containsExactly("approval_request");
            assertThat(queryStrings(connection,
                    "SELECT conname FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace "
                            + "WHERE n.nspname = 'banking' AND c.conrelid = 'banking.approval_request'::regclass "
                            + "ORDER BY conname"))
                    .contains("approval_request_pkey", "fk_approval_maker", "fk_approval_checker",
                            "ck_approval_maker_checker", "ck_approval_decided_fields", "ck_approval_applied_at");
            assertThat(queryStrings(connection,
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'banking' "
                            + "AND tablename = 'approval_request' ORDER BY indexname"))
                    .contains("ix_approval_status_time", "ix_approval_type_status", "ix_approval_target",
                            "ix_approval_maker", "ix_approval_checker");
            assertThat(queryStrings(connection,
                    "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = 'banking' AND table_name = 'admin_idempotency_record'"))
                    .containsExactly("admin_idempotency_record");
            assertThat(queryStrings(connection,
                    "SELECT conname FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace "
                            + "WHERE n.nspname = 'banking' "
                            + "AND c.conrelid = 'banking.admin_idempotency_record'::regclass ORDER BY conname"))
                    .contains("admin_idempotency_record_pkey", "fk_admin_idempotency_actor",
                            "fk_admin_idempotency_approval", "ck_admin_idempotency_key_len",
                            "ck_admin_idempotency_operation", "ck_admin_idempotency_status",
                            "ck_admin_idempotency_terminal_fields", "ck_admin_idempotency_received_fields",
                            "ck_admin_idempotency_expiry");
            assertThat(queryStrings(connection,
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'banking' "
                            + "AND tablename = 'admin_idempotency_record' ORDER BY indexname"))
                    .contains("uq_admin_idempotency_scope", "ix_admin_idempotency_expires",
                            "ix_admin_idempotency_status", "ix_admin_idempotency_approval");
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
