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
 * BE-ADM-DB-03: verifies V7 against the canonical {@code banking.admin_idempotency_record} DDL
 * (04 §4.5.4, design/02 §6, DEC-ADM-003) on a fresh, disposable PostgreSQL 16 database.
 *
 * <p>The unique index {@code uq_admin_idempotency_scope} is the only arbiter for concurrent Admin writes, so its
 * exact column order and uniqueness are asserted as schema facts, not inferred from application code.</p>
 */
@Testcontainers
class AdminIdempotencyV7MigrationPostgreSqlIntegrationTest {

    private static final int EXPECTED_MIGRATIONS = 23;
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("admin_v7");

    private static Flyway flyway;
    private static MigrateResult migration;

    @BeforeAll
    static void migrateFreshDatabase() {
        postgres.start();
        flyway = PostgreSqlIntegrationTestSupport.flyway(postgres);
        migration = flyway.migrate();
    }

    @Test
    void freshMigrationAppliesV7OnceAndRecordsSuccessfulHistory() throws Exception {
        assertThat(migration.migrationsExecuted).isEqualTo(EXPECTED_MIGRATIONS);
        try (Connection connection = connection()) {
            assertThat(queryInt(connection,
                    "SELECT count(*) FROM banking.flyway_schema_history "
                            + "WHERE version = '7' AND success = true"))
                    .isEqualTo(1);
            assertThat(queryStrings(connection,
                    "SELECT version FROM banking.flyway_schema_history "
                            + "WHERE version IS NOT NULL GROUP BY version HAVING count(*) > 1"))
                    .isEmpty();
            assertThat(queryStrings(connection,
                    "SELECT version FROM banking.flyway_schema_history "
                            + "WHERE version IS NOT NULL AND success ORDER BY installed_rank"))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11",
                            "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23");
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("23");
            // Re-running the chain must not execute V7 again and must keep the recorded checksum.
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(queryInt(connection,
                    "SELECT count(*) FROM banking.flyway_schema_history "
                            + "WHERE version = '7' AND (NOT success OR checksum IS NULL)"))
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
                            + "WHERE table_schema = 'banking' AND table_name = 'admin_idempotency_record' "
                            + "ORDER BY ordinal_position");

            assertThat(columns).extracting(row -> row.get("column_name")).containsExactly(
                    "record_id", "idempotency_key", "actor_id", "operation", "scope_key", "request_hash",
                    "status", "response_status", "response_body", "approval_id", "created_at",
                    "completed_at", "expires_at");

            assertThat(dataType(columns, "record_id")).isEqualTo("bigint");
            assertThat(dataType(columns, "idempotency_key")).isEqualTo("character varying");
            assertThat(dataType(columns, "actor_id")).isEqualTo("bigint");
            assertThat(dataType(columns, "operation")).isEqualTo("character varying");
            assertThat(dataType(columns, "scope_key")).isEqualTo("character varying");
            assertThat(dataType(columns, "request_hash")).isEqualTo("character");
            assertThat(dataType(columns, "status")).isEqualTo("character varying");
            assertThat(dataType(columns, "response_status")).isEqualTo("smallint");
            assertThat(dataType(columns, "response_body")).isEqualTo("jsonb");
            assertThat(dataType(columns, "approval_id")).isEqualTo("bigint");
            assertThat(dataType(columns, "created_at")).isEqualTo("timestamp with time zone");
            assertThat(dataType(columns, "completed_at")).isEqualTo("timestamp with time zone");
            assertThat(dataType(columns, "expires_at")).isEqualTo("timestamp with time zone");

            assertThat(maxLength(columns, "idempotency_key")).isEqualTo(255);
            assertThat(maxLength(columns, "operation")).isEqualTo(32);
            assertThat(maxLength(columns, "scope_key")).isEqualTo(64);
            assertThat(maxLength(columns, "request_hash")).isEqualTo(64);
            assertThat(maxLength(columns, "status")).isEqualTo(16);
            assertThat(maxLength(columns, "response_status")).isNull();
            assertThat(maxLength(columns, "response_body")).isNull();

            assertThat(columns.stream()
                    .filter(row -> "YES".equals(row.get("is_nullable")))
                    .map(row -> row.get("column_name")))
                    .containsExactlyInAnyOrder(
                            "response_status", "response_body", "approval_id", "completed_at");

            assertThat(column(columns, "record_id").get("is_identity")).isEqualTo("YES");
            assertThat(column(columns, "record_id").get("identity_generation")).isEqualTo("ALWAYS");
            assertThat(column(columns, "record_id").get("column_default")).isNull();
            assertThat(column(columns, "status").get("column_default"))
                    .isEqualTo("'RECEIVED'::character varying");
            assertThat(column(columns, "scope_key").get("column_default"))
                    .isEqualTo("''::character varying");
            assertThat(column(columns, "created_at").get("column_default")).isEqualTo("now()");
            assertThat(columns.stream()
                    .filter(row -> !List.of("status", "scope_key", "created_at")
                            .contains(row.get("column_name")))
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
                            + "WHERE c.conrelid = 'banking.admin_idempotency_record'::regclass "
                            + "AND c.contype = 'p' ORDER BY k.ord"))
                    .containsExactly("record_id");

            List<Map<String, Object>> foreignKeys = queryRows(connection, """
                    SELECT c.conname, ns.nspname AS referenced_schema, r.relname AS referenced_table,
                           a.attname AS local_column, ra.attname AS referenced_column,
                           c.confdeltype::text AS delete_action, c.confupdtype::text AS update_action
                    FROM pg_constraint c
                    JOIN pg_class r ON r.oid = c.confrelid
                    JOIN pg_namespace ns ON ns.oid = r.relnamespace
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                    JOIN pg_attribute ra ON ra.attrelid = c.confrelid AND ra.attnum = c.confkey[1]
                    WHERE c.conrelid = 'banking.admin_idempotency_record'::regclass AND c.contype = 'f'
                    ORDER BY c.conname
                    """);
            assertThat(foreignKeys).extracting(row -> row.get("conname"))
                    .containsExactly("fk_admin_idempotency_actor", "fk_admin_idempotency_approval");
            assertThat(foreignKeys).extracting(row -> row.get("local_column"))
                    .containsExactly("actor_id", "approval_id");
            assertThat(foreignKeys).extracting(row -> row.get("referenced_table"))
                    .containsExactly("app_user", "approval_request");
            assertThat(foreignKeys).extracting(row -> row.get("referenced_column"))
                    .containsExactly("user_id", "approval_id");
            for (Map<String, Object> foreignKey : foreignKeys) {
                assertThat(foreignKey).containsEntry("referenced_schema", "banking")
                        .containsEntry("delete_action", "r")
                        .containsEntry("update_action", "r");
            }

            List<Map<String, Object>> checks = queryRows(connection, """
                    SELECT conname, pg_get_constraintdef(oid) AS definition
                    FROM pg_constraint
                    WHERE conrelid = 'banking.admin_idempotency_record'::regclass AND contype = 'c'
                    ORDER BY conname
                    """);
            assertThat(checks).extracting(row -> row.get("conname")).containsExactly(
                    "ck_admin_idempotency_decision_scope", "ck_admin_idempotency_expiry",
                    "ck_admin_idempotency_key_len", "ck_admin_idempotency_operation",
                    "ck_admin_idempotency_received_fields", "ck_admin_idempotency_request_hash",
                    "ck_admin_idempotency_response_status", "ck_admin_idempotency_scope_len",
                    "ck_admin_idempotency_status", "ck_admin_idempotency_terminal_fields");

            assertThat(checkValues(connection, "ck_admin_idempotency_operation"))
                    .containsExactlyInAnyOrder(
                            "CREATE_LIMIT_PROPOSAL", "CREATE_ROLE_PROPOSAL", "DECIDE_APPROVAL");
            assertThat(checkValues(connection, "ck_admin_idempotency_status"))
                    .containsExactlyInAnyOrder("RECEIVED", "COMPLETED", "FAILED");
            assertThat(definition(checks, "ck_admin_idempotency_key_len"))
                    .contains("length").contains("idempotency_key").contains("255");
            assertThat(definition(checks, "ck_admin_idempotency_scope_len"))
                    .contains("length").contains("scope_key").contains("64");
            assertThat(definition(checks, "ck_admin_idempotency_request_hash"))
                    .contains("[0-9a-f]").contains("64");
            assertThat(definition(checks, "ck_admin_idempotency_response_status"))
                    .contains("100").contains("599");
            assertThat(definition(checks, "ck_admin_idempotency_expiry"))
                    .contains("expires_at > created_at");
            assertThat(definition(checks, "ck_admin_idempotency_received_fields"))
                    .contains("RECEIVED").contains("completed_at IS NULL")
                    .contains("response_status IS NULL").contains("response_body IS NULL");
            assertThat(definition(checks, "ck_admin_idempotency_terminal_fields"))
                    .contains("COMPLETED", "FAILED").contains("completed_at IS NOT NULL")
                    .contains("response_status IS NOT NULL").contains("response_body IS NOT NULL");
            assertThat(definition(checks, "ck_admin_idempotency_decision_scope"))
                    .contains("DECIDE_APPROVAL").contains("approval_id IS NOT NULL");
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
                    WHERE i.schemaname = 'banking' AND i.tablename = 'admin_idempotency_record'
                    ORDER BY i.indexname
                    """);

            assertThat(indexes).extracting(row -> row.get("indexname")).containsExactly(
                    "admin_idempotency_record_pkey", "ix_admin_idempotency_approval",
                    "ix_admin_idempotency_expires", "ix_admin_idempotency_status",
                    "uq_admin_idempotency_scope");

            assertThat(index(indexes, "admin_idempotency_record_pkey").get("is_unique")).isEqualTo(true);
            assertThat(indexdef(indexes, "admin_idempotency_record_pkey"))
                    .contains("USING btree (record_id)");

            Map<String, Object> uniqueScope = index(indexes, "uq_admin_idempotency_scope");
            assertThat(uniqueScope.get("is_unique")).isEqualTo(true);
            assertThat(uniqueScope.get("is_partial")).isEqualTo(false);
            assertThat(indexdef(indexes, "uq_admin_idempotency_scope"))
                    .contains("UNIQUE INDEX")
                    .contains("USING btree (actor_id, operation, scope_key, idempotency_key)");

            for (String name : List.of("ix_admin_idempotency_approval", "ix_admin_idempotency_expires",
                    "ix_admin_idempotency_status")) {
                assertThat(index(indexes, name).get("is_unique"))
                        .as("%s must not be unique", name).isEqualTo(false);
                assertThat(index(indexes, name).get("is_partial"))
                        .as("%s must not be partial", name).isEqualTo(false);
            }
            assertThat(indexdef(indexes, "ix_admin_idempotency_approval"))
                    .contains("USING btree (approval_id)");
            assertThat(indexdef(indexes, "ix_admin_idempotency_expires"))
                    .contains("USING btree (expires_at)");
            assertThat(indexdef(indexes, "ix_admin_idempotency_status"))
                    .contains("USING btree (status)");
            assertThat(indexes).allSatisfy(row -> assertThat(row.get("is_partial")).isEqualTo(false));
        }
    }

    @Test
    void canonicalInsertAppliesDefaultsAndRequiresExpiry() throws Exception {
        try (Connection connection = connection()) {
            long actor = insertUser(connection);
            long recordId = queryLong(connection, """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, request_hash, expires_at)
                    VALUES (?, ?, 'CREATE_ROLE_PROPOSAL', ?, now() + interval '24 hours')
                    RETURNING record_id
                    """, "adm-db03-" + UUID.randomUUID(), actor, HASH);

            Map<String, Object> stored = queryRow(connection, """
                    SELECT status, scope_key, approval_id, response_status, response_body, completed_at,
                           created_at IS NOT NULL AS created_set,
                           created_at > now() - interval '5 minutes' AS created_recent,
                           pg_typeof(response_body)::text AS response_type
                    FROM banking.admin_idempotency_record WHERE record_id = ?
                    """, recordId);

            assertThat(recordId).isPositive();
            assertThat(stored).containsEntry("status", "RECEIVED")
                    .containsEntry("scope_key", "")
                    .containsEntry("approval_id", null)
                    .containsEntry("response_status", null)
                    .containsEntry("response_body", null)
                    .containsEntry("completed_at", null)
                    .containsEntry("created_set", true)
                    .containsEntry("created_recent", true)
                    .containsEntry("response_type", "jsonb");

            // A terminal row is accepted only with its full replay payload.
            long approval = insertApproval(connection, actor);
            long completed = queryLong(connection, """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, scope_key, request_hash, status,
                         response_status, response_body, approval_id, completed_at, expires_at)
                    VALUES (?, ?, 'DECIDE_APPROVAL', ?, ?, 'COMPLETED', 200, '{"status":"APPLIED"}'::jsonb,
                            ?, now(), now() + interval '24 hours')
                    RETURNING record_id
                    """, "adm-db03-" + UUID.randomUUID(), actor, Long.toString(approval), HASH, approval);
            assertThat(completed).isPositive();

            // Timestamptz stores instants: an explicit +07:00 offset reads back as the same UTC instant.
            long offsetRecord = queryLong(connection, """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, request_hash, created_at, expires_at)
                    VALUES (?, ?, 'CREATE_ROLE_PROPOSAL', ?,
                            '2026-11-17T10:00:00+07:00'::timestamptz,
                            '2026-11-18T10:00:00+07:00'::timestamptz)
                    RETURNING record_id
                    """, "adm-db03-" + UUID.randomUUID(), actor, OTHER_HASH);
            assertThat(queryBoolean(connection, """
                    SELECT created_at = '2026-11-17T03:00:00Z'::timestamptz
                    FROM banking.admin_idempotency_record WHERE record_id = ?
                    """, offsetRecord)).isTrue();
        }
    }

    @Test
    void uniqueScopeIsActorOperationResourceAndKey() throws Exception {
        try (Connection connection = connection()) {
            long actor = insertUser(connection);
            long otherActor = insertUser(connection);
            long approval = insertApproval(connection, actor);
            long otherApproval = insertApproval(connection, actor);
            String key = "adm-db03-scope";

            insertRecord(connection, key, actor, "CREATE_ROLE_PROPOSAL", "", HASH);
            // Same key on another actor, another operation and another resource are distinct scopes.
            insertRecord(connection, key, otherActor, "CREATE_ROLE_PROPOSAL", "", HASH);
            insertRecord(connection, key, actor, "CREATE_LIMIT_PROPOSAL", "", HASH);
            insertRecord(connection, key, actor, "DECIDE_APPROVAL", Long.toString(approval), HASH);
            insertRecord(connection, key, actor, "DECIDE_APPROVAL", Long.toString(otherApproval), HASH);

            // Exactly the scope tuple is unique, regardless of the request hash.
            SQLException duplicate = sqlFailure(connection, """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, request_hash, expires_at)
                    VALUES (?, ?, 'CREATE_ROLE_PROPOSAL', ?, now() + interval '1 hour')
                    """, key, actor, OTHER_HASH);
            assertThat(duplicate.getSQLState()).isEqualTo("23505");
            assertThat(duplicate.getMessage()).contains("uq_admin_idempotency_scope");

            assertThat(queryInt(connection,
                    "SELECT count(*) FROM banking.admin_idempotency_record WHERE idempotency_key = ?", key))
                    .isEqualTo(5);
        }
    }

    @Test
    void unsafeStatesAreRejectedByConstraints() throws Exception {
        try (Connection connection = connection()) {
            long actor = insertUser(connection);

            assertRejected(connection, "23514", "ck_admin_idempotency_request_hash", """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, request_hash, expires_at)
                    VALUES (?, ?, 'CREATE_ROLE_PROPOSAL', 'not-a-hash', now() + interval '1 hour')
                    """, "adm-db03-" + UUID.randomUUID(), actor);
            assertRejected(connection, "23514", "ck_admin_idempotency_operation", """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, request_hash, expires_at)
                    VALUES (?, ?, 'DELETE_EVERYTHING', ?, now() + interval '1 hour')
                    """, "adm-db03-" + UUID.randomUUID(), actor, HASH);
            assertRejected(connection, "23514", "ck_admin_idempotency_status", """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, request_hash, status, expires_at)
                    VALUES (?, ?, 'CREATE_ROLE_PROPOSAL', ?, 'EXPIRED', now() + interval '1 hour')
                    """, "adm-db03-" + UUID.randomUUID(), actor, HASH);
            assertRejected(connection, "23514", "ck_admin_idempotency_key_len", """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, request_hash, expires_at)
                    VALUES ('', ?, 'CREATE_ROLE_PROPOSAL', ?, now() + interval '1 hour')
                    """, actor, HASH);
            assertRejected(connection, "22001", "character varying(255)", """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, request_hash, expires_at)
                    VALUES (?, ?, 'CREATE_ROLE_PROPOSAL', ?, now() + interval '1 hour')
                    """, "k".repeat(256), actor, HASH);
            assertRejected(connection, "23514", "ck_admin_idempotency_expiry", """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, request_hash, created_at, expires_at)
                    VALUES (?, ?, 'CREATE_ROLE_PROPOSAL', ?, now(), now())
                    """, "adm-db03-" + UUID.randomUUID(), actor, HASH);
            // Terminal rows must carry the exact replay response; RECEIVED rows must not.
            assertRejected(connection, "23514", "ck_admin_idempotency_terminal_fields", """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, request_hash, status, response_status,
                         completed_at, expires_at)
                    VALUES (?, ?, 'CREATE_ROLE_PROPOSAL', ?, 'COMPLETED', 201, now(),
                            now() + interval '1 hour')
                    """, "adm-db03-" + UUID.randomUUID(), actor, HASH);
            assertRejected(connection, "23514", "ck_admin_idempotency_terminal_fields", """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, request_hash, status, response_status,
                         response_body, expires_at)
                    VALUES (?, ?, 'CREATE_ROLE_PROPOSAL', ?, 'COMPLETED', 201, '{}'::jsonb,
                            now() + interval '1 hour')
                    """, "adm-db03-" + UUID.randomUUID(), actor, HASH);
            assertRejected(connection, "23514", "ck_admin_idempotency_received_fields", """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, request_hash, status, response_status,
                         response_body, expires_at)
                    VALUES (?, ?, 'CREATE_ROLE_PROPOSAL', ?, 'RECEIVED', 201, '{}'::jsonb,
                            now() + interval '1 hour')
                    """, "adm-db03-" + UUID.randomUUID(), actor, HASH);
            // A decision record must reference the approval it decided.
            assertRejected(connection, "23514", "ck_admin_idempotency_decision_scope", """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, scope_key, request_hash, expires_at)
                    VALUES (?, ?, 'DECIDE_APPROVAL', '99', ?, now() + interval '1 hour')
                    """, "adm-db03-" + UUID.randomUUID(), actor, HASH);
            assertRejected(connection, "23503", "fk_admin_idempotency_actor", """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, request_hash, expires_at)
                    VALUES (?, ?, 'CREATE_ROLE_PROPOSAL', ?, now() + interval '1 hour')
                    """, "adm-db03-" + UUID.randomUUID(), Long.MAX_VALUE, HASH);
            assertRejected(connection, "23503", "fk_admin_idempotency_approval", """
                    INSERT INTO banking.admin_idempotency_record
                        (idempotency_key, actor_id, operation, scope_key, request_hash, approval_id, expires_at)
                    VALUES (?, ?, 'DECIDE_APPROVAL', '1', ?, ?, now() + interval '1 hour')
                    """, "adm-db03-" + UUID.randomUUID(), actor, HASH, Long.MAX_VALUE);
        }
    }

    @Test
    void foreignKeysRestrictReferencedActorDeleteAndApprovalDelete() throws Exception {
        try (Connection connection = connection()) {
            long actor = insertUser(connection);
            long maker = insertUser(connection);
            long approval = insertApproval(connection, maker);
            insertRecord(connection, "adm-db03-fk-actor", actor, "CREATE_ROLE_PROPOSAL", "", HASH);
            insertRecord(connection, "adm-db03-fk-approval", actor, "DECIDE_APPROVAL",
                    Long.toString(approval), HASH);

            SQLException actorDelete = sqlFailure(connection,
                    "DELETE FROM banking.app_user WHERE user_id = ?", actor);
            assertThat(actorDelete.getSQLState()).isEqualTo("23503");
            assertThat(actorDelete.getMessage()).contains("fk_admin_idempotency_actor");

            SQLException approvalDelete = sqlFailure(connection,
                    "DELETE FROM banking.approval_request WHERE approval_id = ?", approval);
            assertThat(approvalDelete.getSQLState()).isEqualTo("23503");
            assertThat(approvalDelete.getMessage()).contains("fk_admin_idempotency_approval");

            assertThat(queryInt(connection,
                    "SELECT count(*) FROM banking.admin_idempotency_record WHERE actor_id = ?", actor))
                    .isEqualTo(2);
        }
    }

    private static void insertRecord(Connection connection, String key, long actor, String operation,
            String scopeKey, String hash) throws SQLException {
        Long approvalId = "DECIDE_APPROVAL".equals(operation) ? Long.valueOf(scopeKey) : null;
        executeUpdate(connection, """
                INSERT INTO banking.admin_idempotency_record
                    (idempotency_key, actor_id, operation, scope_key, request_hash, approval_id, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, now() + interval '1 hour')
                """, key, actor, operation, scopeKey, hash, approvalId);
    }

    private static long insertApproval(Connection connection, long maker) throws SQLException {
        return queryLong(connection, """
                INSERT INTO banking.approval_request
                    (request_type, target_type, target_id, payload, status, maker_id, correlation_id)
                VALUES ('ROLE_CHANGE', 'APP_USER', '1005',
                        '{"operation":"ASSIGN_ROLE","targetUserId":"1005","roleCode":"AUDITOR"}'::jsonb,
                        'PENDING', ?, ?)
                RETURNING approval_id
                """, maker, "corr-" + UUID.randomUUID());
    }

    private static long insertUser(Connection connection) throws SQLException {
        return queryLong(connection,
                "INSERT INTO banking.app_user (username, password_hash, user_type) "
                        + "VALUES (?, ?, 'STAFF') RETURNING user_id",
                "adm_db03_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
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
                WHERE c.conrelid = 'banking.admin_idempotency_record'::regclass AND c.conname = ?
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

    private static Map<String, Object> index(List<Map<String, Object>> indexes, String name) {
        return indexes.stream()
                .filter(row -> name.equals(row.get("indexname")))
                .findFirst()
                .orElseThrow();
    }

    private static String indexdef(List<Map<String, Object>> indexes, String name) {
        return String.valueOf(index(indexes, name).get("indexdef"));
    }

    private static String definition(List<Map<String, Object>> checks, String name) {
        return checks.stream()
                .filter(row -> name.equals(row.get("conname")))
                .map(row -> String.valueOf(row.get("definition")))
                .findFirst()
                .orElseThrow();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
