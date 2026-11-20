package com.simulatedbank.banking.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.math.BigDecimal;
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
 * BE-ACC-02: verifies V8 against the canonical {@code banking.account} DDL
 * (04. Database design & analysis.md §4.3.1, BE-ACC-01 §B) on a disposable PostgreSQL 16 database.
 */
@Testcontainers
class AccountV8MigrationPostgreSqlIntegrationTest {

    private static final int EXPECTED_MIGRATIONS = 23;

    private static final String INSERT_ACCOUNT = """
            INSERT INTO banking.account (customer_id, account_no_enc, account_no_hash, account_no_last4)
            """;

    private static final String INSERT_ACCOUNT_FULL = """
            INSERT INTO banking.account
                (customer_id, account_no_enc, account_no_hash, account_no_last4,
                 currency, status, available_balance, ledger_balance, closed_at)
            """;

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("account_v8");

    private static Flyway flyway;
    private static MigrateResult migration;

    @BeforeAll
    static void migrateFreshDatabase() {
        postgres.start();
        flyway = PostgreSqlIntegrationTestSupport.flyway(postgres);
        migration = flyway.migrate();
    }

    @Test
    void freshMigrationAppliesV8OnceAndValidationSucceeds() throws Exception {
        assertThat(migration.migrationsExecuted).isEqualTo(EXPECTED_MIGRATIONS);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("23");
        assertThatCode(flyway::validate).doesNotThrowAnyException();
        try (Connection connection = connection()) {
            assertThat(queryInt(connection,
                    "SELECT count(*) FROM banking.flyway_schema_history "
                            + "WHERE version = '8' AND success = true"))
                    .isEqualTo(1);
            assertThat(queryStrings(connection,
                    "SELECT version FROM banking.flyway_schema_history "
                            + "WHERE version IS NOT NULL AND success ORDER BY installed_rank"))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23");
            assertThat(queryStrings(connection,
                    "SELECT version FROM banking.flyway_schema_history "
                            + "WHERE version IS NOT NULL GROUP BY version HAVING count(*) > 1"))
                    .isEmpty();
            assertThat(queryInt(connection,
                    "SELECT count(*) FROM banking.flyway_schema_history "
                            + "WHERE version IS NOT NULL AND (NOT success OR checksum IS NULL)"))
                    .isZero();
            // Account is append-only in V8: no V1-V7 object is redefined by this migration.
            assertThat(queryInt(connection,
                    "SELECT count(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'banking' AND table_name = 'account'"))
                    .isEqualTo(1);
        }
    }

    @Test
    void columnsTypesLengthsNullabilityDefaultsAndIdentityMatchCanonicalSchema() throws Exception {
        try (Connection connection = connection()) {
            List<Map<String, Object>> columns = queryRows(connection, """
                    SELECT column_name, data_type, character_maximum_length, numeric_precision,
                           numeric_scale, is_nullable, column_default, is_identity, identity_generation
                    FROM information_schema.columns
                    WHERE table_schema = 'banking' AND table_name = 'account'
                    ORDER BY ordinal_position
                    """);

            assertThat(columns).extracting(row -> row.get("column_name")).containsExactly(
                    "account_id", "customer_id", "account_no_enc", "account_no_hash",
                    "account_no_last4", "currency", "status", "available_balance", "ledger_balance",
                    "closed_at", "created_at", "updated_at");

            assertThat(dataType(columns, "account_id")).isEqualTo("bigint");
            assertThat(dataType(columns, "customer_id")).isEqualTo("bigint");
            assertThat(dataType(columns, "account_no_enc")).isEqualTo("bytea");
            assertThat(dataType(columns, "account_no_hash")).isEqualTo("character");
            assertThat(dataType(columns, "account_no_last4")).isEqualTo("character");
            assertThat(dataType(columns, "currency")).isEqualTo("character");
            assertThat(dataType(columns, "status")).isEqualTo("character varying");
            assertThat(dataType(columns, "available_balance")).isEqualTo("numeric");
            assertThat(dataType(columns, "ledger_balance")).isEqualTo("numeric");
            assertThat(dataType(columns, "closed_at")).isEqualTo("timestamp with time zone");
            assertThat(dataType(columns, "created_at")).isEqualTo("timestamp with time zone");
            assertThat(dataType(columns, "updated_at")).isEqualTo("timestamp with time zone");

            assertThat(maxLength(columns, "account_no_hash")).isEqualTo(64);
            assertThat(maxLength(columns, "account_no_last4")).isEqualTo(4);
            assertThat(maxLength(columns, "currency")).isEqualTo(3);
            assertThat(maxLength(columns, "status")).isEqualTo(16);

            for (String money : List.of("available_balance", "ledger_balance")) {
                assertThat(column(columns, money).get("numeric_precision")).isEqualTo(19);
                assertThat(column(columns, money).get("numeric_scale")).isEqualTo(2);
            }

            assertThat(columns.stream()
                    .filter(row -> "YES".equals(row.get("is_nullable")))
                    .map(row -> row.get("column_name")))
                    .containsExactly("closed_at");

            assertThat(column(columns, "account_id").get("is_identity")).isEqualTo("YES");
            assertThat(column(columns, "account_id").get("identity_generation")).isEqualTo("ALWAYS");
            assertThat(column(columns, "account_id").get("column_default")).isNull();

            assertThat(column(columns, "currency").get("column_default")).isEqualTo("'VND'::bpchar");
            assertThat(column(columns, "status").get("column_default"))
                    .isEqualTo("'ACTIVE'::character varying");
            assertThat(String.valueOf(column(columns, "available_balance").get("column_default")))
                    .contains("0");
            assertThat(String.valueOf(column(columns, "ledger_balance").get("column_default")))
                    .contains("0");
            assertThat(column(columns, "created_at").get("column_default")).isEqualTo("now()");
            assertThat(column(columns, "updated_at").get("column_default")).isEqualTo("now()");

            // ACC-DR-03/07: no account_type and no optimistic-locking version column in the MVP schema.
            assertThat(columns).extracting(row -> row.get("column_name"))
                    .doesNotContain("account_type", "version", "account_no", "idempotency_key");
        }
    }

    @Test
    void primaryForeignUniqueAndCheckConstraintsMatchApprovedDesign() throws Exception {
        try (Connection connection = connection()) {
            assertThat(queryStrings(connection, """
                    SELECT a.attname FROM pg_constraint c
                    JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON true
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                    WHERE c.conrelid = 'banking.account'::regclass AND c.contype = 'p'
                    ORDER BY k.ord
                    """))
                    .containsExactly("account_id");

            List<Map<String, Object>> foreignKeys = queryRows(connection, """
                    SELECT c.conname, ns.nspname AS referenced_schema, r.relname AS referenced_table,
                           a.attname AS local_column, ra.attname AS referenced_column,
                           c.confdeltype::text AS delete_action, c.confupdtype::text AS update_action
                    FROM pg_constraint c
                    JOIN pg_class r ON r.oid = c.confrelid
                    JOIN pg_namespace ns ON ns.oid = r.relnamespace
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                    JOIN pg_attribute ra ON ra.attrelid = c.confrelid AND ra.attnum = c.confkey[1]
                    WHERE c.conrelid = 'banking.account'::regclass AND c.contype = 'f'
                    """);
            assertThat(foreignKeys).singleElement().satisfies(foreignKey -> assertThat(foreignKey)
                    .containsEntry("conname", "fk_account_customer")
                    .containsEntry("referenced_schema", "banking")
                    .containsEntry("referenced_table", "customer")
                    .containsEntry("local_column", "customer_id")
                    .containsEntry("referenced_column", "customer_id")
                    .containsEntry("delete_action", "r")
                    .containsEntry("update_action", "r"));

            List<Map<String, Object>> uniques = queryRows(connection, """
                    SELECT c.conname, a.attname
                    FROM pg_constraint c
                    JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON true
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                    WHERE c.conrelid = 'banking.account'::regclass AND c.contype = 'u'
                    ORDER BY c.conname, k.ord
                    """);
            assertThat(uniques).extracting(row -> row.get("conname")).containsExactly("uq_account_no_hash");
            assertThat(uniques).extracting(row -> row.get("attname")).containsExactly("account_no_hash");

            List<Map<String, Object>> checks = queryRows(connection, """
                    SELECT conname, pg_get_constraintdef(oid) AS definition
                    FROM pg_constraint
                    WHERE conrelid = 'banking.account'::regclass AND contype = 'c'
                    ORDER BY conname
                    """);
            assertThat(checks).extracting(row -> row.get("conname")).containsExactly(
                    "ck_account_available_balance_non_negative",
                    "ck_account_balance_within_ledger",
                    "ck_account_closed_at_required",
                    "ck_account_currency",
                    "ck_account_ledger_balance_non_negative",
                    "ck_account_no_hash_hex",
                    "ck_account_no_last4_digits",
                    "ck_account_status");

            assertThat(checkValues(connection, "ck_account_status"))
                    .containsExactlyInAnyOrder("ACTIVE", "LOCKED", "CLOSED");
            assertThat(definition(checks, "ck_account_currency"))
                    .contains("VND").contains("currency");
            assertThat(definition(checks, "ck_account_available_balance_non_negative"))
                    .contains("available_balance").contains(">= ");
            assertThat(definition(checks, "ck_account_ledger_balance_non_negative"))
                    .contains("ledger_balance").contains(">= ");
            assertThat(definition(checks, "ck_account_balance_within_ledger"))
                    .contains("available_balance").contains("ledger_balance").contains("<= ");
            assertThat(definition(checks, "ck_account_closed_at_required"))
                    .contains("CLOSED").contains("closed_at IS NOT NULL");
            assertThat(definition(checks, "ck_account_no_hash_hex"))
                    .contains("0-9a-f").contains("64");
            assertThat(definition(checks, "ck_account_no_last4_digits"))
                    .contains("0-9").contains("4");
        }
    }

    @Test
    void canonicalIndexesExistWithoutDuplicatesOrPartialPredicates() throws Exception {
        try (Connection connection = connection()) {
            List<Map<String, Object>> indexes = queryRows(connection, """
                    SELECT i.indexname, i.indexdef, x.indisunique AS is_unique,
                           (x.indpred IS NOT NULL) AS is_partial
                    FROM pg_indexes i
                    JOIN pg_class c ON c.relname = i.indexname
                    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = i.schemaname
                    JOIN pg_index x ON x.indexrelid = c.oid
                    WHERE i.schemaname = 'banking' AND i.tablename = 'account'
                    ORDER BY i.indexname
                    """);

            // 04 §4.3.1: PK, uq_account_no_hash, ix_account_customer, ix_account_status only.
            assertThat(indexes).extracting(row -> row.get("indexname")).containsExactly(
                    "account_pkey", "ix_account_customer", "ix_account_status", "uq_account_no_hash");
            assertThat(indexes).allSatisfy(row -> assertThat(row.get("is_partial")).isEqualTo(false));

            assertThat(indexdef(indexes, "account_pkey")).contains("USING btree (account_id)");
            assertThat(index(indexes, "account_pkey").get("is_unique")).isEqualTo(true);
            assertThat(indexdef(indexes, "uq_account_no_hash"))
                    .contains("CREATE UNIQUE INDEX").contains("USING btree (account_no_hash)");
            assertThat(indexdef(indexes, "ix_account_customer"))
                    .contains("USING btree (customer_id)");
            assertThat(indexdef(indexes, "ix_account_status")).contains("USING btree (status)");

            for (String name : List.of("ix_account_customer", "ix_account_status")) {
                assertThat(index(indexes, name).get("is_unique"))
                        .as("%s must not be unique", name).isEqualTo(false);
            }

            // No duplicate access path on the blind index: the UNIQUE constraint owns it.
            assertThat(indexes).filteredOn(row -> String.valueOf(row.get("indexdef"))
                            .contains("(account_no_hash)"))
                    .extracting(row -> row.get("indexname"))
                    .containsExactly("uq_account_no_hash");
        }
    }

    @Test
    void validAccountUsesApprovedDefaultsAndSupportsClosedLifecycle() throws Exception {
        try (Connection connection = connection()) {
            long customerId = insertCustomer(connection);
            long accountId = queryLong(connection,
                    INSERT_ACCOUNT + "VALUES (?, ?, ?, ?) RETURNING account_id",
                    customerId, new byte[] {1, 2, 3, 4}, hash(), "1001");

            Map<String, Object> stored = queryRow(connection, """
                    SELECT customer_id, currency, status, available_balance, ledger_balance,
                           closed_at, created_at IS NOT NULL AS created_set,
                           updated_at IS NOT NULL AS updated_set,
                           pg_typeof(created_at)::text AS created_type
                    FROM banking.account WHERE account_id = ?
                    """, accountId);

            assertThat(accountId).isPositive();
            assertThat(stored).containsEntry("customer_id", customerId)
                    .containsEntry("currency", "VND")
                    .containsEntry("status", "ACTIVE")
                    .containsEntry("available_balance", new BigDecimal("0.00"))
                    .containsEntry("ledger_balance", new BigDecimal("0.00"))
                    .containsEntry("closed_at", null)
                    .containsEntry("created_set", true)
                    .containsEntry("updated_set", true)
                    .containsEntry("created_type", "timestamp with time zone");

            long lockedId = queryLong(connection,
                    INSERT_ACCOUNT_FULL + "VALUES (?, ?, ?, ?, 'VND', 'LOCKED', 0.00, 0.00, NULL) "
                            + "RETURNING account_id",
                    customerId, new byte[] {5}, hash(), "2002");
            long closedId = queryLong(connection,
                    INSERT_ACCOUNT_FULL + "VALUES (?, ?, ?, ?, 'VND', 'CLOSED', 0.00, 0.00, now()) "
                            + "RETURNING account_id",
                    customerId, new byte[] {6}, hash(), "3003");

            assertThat(queryStrings(connection,
                    "SELECT status FROM banking.account WHERE account_id IN (?, ?, ?) ORDER BY status",
                    lockedId, closedId, accountId))
                    .containsExactly("ACTIVE", "CLOSED", "LOCKED");
            assertThat(queryBoolean(connection,
                    "SELECT closed_at IS NOT NULL FROM banking.account WHERE account_id = ?", closedId))
                    .isTrue();
            assertThat(queryInt(connection,
                    "SELECT count(*) FROM banking.account WHERE customer_id = ?", customerId))
                    .isEqualTo(3);
        }
    }

    @Test
    void duplicateAccountNumberBlindIndexIsRejected() throws Exception {
        try (Connection connection = connection()) {
            long customerId = insertCustomer(connection);
            String blindIndex = hash();
            queryLong(connection, INSERT_ACCOUNT + "VALUES (?, ?, ?, ?) RETURNING account_id",
                    customerId, new byte[] {7}, blindIndex, "1001");

            assertRejected(connection, "23505", "uq_account_no_hash",
                    INSERT_ACCOUNT + "VALUES (?, ?, ?, ?)",
                    customerId, new byte[] {8}, blindIndex, "9999");
            assertThat(queryInt(connection,
                    "SELECT count(*) FROM banking.account WHERE account_no_hash = ?", blindIndex))
                    .isEqualTo(1);
        }
    }

    @Test
    void missingRequiredColumnsAndInvalidDomainValuesAreRejected() throws Exception {
        try (Connection connection = connection()) {
            long customerId = insertCustomer(connection);

            assertRejected(connection, "23502", "customer_id",
                    INSERT_ACCOUNT + "VALUES (NULL, ?, ?, ?)", new byte[] {1}, hash(), "1001");
            assertRejected(connection, "23502", "account_no_enc",
                    INSERT_ACCOUNT + "VALUES (?, NULL, ?, ?)", customerId, hash(), "1001");
            assertRejected(connection, "23502", "account_no_hash",
                    INSERT_ACCOUNT + "VALUES (?, ?, NULL, ?)", customerId, new byte[] {1}, "1001");
            assertRejected(connection, "23502", "account_no_last4",
                    INSERT_ACCOUNT + "VALUES (?, ?, ?, NULL)", customerId, new byte[] {1}, hash());

            // ACC-DR-01: FROZEN is stale documentation, only ACTIVE/LOCKED/CLOSED are canonical.
            assertRejected(connection, "23514", "ck_account_status",
                    INSERT_ACCOUNT_FULL
                            + "VALUES (?, ?, ?, ?, 'VND', 'FROZEN', 0.00, 0.00, NULL)",
                    customerId, new byte[] {1}, hash(), "1001");
            assertRejected(connection, "23514", "ck_account_currency",
                    INSERT_ACCOUNT_FULL
                            + "VALUES (?, ?, ?, ?, 'USD', 'ACTIVE', 0.00, 0.00, NULL)",
                    customerId, new byte[] {1}, hash(), "1001");
            assertRejected(connection, "23514", "ck_account_available_balance_non_negative",
                    INSERT_ACCOUNT_FULL
                            + "VALUES (?, ?, ?, ?, 'VND', 'ACTIVE', -1.00, 0.00, NULL)",
                    customerId, new byte[] {1}, hash(), "1001");
            // A negative ledger_balance also breaks ck_account_balance_within_ledger (available <=
            // ledger cannot hold once ledger < 0); PostgreSQL reports one violated check per row, so
            // this asserts the row is rejected by a check constraint rather than naming which one.
            assertRejected(connection, "23514", "ck_account_",
                    INSERT_ACCOUNT_FULL
                            + "VALUES (?, ?, ?, ?, 'VND', 'ACTIVE', 0.00, -0.01, NULL)",
                    customerId, new byte[] {1}, hash(), "1001");
            // No holds in the MVP: available can never exceed the ledger balance.
            assertRejected(connection, "23514", "ck_account_balance_within_ledger",
                    INSERT_ACCOUNT_FULL
                            + "VALUES (?, ?, ?, ?, 'VND', 'ACTIVE', 100.00, 50.00, NULL)",
                    customerId, new byte[] {1}, hash(), "1001");
            assertRejected(connection, "23514", "ck_account_closed_at_required",
                    INSERT_ACCOUNT_FULL
                            + "VALUES (?, ?, ?, ?, 'VND', 'CLOSED', 0.00, 0.00, NULL)",
                    customerId, new byte[] {1}, hash(), "1001");
            assertRejected(connection, "23514", "ck_account_no_last4_digits",
                    INSERT_ACCOUNT + "VALUES (?, ?, ?, ?)",
                    customerId, new byte[] {1}, hash(), "10a1");
            assertRejected(connection, "23514", "ck_account_no_hash_hex",
                    INSERT_ACCOUNT + "VALUES (?, ?, ?, ?)",
                    customerId, new byte[] {1}, "A".repeat(64), "1001");
            assertRejected(connection, "23514", "ck_account_no_hash_hex",
                    INSERT_ACCOUNT + "VALUES (?, ?, ?, ?)",
                    customerId, new byte[] {1}, "0".repeat(63), "1001");
        }
    }

    @Test
    void invalidCustomerReferenceIsRejected() throws Exception {
        try (Connection connection = connection()) {
            assertRejected(connection, "23503", "fk_account_customer",
                    INSERT_ACCOUNT + "VALUES (?, ?, ?, ?)",
                    Long.MAX_VALUE, new byte[] {1}, hash(), "1001");
        }
    }

    @Test
    void customerDeleteAndKeyUpdateAreRestrictedByAccountForeignKey() throws Exception {
        try (Connection connection = connection()) {
            long customerId = insertCustomer(connection);
            queryLong(connection, INSERT_ACCOUNT + "VALUES (?, ?, ?, ?) RETURNING account_id",
                    customerId, new byte[] {9}, hash(), "1001");

            assertRejected(connection, "23503", "fk_account_customer",
                    "DELETE FROM banking.customer WHERE customer_id = ?", customerId);
            assertRejected(connection, "23503", "fk_account_customer",
                    "UPDATE banking.customer SET customer_id = DEFAULT WHERE customer_id = ?", customerId);

            assertThat(queryInt(connection,
                    "SELECT count(*) FROM banking.account WHERE customer_id = ?", customerId))
                    .isEqualTo(1);
            assertThat(queryInt(connection,
                    "SELECT count(*) FROM banking.customer WHERE customer_id = ?", customerId))
                    .isEqualTo(1);
        }
    }

    @Test
    void upgradeFromV7PreservesCustomerDataAndAddsAccountTable() throws Exception {
        String database = "acc_v7_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connection()) {
            execute(admin, "CREATE DATABASE " + database);
        }
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/" + database;

        MigrateResult toV7 = flywayAt(url).target("7").load().migrate();
        assertThat(toV7.migrationsExecuted).isEqualTo(7);

        long legacyCustomerId;
        try (Connection legacy = DriverManager.getConnection(
                url, postgres.getUsername(), postgres.getPassword())) {
            assertThat(queryInt(legacy,
                    "SELECT count(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'banking' AND table_name = 'account'"))
                    .isZero();
            legacyCustomerId = insertCustomer(legacy);
        }

        Flyway upgrade = flywayAt(url).load();
        MigrateResult toV8 = upgrade.migrate();
        assertThat(toV8.migrationsExecuted).isEqualTo(EXPECTED_MIGRATIONS - 7);
        assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("23");
        assertThatCode(upgrade::validate).doesNotThrowAnyException();

        try (Connection connection = DriverManager.getConnection(
                url, postgres.getUsername(), postgres.getPassword())) {
            assertThat(queryStrings(connection,
                    "SELECT version FROM banking.flyway_schema_history "
                            + "WHERE version IS NOT NULL AND success ORDER BY installed_rank"))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23");
            assertThat(queryInt(connection,
                    "SELECT count(*) FROM banking.customer WHERE customer_id = ?", legacyCustomerId))
                    .isEqualTo(1);
            assertThat(queryStrings(connection,
                    "SELECT status FROM banking.customer WHERE customer_id = ?", legacyCustomerId))
                    .containsExactly("ACTIVE");

            long accountId = queryLong(connection,
                    INSERT_ACCOUNT + "VALUES (?, ?, ?, ?) RETURNING account_id",
                    legacyCustomerId, new byte[] {1}, hash(), "1001");
            assertThat(accountId).isPositive();
            assertThat(queryInt(connection,
                    "SELECT count(*) FROM banking.account WHERE customer_id = ?", legacyCustomerId))
                    .isEqualTo(1);
        }
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flywayAt(String url) {
        return Flyway.configure()
                .dataSource(url, postgres.getUsername(), postgres.getPassword())
                .schemas("banking")
                .defaultSchema("banking")
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .outOfOrder(false);
    }

    private static long insertCustomer(Connection connection) throws SQLException {
        long userId = queryLong(connection,
                "INSERT INTO banking.app_user (username, password_hash, user_type) "
                        + "VALUES (?, ?, 'CUSTOMER') RETURNING user_id",
                "acc_v8_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                "x".repeat(60));
        return queryLong(connection,
                "INSERT INTO banking.customer (user_id, full_name_enc) VALUES (?, ?) "
                        + "RETURNING customer_id",
                userId, new byte[] {1, 2, 3});
    }

    private static String hash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
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

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
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
                List<String> labels = new ArrayList<>();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    labels.add(metadata.getColumnLabel(index));
                }
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= labels.size(); index++) {
                        row.put(labels.get(index - 1), result.getObject(index));
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
                WHERE c.conrelid = 'banking.account'::regclass AND c.conname = ?
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
