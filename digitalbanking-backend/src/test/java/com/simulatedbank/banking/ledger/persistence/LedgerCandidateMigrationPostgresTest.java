package com.simulatedbank.banking.ledger.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class LedgerCandidateMigrationPostgresTest {
    private static final String FRESH = "digital_banking";
    private static final String UPGRADE = "ledger_upgrade";
    private static final String APP = "banking_app";
    private static final String MIGRATOR = "banking_migrator";
    private static final String PROBE = "ledger_trigger_probe";
    private static final String INHERITED_WRITER = "ledger_inherited_writer";
    private static final String APP_PASSWORD = UUID.randomUUID().toString();
    private static final String MIGRATOR_PASSWORD = UUID.randomUUID().toString();
    private static final String PROBE_PASSWORD = UUID.randomUUID().toString();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName(FRESH)
            .withUsername("postgres")
            .withEnv("BANKING_APP_PASSWORD", APP_PASSWORD)
            .withEnv("BANKING_MIGRATION_PASSWORD", MIGRATOR_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath(
                    Path.of("docker/postgres/init/01-create-roles.sql"), 0755),
                    "/docker-entrypoint-initdb.d/01-create-roles.sql");

    @BeforeAll
    static void migrateFreshDatabase() throws SQLException {
        // Clone the bootstrapped schema and its default ACL before applying either migration.
        try (var admin = connect("postgres", POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(admin, "CREATE DATABASE " + UPGRADE + " TEMPLATE " + FRESH);
            execute(admin, "CREATE ROLE " + PROBE + " LOGIN PASSWORD '" + PROBE_PASSWORD + "'");
            execute(admin, "CREATE ROLE " + INHERITED_WRITER + " NOLOGIN");
            execute(admin, "GRANT " + INHERITED_WRITER + " TO banking_app");
        }

        var flyway = flyway(FRESH, "latest");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        flyway.validate();
        try (var owner = owner(FRESH)) {
            System.out.println("PostgreSQL " + scalar(owner, "SHOW server_version"));
            System.out.println("Fresh version/checksum " + values(owner, """
                    SELECT concat_ws(':', version, checksum) FROM banking.flyway_schema_history
                    WHERE success AND version IS NOT NULL ORDER BY installed_rank
                    """));
        }
    }

    @Test
    void catalogMatchesLedgerSchemaAndBusinessIndexes() throws SQLException {
        try (var owner = owner(FRESH)) {
            assertThat(values(owner, """
                    SELECT a.attname || ' ' || format_type(a.atttypid, a.atttypmod)
                    FROM pg_attribute a WHERE a.attrelid = 'banking.ledger_entry'::regclass
                    AND a.attnum > 0 AND NOT a.attisdropped AND a.attnotnull ORDER BY a.attnum
                    """)).containsExactly("entry_id bigint", "transfer_id bigint", "account_id bigint",
                    "entry_seq smallint", "entry_type character varying(8)", "amount numeric(19,2)",
                    "currency character(3)", "balance_after numeric(19,2)", "correlation_id character varying(64)",
                    "created_at timestamp with time zone");
            assertThat(values(owner, """
                    SELECT column_name || ':' || column_default FROM information_schema.columns
                    WHERE table_schema = 'banking' AND table_name = 'ledger_entry'
                    AND column_default IS NOT NULL
                    """)).containsExactlyInAnyOrder("currency:'VND'::bpchar", "created_at:now()");
            assertThat(scalar(owner, """
                    SELECT identity_generation FROM information_schema.columns
                    WHERE table_schema = 'banking' AND table_name = 'ledger_entry' AND column_name = 'entry_id'
                    """)).isEqualTo("ALWAYS");
            assertThat(scalar(owner, """
                    SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'banking.ledger_entry'::regclass
                    """)).isEqualTo(MIGRATOR);
            assertThat(values(owner, """
                    SELECT conname FROM pg_constraint WHERE conrelid = 'banking.ledger_entry'::regclass
                    AND convalidated ORDER BY conname
                    """)).containsExactly("ck_ledger_entry_amount", "ck_ledger_entry_balance", "ck_ledger_entry_currency",
                    "ck_ledger_entry_seq", "ck_ledger_entry_seq_type", "ck_ledger_entry_type", "fk_ledger_account",
                    "fk_ledger_transfer", "pk_ledger_entry", "uq_ledger_transfer_seq");
            assertThat(values(owner, """
                    SELECT pg_get_constraintdef(oid) FROM pg_constraint
                    WHERE conrelid = 'banking.ledger_entry'::regclass AND contype = 'f' AND convalidated
                    """)).containsExactlyInAnyOrder(
                    "FOREIGN KEY (account_id) REFERENCES account(account_id) ON UPDATE RESTRICT ON DELETE RESTRICT",
                    "FOREIGN KEY (transfer_id) REFERENCES transfer(transfer_id) ON UPDATE RESTRICT ON DELETE RESTRICT");
            assertThat(values(owner, """
                    SELECT indexdef FROM pg_indexes WHERE schemaname = 'banking' AND tablename = 'ledger_entry'
                    """)).containsExactlyInAnyOrder(
                    "CREATE UNIQUE INDEX pk_ledger_entry ON banking.ledger_entry USING btree (entry_id)",
                    "CREATE UNIQUE INDEX uq_ledger_transfer_seq ON banking.ledger_entry USING btree (transfer_id, entry_seq)",
                    "CREATE INDEX ix_ledger_account_time ON banking.ledger_entry USING btree (account_id, created_at DESC)",
                    "CREATE INDEX ix_ledger_transfer ON banking.ledger_entry USING btree (transfer_id)",
                    "CREATE INDEX ix_ledger_correlation ON banking.ledger_entry USING btree (correlation_id)");
            assertThat(scalar(owner, """
                    SELECT concat_ws(':', t.tgenabled, pg_get_userbyid(p.proowner), p.prosecdef::text)
                    FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
                    WHERE t.tgrelid = 'banking.ledger_entry'::regclass AND NOT t.tgisinternal
                    AND t.tgname = 'trg_ledger_entry_append_only'
                    """)).isEqualTo("O:banking_migrator:false");
        }
    }

    @Test
    void runtimeInsertsAndReadsPairWithIdentityAndDefaults() throws SQLException {
        long transfer = newTransfer(FRESH);
        try (var app = app(FRESH)) {
            app.setAutoCommit(false);
            long debit = insert(app, transfer, "1001, 1, 'DEBIT', 0.01, 0, 'pair'");
            long credit = insert(app, transfer, "1002, 2, 'CREDIT', 0.01, 12.01, 'pair'");
            assertThat(debit).isPositive().isNotEqualTo(credit);
            assertThat(credit).isPositive();
            app.commit();
        }
        try (var app = app(FRESH)) {
            assertThat(values(app, """
                    SELECT concat_ws(':', account_id, entry_seq, entry_type, amount, currency, balance_after,
                        correlation_id, created_at <= now())
                    FROM banking.ledger_entry WHERE transfer_id = %d ORDER BY entry_seq
                    """.formatted(transfer))).containsExactly(
                    "1001:1:DEBIT:0.01:VND:0.00:pair:t", "1002:2:CREDIT:0.01:VND:12.01:pair:t");
        }
    }

    @Test
    void rejectsNullsAndInvalidEntryValuesAtTheirConstraints() throws SQLException {
        long transfer = newTransfer(FRESH);
        try (var app = app(FRESH)) {
            String valid = "1001, 1, 'DEBIT', 1, 0, 'checks'";
            var columns = List.of("transfer_id", "account_id", "entry_seq", "entry_type", "amount",
                    "balance_after", "correlation_id", "currency", "created_at");
            var defaults = List.of(Long.toString(transfer), "1001", "1", "'DEBIT'", "1", "0",
                    "'checks'", "DEFAULT", "DEFAULT");
            for (int i = 0; i < columns.size(); i++) {
                var row = new ArrayList<>(defaults);
                row.set(i, "NULL");
                rejected("23502", columns.get(i), () -> execute(app, """
                        INSERT INTO banking.ledger_entry (%s) VALUES (%s)
                        """.formatted(String.join(", ", columns), String.join(", ", row))));
            }
            check(app, transfer, "0", "DEBIT", "1", "0", "ck_ledger_entry_seq");
            check(app, transfer, "3", "DEBIT", "1", "0", "ck_ledger_entry_seq");
            check(app, transfer, "1", "INVALID", "1", "0", "ck_ledger_entry_seq_type");
            check(app, transfer, "1", "CREDIT", "1", "0", "ck_ledger_entry_seq_type");
            check(app, transfer, "2", "DEBIT", "1", "0", "ck_ledger_entry_seq_type");
            for (String amount : List.of("0", "-1", "'NaN'")) {
                check(app, transfer, "1", "DEBIT", amount, "0", "ck_ledger_entry_amount");
            }
            for (String balance : List.of("-1", "'NaN'")) {
                check(app, transfer, "1", "DEBIT", "1", balance, "ck_ledger_entry_balance");
            }
            rejected("23514", "ck_ledger_entry_currency", () -> execute(app, """
                    INSERT INTO banking.ledger_entry (transfer_id, account_id, entry_seq, entry_type,
                        amount, balance_after, correlation_id, currency) VALUES (%d, %s, 'USD')
                    """.formatted(transfer, valid)));
        }
        assertCount(transfer, 0);
    }

    @Test
    void enforcesMoneyPrecisionAndCorrelationLengthAndDocumentsScaleCoercion() throws SQLException {
        long transfer = newTransfer(FRESH);
        try (var app = app(FRESH)) {
            String max = "99999999999999999.99";
            String overflow = "100000000000000000.00";
            rejected("22003", null, () -> insert(app, transfer, "1001, 1, 'DEBIT', " + overflow + ", 0, 'money'"));
            rejected("22003", null, () -> insert(app, transfer, "1001, 1, 'DEBIT', 1, " + overflow + ", 'money'"));
            rejected("22001", null, () -> insert(app, transfer, "1001, 1, 'DEBIT', 1, 0, repeat('a', 65)"));
            insert(app, transfer, "1001, 1, 'DEBIT', " + max + ", " + max + ", repeat('a', 64)");
            insert(app, transfer, "1002, 2, 'CREDIT', 1.235, 2.345, 'scale'");
        }
        try (var app = app(FRESH)) {
            assertThat(values(app, """
                    SELECT concat_ws(':', amount, balance_after, length(correlation_id))
                    FROM banking.ledger_entry WHERE transfer_id = %d ORDER BY entry_seq
                    """.formatted(transfer))).containsExactly("99999999999999999.99:99999999999999999.99:64", "1.24:2.35:5");
        }
    }

    @Test
    void foreignKeysRejectOrphansAndRestrictChangesToReferencedParents() throws SQLException {
        long transfer = newTransfer(FRESH);
        try (var app = app(FRESH); var owner = owner(FRESH)) {
            rejected("23503", "fk_ledger_account", () -> insert(app, transfer, "-1, 1, 'DEBIT', 1, 0, 'fk'"));
            rejected("23503", "fk_ledger_transfer", () -> insertDebit(app, -1));
            insertDebit(app, transfer);
            rejected("23503", "fk_ledger_transfer", () -> execute(owner,
                    "DELETE FROM banking.transfer WHERE transfer_id = " + transfer));
            rejected("23503", "fk_ledger_transfer", () -> execute(owner,
                    "UPDATE banking.transfer SET transfer_id = DEFAULT WHERE transfer_id = " + transfer));
            rejected("23503", "fk_ledger_account", () -> execute(owner, "DELETE FROM banking.account WHERE account_id = 1001"));
            rejected("23503", "fk_ledger_account", () -> execute(owner,
                    "UPDATE banking.account SET account_id = DEFAULT WHERE account_id = 1001"));
        }
        assertCount(transfer, 1);
    }

    @Test
    void duplicateInSameTransactionAbortsTheWholeWrite() throws SQLException {
        long transfer = newTransfer(FRESH);
        try (var app = app(FRESH)) {
            app.setAutoCommit(false);
            insertDebit(app, transfer);
            rejected("23505", "uq_ledger_transfer_seq", () -> insertDebit(app, transfer));
            app.rollback();
        }
        assertCount(transfer, 0);
    }

    @ParameterizedTest(name = "winner commits={0}")
    @ValueSource(booleans = {true, false})
    void concurrentDuplicateWaitsForWinnerAndRespectsItsOutcome(boolean winnerCommits) throws Exception {
        long transfer = newTransfer(FRESH);
        try (var winner = app(FRESH); var waiter = app(FRESH)) {
            execute(waiter, "SET statement_timeout = '8s'");
            String waiterPid = scalar(waiter, "SELECT pg_backend_pid()");
            String winnerPid = scalar(winner, "SELECT pg_backend_pid()");
            winner.setAutoCommit(false);
            insertDebit(winner, transfer);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var pending = executor.submit(() -> insertDebit(waiter, transfer));
                await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> assertThat(scalar(winner,
                        "SELECT " + winnerPid + " = ANY(pg_blocking_pids(" + waiterPid + "))")).isEqualTo("t"));

                if (winnerCommits) {
                    winner.commit();
                    var failure = assertThrows(ExecutionException.class, () -> pending.get(4, TimeUnit.SECONDS));
                    assertRejected((SQLException) failure.getCause(), "23505", "uq_ledger_transfer_seq");
                } else {
                    winner.rollback();
                    assertThat(pending.get(4, TimeUnit.SECONDS)).isPositive();
                }
            } finally {
                winner.rollback();
                executor.shutdown();
            }
        }
        assertCount(transfer, 1);
    }

    @Test
    void rollbacksLeaveNoEntriesAndPreservePreviouslyCommittedData() throws SQLException {
        long existing = newTransfer(FRESH);
        long failed = newTransfer(FRESH);
        long rolledBack = newTransfer(FRESH);
        try (var app = app(FRESH)) {
            insertDebit(app, existing);
            app.setAutoCommit(false);
            insertDebit(app, failed);
            rejected("23514", "ck_ledger_entry_seq_type",
                    () -> insert(app, failed, "1002, 2, 'DEBIT', 1, 0, 'bad-second'"));
            app.rollback();
            insertDebit(app, rolledBack);
            insert(app, rolledBack, "1002, 2, 'CREDIT', 1, 0, 'explicit-rollback'");
            app.rollback();
            insertDebit(app, rolledBack);
            app.rollback();
        }
        assertCount(failed, 0);
        assertCount(rolledBack, 0);
        assertCount(existing, 1);
    }

    @Test
    void runtimeAclRejectsMutationDdlSequenceAccessAndRoleSwitch() throws SQLException {
        long transfer = newTransfer(FRESH);
        try (var app = app(FRESH)) {
            insertDebit(app, transfer);
            assertRuntimePrivileges(app);
            for (String sql : List.of(
                    "UPDATE banking.ledger_entry SET amount = 2 WHERE transfer_id = " + transfer,
                    "DELETE FROM banking.ledger_entry WHERE transfer_id = " + transfer,
                    "TRUNCATE banking.ledger_entry",
                    "ALTER TABLE banking.ledger_entry ADD COLUMN unexpected integer",
                    "DROP TABLE banking.ledger_entry",
                    "CREATE TABLE banking.unexpected (id integer)",
                    "ALTER TABLE banking.ledger_entry DISABLE TRIGGER trg_ledger_entry_append_only",
                    "CREATE OR REPLACE TRIGGER trg_ledger_entry_append_only BEFORE DELETE ON banking.ledger_entry "
                            + "FOR EACH STATEMENT EXECUTE FUNCTION banking.reject_ledger_entry_mutation()",
                    "CREATE OR REPLACE FUNCTION banking.reject_ledger_entry_mutation() RETURNS trigger "
                            + "LANGUAGE plpgsql AS 'BEGIN RETURN NULL; END;'",
                    "ALTER SEQUENCE banking.ledger_entry_entry_id_seq RESTART WITH 1",
                    "SELECT setval('banking.ledger_entry_entry_id_seq', 1)",
                    "SELECT nextval('banking.ledger_entry_entry_id_seq')",
                    "SET ROLE banking_migrator")) {
                rejected("42501", null, () -> execute(app, sql));
            }
            assertThat(scalar(app, "SELECT amount FROM banking.ledger_entry WHERE transfer_id = " + transfer))
                    .isEqualTo("1.00");
        }
        assertCount(transfer, 1);
    }

    @Test
    void triggerRejectsActualMutationsIndependentlyOfAcl() throws SQLException {
        long transfer = newTransfer(FRESH);
        try (var app = app(FRESH)) {
            insertDebit(app, transfer);
        }
        grantProbe(FRESH);
        try (var probe = connect(FRESH, PROBE, PROBE_PASSWORD)) {
            assertThat(scalar(probe, "SELECT current_user")).isEqualTo(PROBE);
            for (String sql : List.of("UPDATE banking.ledger_entry SET amount = 2 WHERE transfer_id = " + transfer,
                    "DELETE FROM banking.ledger_entry WHERE transfer_id = " + transfer, "TRUNCATE banking.ledger_entry")) {
                assertThat(rejected("55000", null, () -> execute(probe, sql)).getMessage())
                        .contains("ledger_entry is append-only");
            }
            rejected("42501", null, () -> execute(probe,
                    "ALTER TABLE banking.ledger_entry DISABLE TRIGGER trg_ledger_entry_append_only"));
        }
        try (var app = app(FRESH)) {
            assertThat(scalar(app, "SELECT amount FROM banking.ledger_entry WHERE transfer_id = " + transfer))
                    .isEqualTo("1.00");
        }
        assertCount(transfer, 1);
    }

    @Test
    void upgradesFromFixturePreservesKeysAndValidatesOnRestart() throws SQLException {
        assertThat(flyway(UPGRADE, "1").migrate().migrationsExecuted).isEqualTo(1);
        long transfer = newTransfer(UPGRADE);
        var upgrade = flyway(UPGRADE, "latest");
        try (var owner = owner(UPGRADE)) {
            // This inherited privilege applies only to new tables in the upgrade database.
            execute(owner, "ALTER DEFAULT PRIVILEGES IN SCHEMA banking GRANT UPDATE ON TABLES TO " + INHERITED_WRITER);
            var failure = assertThrows(FlywayException.class, upgrade::migrate);
            var cause = failure;
            while (cause.getCause() instanceof FlywayException nested) {
                cause = nested;
            }
            assertThat(cause.getCause()).isInstanceOfSatisfying(SQLException.class,
                    error -> assertRejected(error, "42501", null));
            assertThat(failure.getMessage()).contains("ledger_entry requires isolated migration and runtime roles");
            assertThat(scalar(owner, "SELECT (to_regclass('banking.ledger_entry') IS NULL)::text")).isEqualTo("true");
            assertThat(scalar(owner, """
                    SELECT count(*) FROM banking.flyway_schema_history WHERE version = '2'
                    """)).isEqualTo("0");
            execute(owner, "ALTER DEFAULT PRIVILEGES IN SCHEMA banking REVOKE UPDATE ON TABLES FROM " + INHERITED_WRITER);
        }
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        var restart = flyway(UPGRADE, "latest");
        assertThat(restart.migrate().migrationsExecuted).isZero();
        restart.validate();

        grantProbe(UPGRADE);
        try (var probe = connect(UPGRADE, PROBE, PROBE_PASSWORD); var app = app(UPGRADE)) {
            assertRuntimePrivileges(app);
            assertThat(scalar(probe, "SELECT count(*) FROM banking.ledger_entry")).isEqualTo("0");
            assertThat(rejected("55000", null, () -> execute(probe, "TRUNCATE banking.ledger_entry")).getMessage())
                    .contains("ledger_entry is append-only");
            assertThat(values(app, "SELECT account_id FROM banking.account ORDER BY account_id"))
                    .containsExactly("1001", "1002");
            assertThat(values(app, "SELECT transfer_id FROM banking.transfer")).containsExactly(Long.toString(transfer));
            assertThat(insertDebit(app, transfer)).isPositive();
        }
    }

    @Test
    void schemaPermitsPartialAndUnequalPairsForProviderToDetect() throws SQLException {
        long partial = newTransfer(FRESH);
        long unequal = newTransfer(FRESH);
        try (var app = app(FRESH)) {
            insertDebit(app, partial);
            insertDebit(app, unequal);
            insert(app, unequal, "1002, 2, 'CREDIT', 2, 0, 'ledger-db'");
        }
        assertCount(partial, 1);
        try (var app = app(FRESH)) {
            assertThat(values(app, "SELECT amount FROM banking.ledger_entry WHERE transfer_id = " + unequal
                    + " ORDER BY entry_seq")).containsExactly("1.00", "2.00");
        }
    }

    @Test
    void capturesPlansForAccountTransferAndCorrelationLookups() throws SQLException {
        try (var owner = owner(FRESH); var app = app(FRESH)) {
            owner.setAutoCommit(false);
            execute(owner, """
                    INSERT INTO banking.transfer (transfer_id) OVERRIDING SYSTEM VALUE
                    SELECT n FROM generate_series(100000, 103999) AS n
                    """);
            execute(owner, """
                    INSERT INTO banking.ledger_entry (transfer_id, account_id, entry_seq, entry_type,
                        amount, balance_after, correlation_id, created_at)
                    SELECT transfer_id, 1001 + transfer_id % 2, 1, 'DEBIT', 1, 0,
                        'plan-' || transfer_id, now() - (transfer_id - 100000) * interval '1 minute'
                    FROM banking.transfer WHERE transfer_id BETWEEN 100000 AND 103999
                    """);
            execute(owner, "ANALYZE banking.ledger_entry");
            for (String query : List.of(
                    "WHERE account_id = 1001 ORDER BY created_at DESC LIMIT 20",
                    "WHERE transfer_id = 103000 ORDER BY entry_seq",
                    "WHERE correlation_id = 'plan-103000'")) {
                var plan = values(owner, "EXPLAIN (COSTS OFF) SELECT * FROM banking.ledger_entry " + query);
                assertThat(plan).isNotEmpty();
                System.out.println(query + "\n" + String.join("\n", plan));
            }
            owner.rollback();
            assertThat(scalar(app, "SELECT count(*) FROM banking.ledger_entry WHERE transfer_id >= 100000"))
                    .isEqualTo("0");
        }
    }

    private static void assertRuntimePrivileges(Connection app) throws SQLException {
        String identity = scalar(app, "SELECT current_user || ':' || session_user");
        System.out.println("Ledger role current_user:session_user " + identity);
        assertThat(identity).isEqualTo("banking_app:banking_app");
        assertThat(values(app, """
                SELECT acl.privilege_type || ':' || acl.is_grantable
                FROM pg_class c CROSS JOIN LATERAL aclexplode(c.relacl) acl
                WHERE c.oid = 'banking.ledger_entry'::regclass AND acl.grantee = 'banking_app'::regrole
                """)).containsExactlyInAnyOrder("SELECT:false", "INSERT:false");
        assertThat(scalar(app, """
                SELECT (has_table_privilege('banking.transfer', 'UPDATE')
                    AND has_table_privilege('banking.ledger_entry', 'SELECT')
                    AND has_table_privilege('banking.ledger_entry', 'INSERT')
                    AND NOT has_table_privilege('banking.ledger_entry', 'UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
                    AND NOT pg_has_role('banking_migrator', 'MEMBER')
                    AND NOT has_schema_privilege('banking', 'CREATE')
                    AND NOT has_sequence_privilege('banking.ledger_entry_entry_id_seq', 'USAGE, SELECT, UPDATE'))::text
                """)).isEqualTo("true");
    }

    private static Flyway flyway(String database, String target) {
        return Flyway.configure().dataSource(url(database), MIGRATOR, MIGRATOR_PASSWORD)
                .schemas("banking").defaultSchema("banking")
                .locations("classpath:db/ledger-fixture", "classpath:db/ledger-candidate")
                .target(target).load();
    }

    private static void grantProbe(String database) throws SQLException {
        try (var owner = owner(database)) {
            execute(owner, "GRANT USAGE ON SCHEMA banking TO " + PROBE);
            execute(owner, "GRANT SELECT, UPDATE, DELETE, TRUNCATE ON banking.ledger_entry TO " + PROBE);
        }
    }

    private static long newTransfer(String database) throws SQLException {
        try (var owner = owner(database)) {
            return Long.parseLong(scalar(owner, "INSERT INTO banking.transfer DEFAULT VALUES RETURNING transfer_id"));
        }
    }

    private static void assertCount(long transfer, int expected) throws SQLException {
        try (var app = app(FRESH)) {
            assertThat(scalar(app, "SELECT count(*) FROM banking.ledger_entry WHERE transfer_id = " + transfer))
                    .isEqualTo(Integer.toString(expected));
        }
    }

    private static long insertDebit(Connection connection, long transfer) throws SQLException {
        return insert(connection, transfer, "1001, 1, 'DEBIT', 1, 0, 'ledger-db'");
    }

    private static long insert(Connection connection, long transfer, String values) throws SQLException {
        return Long.parseLong(scalar(connection, """
                INSERT INTO banking.ledger_entry (transfer_id, account_id, entry_seq, entry_type,
                    amount, balance_after, correlation_id) VALUES (%d, %s) RETURNING entry_id
                """.formatted(transfer, values)));
    }

    private static void check(Connection app, long transfer, String seq, String type, String amount, String balance,
            String constraint) {
        rejected("23514", constraint, () -> insert(app, transfer,
                "1001, %s, '%s', %s, %s, 'checks'".formatted(seq, type, amount, balance)));
    }

    private static SQLException rejected(String state, String object, Executable sql) {
        var error = assertThrows(SQLException.class, sql);
        assertRejected(error, state, object);
        return error;
    }

    private static void assertRejected(SQLException error, String state, String object) {
        assertThat(error.getSQLState()).as(error.getMessage()).isEqualTo(state);
        if (object != null) {
            assertThat(error.getMessage()).contains("\"" + object + "\"");
        }
    }

    private static Connection app(String database) throws SQLException {
        return connect(database, APP, APP_PASSWORD);
    }

    private static Connection owner(String database) throws SQLException {
        return connect(database, MIGRATOR, MIGRATOR_PASSWORD);
    }

    private static String url(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    private static Connection connect(String database, String user, String password) throws SQLException {
        return DriverManager.getConnection(url(database), user, password);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        List<String> result = values(connection, sql);
        assertThat(result).hasSize(1);
        return result.getFirst();
    }

    private static List<String> values(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            List<String> rows = new ArrayList<>();
            while (result.next()) {
                rows.add(result.getString(1));
            }
            return rows;
        }
    }
}
