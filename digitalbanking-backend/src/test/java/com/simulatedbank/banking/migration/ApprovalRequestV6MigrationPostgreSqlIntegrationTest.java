package com.simulatedbank.banking.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-ADM-DB-01: verifies V6 against the canonical {@code banking.approval_request} DDL
 * (04 §4.5.3) on a fresh, disposable PostgreSQL 16 database.
 */
@Testcontainers
class ApprovalRequestV6MigrationPostgreSqlIntegrationTest {

    private static final String INSERT_REQUIRED_COLUMNS = """
            INSERT INTO banking.approval_request
                (request_type, target_type, target_id, payload, maker_id, correlation_id)
            """;

    private static final int EXPECTED_MIGRATIONS = 22;

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("admin_v6");

    private static Flyway flyway;
    private static MigrateResult migration;

    @BeforeAll
    static void migrateFreshDatabase() {
        postgres.start();
        flyway = PostgreSqlIntegrationTestSupport.flyway(postgres);
        migration = flyway.migrate();
    }

    @Test
    void freshMigrationAppliesV6OnceAndRecordsSuccessfulHistory() throws Exception {
        assertThat(migration.migrationsExecuted).isEqualTo(EXPECTED_MIGRATIONS);
        try (Connection connection = connection()) {
            assertThat(queryInt(connection,
                    "SELECT count(*) FROM banking.flyway_schema_history "
                            + "WHERE version = '6' AND success = true"))
                    .isEqualTo(1);
            assertThat(queryStrings(connection,
                    "SELECT version FROM banking.flyway_schema_history "
                            + "WHERE version IS NOT NULL GROUP BY version HAVING count(*) > 1"))
                    .isEmpty();
            assertThat(queryStrings(connection,
                    "SELECT version FROM banking.flyway_schema_history "
                            + "WHERE version IS NOT NULL AND success ORDER BY installed_rank"))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11",
                            "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22");
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("22");
            assertThat(queryInt(connection,
                    "SELECT count(*) FROM banking.flyway_schema_history "
                            + "WHERE version IS NOT NULL AND (NOT success OR checksum IS NULL)"))
                    .isZero();
        }
    }

    @Test
    void columnsTypesLengthsNullabilityAndDefaultsMatchCanonicalSchema() throws Exception {
        try (Connection connection = connection()) {
            List<Map<String, Object>> columns = queryRows(connection,
                    "SELECT column_name, data_type, character_maximum_length, is_nullable, "
                            + "column_default, is_identity, identity_generation "
                            + "FROM information_schema.columns "
                            + "WHERE table_schema = 'banking' AND table_name = 'approval_request' "
                            + "ORDER BY ordinal_position");

            assertThat(columns).extracting(row -> row.get("column_name")).containsExactly(
                    "approval_id", "request_type", "target_type", "target_id", "payload",
                    "status", "maker_id", "checker_id", "decision_note", "correlation_id",
                    "created_at", "decided_at", "applied_at");

            assertThat(dataType(columns, "approval_id")).isEqualTo("bigint");
            assertThat(dataType(columns, "request_type")).isEqualTo("character varying");
            assertThat(dataType(columns, "target_type")).isEqualTo("character varying");
            assertThat(dataType(columns, "target_id")).isEqualTo("character varying");
            assertThat(dataType(columns, "payload")).isEqualTo("jsonb");
            assertThat(dataType(columns, "status")).isEqualTo("character varying");
            assertThat(dataType(columns, "maker_id")).isEqualTo("bigint");
            assertThat(dataType(columns, "checker_id")).isEqualTo("bigint");
            assertThat(dataType(columns, "decision_note")).isEqualTo("character varying");
            assertThat(dataType(columns, "correlation_id")).isEqualTo("character varying");
            assertThat(dataType(columns, "created_at")).isEqualTo("timestamp with time zone");
            assertThat(dataType(columns, "decided_at")).isEqualTo("timestamp with time zone");
            assertThat(dataType(columns, "applied_at")).isEqualTo("timestamp with time zone");

            assertThat(maxLength(columns, "request_type")).isEqualTo(32);
            assertThat(maxLength(columns, "target_type")).isEqualTo(32);
            assertThat(maxLength(columns, "target_id")).isEqualTo(64);
            assertThat(maxLength(columns, "status")).isEqualTo(16);
            assertThat(maxLength(columns, "decision_note")).isEqualTo(500);
            assertThat(maxLength(columns, "correlation_id")).isEqualTo(64);
            assertThat(maxLength(columns, "payload")).isNull();

            assertThat(columns.stream()
                    .filter(row -> "YES".equals(row.get("is_nullable")))
                    .map(row -> row.get("column_name")))
                    .containsExactlyInAnyOrder(
                            "checker_id", "decision_note", "decided_at", "applied_at");

            assertThat(column(columns, "approval_id").get("is_identity")).isEqualTo("YES");
            assertThat(column(columns, "approval_id").get("identity_generation")).isEqualTo("ALWAYS");
            assertThat(column(columns, "status").get("column_default"))
                    .isEqualTo("'PENDING'::character varying");
            assertThat(column(columns, "created_at").get("column_default")).isEqualTo("now()");
            assertThat(column(columns, "approval_id").get("column_default")).isNull();
            assertThat(columns.stream()
                    .filter(row -> !List.of("status", "created_at").contains(row.get("column_name")))
                    .map(row -> row.get("column_default")))
                    .containsOnlyNulls();
        }
    }

    @Test
    void primaryKeyForeignKeysAndCanonicalChecksMatch() throws Exception {
        try (Connection connection = connection()) {
            assertThat(queryStrings(connection,
                    "SELECT a.attname FROM pg_constraint c "
                            + "JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON true "
                            + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum "
                            + "WHERE c.conrelid = 'banking.approval_request'::regclass "
                            + "AND c.contype = 'p' ORDER BY k.ord"))
                    .containsExactly("approval_id");

            List<Map<String, Object>> foreignKeys = queryRows(connection, """
                    SELECT c.conname, ns.nspname AS referenced_schema, r.relname AS referenced_table,
                           a.attname AS local_column, ra.attname AS referenced_column,
                           c.confdeltype::text AS delete_action, c.confupdtype::text AS update_action
                    FROM pg_constraint c
                    JOIN pg_class r ON r.oid = c.confrelid
                    JOIN pg_namespace ns ON ns.oid = r.relnamespace
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                    JOIN pg_attribute ra ON ra.attrelid = c.confrelid AND ra.attnum = c.confkey[1]
                    WHERE c.conrelid = 'banking.approval_request'::regclass AND c.contype = 'f'
                    ORDER BY c.conname
                    """);
            assertThat(foreignKeys).extracting(row -> row.get("conname"))
                    .containsExactly("fk_approval_checker", "fk_approval_maker");
            for (Map<String, Object> foreignKey : foreignKeys) {
                assertThat(foreignKey).containsEntry("referenced_schema", "banking")
                        .containsEntry("referenced_table", "app_user")
                        .containsEntry("referenced_column", "user_id")
                        .containsEntry("delete_action", "r")
                        .containsEntry("update_action", "r");
            }
            assertThat(foreignKeys).extracting(row -> row.get("local_column"))
                    .containsExactly("checker_id", "maker_id");

            List<Map<String, Object>> checks = queryRows(connection, """
                    SELECT conname, pg_get_constraintdef(oid) AS definition
                    FROM pg_constraint
                    WHERE conrelid = 'banking.approval_request'::regclass AND contype = 'c'
                    ORDER BY conname
                    """);
            assertThat(checks).extracting(row -> row.get("conname")).containsExactly(
                    "ck_approval_applied_at", "ck_approval_correlation_id",
                    "ck_approval_decided_fields", "ck_approval_maker_checker",
                    "ck_approval_request_type", "ck_approval_status",
                    "ck_approval_target_id", "ck_approval_target_type");

            assertThat(checkValues(connection, "ck_approval_request_type"))
                    .containsExactlyInAnyOrder(
                            "LIMIT_CHANGE", "ROLE_CHANGE", "RETENTION_HOLD", "RETENTION_POLICY_CHANGE");
            assertThat(checkValues(connection, "ck_approval_target_type"))
                    .containsExactlyInAnyOrder(
                            "CUSTOMER", "ACCOUNT", "APP_USER", "DATA_SCOPE", "RETENTION_POLICY");
            assertThat(checkValues(connection, "ck_approval_status"))
                    .containsExactlyInAnyOrder(
                            "PENDING", "APPROVED", "REJECTED", "APPLIED", "CANCELLED");

            assertThat(definition(checks, "ck_approval_target_id"))
                    .contains("length").contains("target_id").contains("64");
            assertThat(definition(checks, "ck_approval_correlation_id"))
                    .contains("length").contains("correlation_id").contains("64");
            assertThat(definition(checks, "ck_approval_maker_checker"))
                    .contains("checker_id IS NULL").contains("checker_id <> maker_id");
            assertThat(definition(checks, "ck_approval_decided_fields"))
                    .contains("APPROVED", "REJECTED", "APPLIED")
                    .contains("checker_id IS NOT NULL").contains("decided_at IS NOT NULL");
            assertThat(definition(checks, "ck_approval_applied_at"))
                    .contains("APPLIED").contains("applied_at IS NOT NULL");
        }
    }

    @Test
    void canonicalIndexesExistInOrderWithoutPartialPredicates() throws Exception {
        try (Connection connection = connection()) {
            List<Map<String, Object>> indexes = queryRows(connection, """
                    SELECT i.indexname, i.indexdef, x.indisunique AS is_unique,
                           (x.indpred IS NOT NULL) AS is_partial
                    FROM pg_indexes i
                    JOIN pg_class c ON c.relname = i.indexname
                    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = i.schemaname
                    JOIN pg_index x ON x.indexrelid = c.oid
                    WHERE i.schemaname = 'banking' AND i.tablename = 'approval_request'
                    ORDER BY i.indexname
                    """);

            assertThat(indexes).extracting(row -> row.get("indexname")).containsExactly(
                    "approval_request_pkey", "ix_approval_checker", "ix_approval_maker",
                    "ix_approval_status_time", "ix_approval_target", "ix_approval_type_status");

            assertThat(index(indexes, "approval_request_pkey").get("is_unique")).isEqualTo(true);
            assertThat(index(indexes, "approval_request_pkey").get("is_partial")).isEqualTo(false);
            assertThat(indexdef(indexes, "approval_request_pkey"))
                    .contains("USING btree (approval_id)");

            for (String name : List.of("ix_approval_checker", "ix_approval_maker",
                    "ix_approval_status_time", "ix_approval_target", "ix_approval_type_status")) {
                assertThat(index(indexes, name).get("is_unique"))
                        .as("%s must not be unique", name).isEqualTo(false);
                assertThat(index(indexes, name).get("is_partial"))
                        .as("%s must not be partial", name).isEqualTo(false);
            }
            assertThat(indexdef(indexes, "ix_approval_status_time"))
                    .contains("USING btree (status, created_at)");
            assertThat(indexdef(indexes, "ix_approval_type_status"))
                    .contains("USING btree (request_type, status)");
            assertThat(indexdef(indexes, "ix_approval_target"))
                    .contains("USING btree (target_type, target_id)");
            assertThat(indexdef(indexes, "ix_approval_maker"))
                    .contains("USING btree (maker_id)");
            assertThat(indexdef(indexes, "ix_approval_checker"))
                    .contains("USING btree (checker_id)");

            // 04 §4.5.3 defines no partial index; ADM-OI-03 (PENDING uniqueness) is not approved.
            assertThat(indexes).allSatisfy(row -> assertThat(row.get("is_partial")).isEqualTo(false));
            assertThat(indexes).extracting(row -> String.valueOf(row.get("indexname")))
                    .noneMatch(name -> name.startsWith("uq_"));
        }
    }

    @Test
    void validInsertUsesCanonicalDefaultsAndTimestamptzSemantics() throws Exception {
        try (Connection connection = connection()) {
            long maker = insertUser(connection);
            long approvalId = insertPending(connection, maker);

            Map<String, Object> stored = queryRow(connection, """
                    SELECT status, pg_typeof(payload)::text AS payload_type,
                           pg_typeof(created_at)::text AS created_type,
                           created_at IS NOT NULL AS created_set,
                           created_at > now() - interval '5 minutes' AS recent,
                           decided_at, applied_at, checker_id
                    FROM banking.approval_request WHERE approval_id = ?
                    """, approvalId);

            assertThat(approvalId).isPositive();
            assertThat(stored).containsEntry("status", "PENDING")
                    .containsEntry("payload_type", "jsonb")
                    .containsEntry("created_type", "timestamp with time zone")
                    .containsEntry("created_set", true)
                    .containsEntry("recent", true)
                    .containsEntry("decided_at", null)
                    .containsEntry("applied_at", null)
                    .containsEntry("checker_id", null);

            long explicitId = queryLong(connection, """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, maker_id, correlation_id, created_at)
                    VALUES ('ROLE_CHANGE', 'APP_USER', '42', '{}'::jsonb, ?, ?, 
                            '2026-11-17T10:00:00+07:00'::timestamptz)
                    RETURNING approval_id
                    """, maker, "corr-" + UUID.randomUUID());
            assertThat(queryBoolean(connection, """
                    SELECT created_at = '2026-11-17T03:00:00Z'::timestamptz
                    FROM banking.approval_request WHERE approval_id = ?
                    """, explicitId)).isTrue();
        }
    }

    @Test
    void canonicalTerminalStatesAndTimestampInvariantsAreAccepted() throws Exception {
        try (Connection connection = connection()) {
            long maker = insertUser(connection);
            long checker = insertUser(connection);

            insertTerminal(connection, maker, checker, "APPROVED", false);
            insertTerminal(connection, maker, checker, "REJECTED", false);
            insertTerminal(connection, maker, checker, "APPLIED", true);
            long cancelled = queryLong(connection, """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, status, maker_id, correlation_id)
                    VALUES ('RETENTION_HOLD', 'DATA_SCOPE', 'scope-1', '{}'::jsonb, 'CANCELLED', ?, ?)
                    RETURNING approval_id
                    """, maker, "corr-" + UUID.randomUUID());
            assertThat(cancelled).isPositive();

            assertThat(queryStrings(connection, """
                    SELECT status FROM banking.approval_request
                    WHERE approval_id IN (SELECT approval_id FROM banking.approval_request
                                          WHERE maker_id = ?) ORDER BY status
                    """, maker))
                    .containsExactly("APPLIED", "APPROVED", "CANCELLED", "REJECTED");
        }
    }

    @Test
    void missingRequiredColumnsAndInvalidEnumerationsAreRejected() throws Exception {
        try (Connection connection = connection()) {
            long maker = insertUser(connection);
            String correlation = "corr-" + UUID.randomUUID();

            assertRejected(connection, "23502", "payload", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, maker_id, correlation_id)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', ?, ?)
                    """, maker, correlation);
            assertRejected(connection, "23502", "maker_id", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, correlation_id)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, ?)
                    """, correlation);
            assertRejected(connection, "23502", "correlation_id", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, maker_id)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, ?)
                    """, maker);
            assertRejected(connection, "23502", "target_id", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, payload, maker_id, correlation_id)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '{}'::jsonb, ?, ?)
                    """, maker, correlation);
            assertRejected(connection, "23514", "ck_approval_request_type", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, maker_id, correlation_id)
                    VALUES ('TRANSFER', 'CUSTOMER', '2001', '{}'::jsonb, ?, ?)
                    """, maker, correlation);
            assertRejected(connection, "23514", "ck_approval_target_type", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, maker_id, correlation_id)
                    VALUES ('LIMIT_CHANGE', 'TRANSFER', '2001', '{}'::jsonb, ?, ?)
                    """, maker, correlation);
            assertRejected(connection, "23514", "ck_approval_status", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, status, maker_id, correlation_id)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, 'EXPIRED', ?, ?)
                    """, maker, correlation);
            assertRejected(connection, "23514", "ck_approval_status", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, status, maker_id, correlation_id)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, 'CONSUMED', ?, ?)
                    """, maker, correlation);
        }
    }

    @Test
    void invalidForeignKeysAndMakerCheckerRelationshipsAreRejected() throws Exception {
        try (Connection connection = connection()) {
            long maker = insertUser(connection);
            long checker = insertUser(connection);
            String correlation = "corr-" + UUID.randomUUID();

            assertRejected(connection, "23503", "fk_approval_maker", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, maker_id, correlation_id)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, ?, ?)
                    """, Long.MAX_VALUE, correlation);
            assertRejected(connection, "23503", "fk_approval_checker", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, status, maker_id, checker_id,
                         correlation_id, decided_at)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, 'REJECTED', ?, ?, ?, now())
                    """, maker, Long.MAX_VALUE, correlation);
            assertRejected(connection, "23514", "ck_approval_maker_checker", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, maker_id, checker_id, correlation_id)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, ?, ?, ?)
                    """, maker, maker, correlation);
            assertRejected(connection, "23514", "ck_approval_decided_fields", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, status, maker_id, checker_id,
                         correlation_id, decided_at)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, 'APPROVED', ?, NULL, ?, now())
                    """, maker, correlation);
            assertRejected(connection, "23514", "ck_approval_decided_fields", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, status, maker_id, checker_id,
                         correlation_id, decided_at)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, 'REJECTED', ?, ?, ?, NULL)
                    """, maker, checker, correlation);
            assertRejected(connection, "23514", "ck_approval_applied_at", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, status, maker_id, checker_id,
                         correlation_id, decided_at, applied_at)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, 'APPLIED', ?, ?, ?, now(), NULL)
                    """, maker, checker, correlation);
        }
    }

    @Test
    void canonicalLengthBoundsAndCheckConstraintsAreEnforced() throws Exception {
        try (Connection connection = connection()) {
            long maker = insertUser(connection);
            String correlation = "corr-" + UUID.randomUUID();

            assertRejected(connection, "23514", "ck_approval_target_id", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, maker_id, correlation_id)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '', '{}'::jsonb, ?, ?)
                    """, maker, correlation);
            assertRejected(connection, "22001", "character varying(64)", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, maker_id, correlation_id)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', ?, '{}'::jsonb, ?, ?)
                    """, "x".repeat(65), maker, correlation);
            assertRejected(connection, "23514", "ck_approval_correlation_id", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, maker_id, correlation_id)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, ?, '')
                    """, maker);
            assertRejected(connection, "22001", "character varying(64)", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, maker_id, correlation_id)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, ?, ?)
                    """, maker, "x".repeat(65));
            assertRejected(connection, "22001", "character varying(500)", """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, maker_id, correlation_id, decision_note)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, ?, ?, ?)
                    """, maker, correlation, "x".repeat(501));

            long maxNote = queryLong(connection, """
                    INSERT INTO banking.approval_request
                        (request_type, target_type, target_id, payload, maker_id, correlation_id, decision_note)
                    VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, ?, ?, ?)
                    RETURNING approval_id
                    """, maker, correlation, "x".repeat(500));
            assertThat(maxNote).isPositive();
        }
    }

    @Test
    void foreignKeysRestrictReferencedUserDeleteAndKeyUpdate() throws Exception {
        try (Connection connection = connection()) {
            long maker = insertUser(connection);
            insertPending(connection, maker);

            SQLException delete = sqlFailure(connection,
                    "DELETE FROM banking.app_user WHERE user_id = ?", maker);
            assertThat(delete.getSQLState()).isEqualTo("23503");
            assertThat(delete.getMessage()).contains("fk_approval_maker");

            SQLException update = sqlFailure(connection,
                    "UPDATE banking.app_user SET user_id = DEFAULT WHERE user_id = ?", maker);
            assertThat(update.getSQLState()).isEqualTo("23503");
            assertThat(update.getMessage()).contains("fk_approval_maker");

            assertThat(queryInt(connection,
                    "SELECT count(*) FROM banking.app_user WHERE user_id = ?", maker)).isEqualTo(1);
        }
    }

    private static long insertPending(Connection connection, long maker) throws SQLException {
        return queryLong(connection, INSERT_REQUIRED_COLUMNS + """
                VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, ?, ?)
                RETURNING approval_id
                """, maker, "corr-" + UUID.randomUUID());
    }

    private static long insertTerminal(
            Connection connection, long maker, long checker, String status, boolean applied) throws SQLException {
        return queryLong(connection,
                "INSERT INTO banking.approval_request "
                        + "(request_type, target_type, target_id, payload, status, maker_id, checker_id, "
                        + "correlation_id, decided_at, applied_at) "
                        + "VALUES ('LIMIT_CHANGE', 'CUSTOMER', '2001', '{}'::jsonb, ?, ?, ?, ?, now(), ?) "
                        + "RETURNING approval_id",
                status, maker, checker, "corr-" + UUID.randomUUID(),
                applied ? java.time.OffsetDateTime.now() : null);
    }

    private static long insertUser(Connection connection) throws SQLException {
        return queryLong(connection,
                "INSERT INTO banking.app_user (username, password_hash, user_type) "
                        + "VALUES (?, ?, 'STAFF') RETURNING user_id",
                "adm_db01_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                "x".repeat(60));
    }

    private static void assertRejected(
            Connection connection, String sqlState, String messageFragment, String sql, Object... params) {
        SQLException failure = sqlFailure(connection, sql, params);
        assertThat(failure.getSQLState()).as("SQLSTATE for: %s", sql).isEqualTo(sqlState);
        assertThat(failure.getMessage()).as("failure message for: %s", sql).contains(messageFragment);
    }

    private static SQLException sqlFailure(Connection connection, String sql, Object... params) {
        try {
            executeUpdate(connection, sql, params);
        } catch (SQLException failure) {
            return failure;
        }
        throw new AssertionError("Expected SQL statement to fail: " + sql);
    }

    private static void executeUpdate(Connection connection, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            statement.executeUpdate();
        }
    }

    private static long queryLong(Connection connection, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static boolean queryBoolean(Connection connection, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    private static int queryInt(Connection connection, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getInt(1);
            }
        }
    }

    private static List<String> queryStrings(Connection connection, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet result = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (result.next()) {
                    values.add(result.getString(1));
                }
                return values;
            }
        }
    }

    private static Map<String, Object> queryRow(Connection connection, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                ResultSetMetaData metadata = result.getMetaData();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    row.put(metadata.getColumnLabel(index), result.getObject(index));
                }
                return row;
            }
        }
    }

    private static List<Map<String, Object>> queryRows(Connection connection, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData metadata = result.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        row.put(metadata.getColumnLabel(index), result.getObject(index));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int index = 0; index < params.length; index++) {
            if (params[index] == null) {
                statement.setNull(index + 1, Types.OTHER);
            } else {
                statement.setObject(index + 1, params[index]);
            }
        }
    }

    private static List<String> checkValues(Connection connection, String constraint) throws SQLException {
        return queryStrings(connection, """
                SELECT m.value[1]
                FROM pg_constraint c,
                     regexp_matches(pg_get_constraintdef(c.oid), '''([A-Z_]+)''', 'g') AS m(value)
                WHERE c.conrelid = 'banking.approval_request'::regclass AND c.conname = ?
                ORDER BY 1
                """, constraint);
    }

    private static Map<String, Object> column(List<Map<String, Object>> columns, String name) {
        return columns.stream()
                .filter(row -> name.equals(row.get("column_name")))
                .findFirst()
                .orElseThrow();
    }

    private static String dataType(List<Map<String, Object>> columns, String name) {
        return String.valueOf(column(columns, name).get("data_type"));
    }

    private static Integer maxLength(List<Map<String, Object>> columns, String name) {
        return (Integer) column(columns, name).get("character_maximum_length");
    }

    private static String definition(List<Map<String, Object>> checks, String name) {
        return String.valueOf(checks.stream()
                .filter(row -> name.equals(row.get("conname")))
                .map(row -> row.get("definition"))
                .findFirst()
                .orElseThrow());
    }

    private static Map<String, Object> index(List<Map<String, Object>> indexes, String name) {
        return indexes.stream()
                .filter(row -> name.equals(row.get("indexname")))
                .findFirst()
                .orElseThrow();
    }

    private static String indexdef(List<Map<String, Object>> indexes, String name) {
        return String.valueOf(index(indexes, name).get("indexdef"));
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
