package com.simulatedbank.banking.transfer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferPersistence;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferIdentity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Candidate checks: real Identity/Customer migrations plus a synthetic Account stand-in, not full provider integration evidence. */
@Testcontainers
class TransferCandidateMigrationPostgresTest {
    @TempDir
    static Path schemaDirectory;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transfer_test")
            .withUsername("postgres")
            .withPassword("test");

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("banking").defaultSchema("banking");
        TransferCandidateFlyway.locations(config, schemaDirectory);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    @BeforeEach
    void reset() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS banking CASCADE");
        }
    }

    @Test
    void candidateMigratesFromCleanAndFromV1WithSyntheticPredecessor() throws SQLException {
        Flyway clean = flyway(null);
        clean.migrate();
        assertThatCode(clean::validate).doesNotThrowAnyException();
        assertThat(queryLong("SELECT count(*) FROM banking.flyway_schema_history WHERE success AND version "
                + "IN ('1', '2', '3', '4', '5', '5.5', '6', '7', '8', '8.5', '9', '10', '11', '12')"))
                .isEqualTo(14);
        assertThat(queryLong("SELECT count(*) FROM banking.account")).isEqualTo(3);

        reset();
        flyway("1").migrate();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE banking.predecessor_data (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO banking.predecessor_data VALUES (42)");
        }
        Flyway upgrade = flyway(null);
        upgrade.migrate();
        assertThatCode(upgrade::validate).doesNotThrowAnyException();
        assertThat(queryLong("SELECT id FROM banking.predecessor_data")).isEqualTo(42);
        assertThat(queryLong("SELECT count(*) FROM banking.flyway_schema_history WHERE success AND version = '12'"))
                .isEqualTo(1);
    }

    @Test
    void catalogContainsExpectedForeignKeysIndexesAndTrigger() throws SQLException {
        flyway(null).migrate();
        assertThat(queryLong("SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid "
                + "JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='banking' "
                + "AND t.relname='transfer' AND c.contype='f' AND c.confdeltype='r'"))
                .isEqualTo(5);
        assertThat(queryLong("SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid "
                + "JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='banking' "
                + "AND t.relname='idempotency_record' AND c.contype='f' AND c.confdeltype='r'"))
                .isEqualTo(1);
        List<String> indexes = queryStrings("SELECT indexname FROM pg_indexes WHERE schemaname='banking'");
        assertThat(indexes).contains("uq_transfer_reference", "ix_transfer_customer_time", "ix_transfer_source_time",
                "ix_transfer_destination_time", "ix_transfer_status_time", "ix_transfer_correlation",
                "uq_transfer_step_up_challenge", "uq_idempotency_transfer", "ix_idempotency_expires",
                "ix_idempotency_status");
        assertThat(queryLong("SELECT count(*) FROM pg_trigger g JOIN pg_class t ON t.oid=g.tgrelid "
                + "JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='banking' "
                + "AND t.relname='transfer' AND NOT g.tgisinternal")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void rejectsInvalidValuesMissingReferencesAndRestrictedDeletes() throws SQLException {
        flyway(null).migrate();
        try (Connection connection = connect()) {
            insertTransfer(connection, "valid", 3, 4, null);
        }
        rejected("INSERT INTO banking.transfer(reference_no,customer_id,initiated_by_user_id,source_account_id,"
                + "destination_account_id,amount,correlation_id) VALUES ('bad-customer',999,2,3,4,10,'test')", "23503");
        rejected("INSERT INTO banking.transfer(reference_no,customer_id,initiated_by_user_id,source_account_id,"
                + "destination_account_id,amount,correlation_id) VALUES ('same-accounts',1,2,3,3,10,'test')", "23514");
        rejected("INSERT INTO banking.transfer(reference_no,customer_id,initiated_by_user_id,source_account_id,"
                + "destination_account_id,amount,correlation_id) VALUES ('bad-amount',1,2,3,4,0,'test')", "23514");
        rejected("INSERT INTO banking.transfer(reference_no,customer_id,initiated_by_user_id,source_account_id,"
                + "destination_account_id,amount,correlation_id) VALUES ('nan',1,2,3,4,'NaN','test')", "23514");
        rejected("INSERT INTO banking.transfer(reference_no,customer_id,initiated_by_user_id,source_account_id,"
                + "destination_account_id,amount,currency,correlation_id) VALUES ('bad-currency',1,2,3,4,10,'USD','test')", "23514");
        rejected("UPDATE banking.transfer SET status='FAILED' WHERE reference_no='valid'", "23514");
        rejected("DELETE FROM banking.account WHERE account_id=3", "23503");
    }

    @Test
    void challengeIsUniqueWhenPresentAndReferenceIsUnique() throws SQLException {
        flyway(null).migrate();
        try (Connection connection = connect()) {
            insertTransfer(connection, "null-a", 3, 4, null);
            insertTransfer(connection, "null-b", 3, 4, null);
            insertTransfer(connection, "challenge", 3, 4, 6L);
            assertThatThrownBy(() -> insertTransfer(connection, "duplicate-challenge", 3, 4, 6L))
                    .isInstanceOf(SQLException.class).satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
            assertThatThrownBy(() -> insertTransfer(connection, "null-a", 3, 4, null))
                    .isInstanceOf(SQLException.class).satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
        }
    }

    @Test
    void terminalTransitionIsConditionalAndProtectedAgainstDirectSqlMutation() throws SQLException {
        flyway(null).migrate();
        long id;
        try (Connection connection = connect()) {
            id = insertTransfer(connection, "terminal", 3, 4, null);
        }
        assertThat(execute("UPDATE banking.transfer SET status='COMPLETED', completed_at=now() WHERE transfer_id=" + id
                + " AND status='PENDING'")).isEqualTo(1);
        assertThat(execute("UPDATE banking.transfer SET status='FAILED', failed_at=now(), failure_code='FAIL' WHERE transfer_id="
                + id + " AND status='PENDING'")).isZero();
        rejectedAny("UPDATE banking.transfer SET status='PENDING', completed_at=NULL WHERE transfer_id=" + id);
        rejectedAny("UPDATE banking.transfer SET amount=20 WHERE transfer_id=" + id);
        rejectedAny("UPDATE banking.transfer SET source_account_id=5 WHERE transfer_id=" + id);
        rejectedAny("UPDATE banking.transfer SET destination_account_id=5 WHERE transfer_id=" + id);
        rejectedAny("UPDATE banking.transfer SET reference_no='other' WHERE transfer_id=" + id);
    }

    @Test
    void expiryAndTerminalTtlConstraintsRejectIncompleteRecords() throws SQLException {
        flyway(null).migrate();
        long id;
        try (Connection connection = connect()) {
            id = insertTransfer(connection, "ttl", 3, 4, null);
        }
        String columns = "(idempotency_key, request_hash, transfer_id, created_at, expires_at, ttl_t1_seconds, ttl_t1_config_version) ";
        String hash = "'" + "a".repeat(64) + "'";
        rejected("INSERT INTO banking.idempotency_record " + columns + "VALUES ('bad-expiry'," + hash + "," + id
                + ",now(),now(),86400,'v1')", "23514");
        rejected("INSERT INTO banking.idempotency_record " + columns + "VALUES ('bad-ttl'," + hash + "," + id
                + ",now(),now()+interval '1 day',86399,'v1')", "23514");
        execute("INSERT INTO banking.idempotency_record " + columns + "VALUES ('valid-key'," + hash + "," + id
                + ",now(),now()+interval '1 day',86400,'v1')");
        rejected("UPDATE banking.idempotency_record SET status='COMPLETED', completed_at=now(), response_status=201 "
                + "WHERE idempotency_key='valid-key'", "23514");
    }

    @Test
    void fencingAndRecoveryMetadataConstraintsRejectInvalidShapes() throws SQLException {
        flyway(null).migrate();
        long id;
        try (Connection connection = connect()) {
            id = insertTransfer(connection, "fence-shapes", 3, 4, null);
        }
        String where = " WHERE transfer_id=" + id;
        String token = "'00000000-0000-4000-8000-000000000001'::uuid";
        rejected("UPDATE banking.transfer SET attempt_epoch=-1" + where, "23514");
        rejected("UPDATE banking.transfer SET attempt_state='CLAIMED'" + where, "23514");
        rejected("UPDATE banking.transfer SET attempt_token=" + token + where, "23514");
        rejected("UPDATE banking.transfer SET attempt_state='CLAIMED', attempt_token=" + token
                + ", attempt_owner='   ', attempt_lease_expires_at=now()+interval '1 minute'" + where, "23514");
        rejected("UPDATE banking.transfer SET recovery_attempt_count=-1" + where, "23514");
        rejected("UPDATE banking.transfer SET recovery_attempt_count=1" + where, "23514");
        rejected("UPDATE banking.transfer SET recovery_first_attempt_at=now()" + where, "23514");
        assertThat(execute("UPDATE banking.transfer SET attempt_state='CLAIMED', attempt_epoch=1, attempt_token=" + token
                + ", attempt_owner='worker', attempt_lease_expires_at=now()+interval '1 minute', "
                + "recovery_attempt_count=1, recovery_first_attempt_at=now()" + where)).isEqualTo(1);
        assertThat(execute("UPDATE banking.transfer SET attempt_state='MANUAL', attempt_token=NULL, attempt_owner=NULL, "
                + "attempt_lease_expires_at=NULL" + where)).isEqualTo(1);
    }

    @Test
    void terminalTtlSnapshotsMustBeCompleteAndNonterminalCannotSetThem() throws SQLException {
        flyway(null).migrate();
        long id;
        try (Connection connection = connect()) {
            id = insertTransfer(connection, "ttl-shapes", 3, 4, null);
        }
        String hash = "'" + "a".repeat(64) + "'";
        execute("INSERT INTO banking.idempotency_record (idempotency_key,request_hash,transfer_id,expires_at,"
                + "ttl_t1_seconds,ttl_t1_config_version) VALUES ('ttl-shapes'," + hash + "," + id
                + ",now()+interval '2 days',172800,'v1')");
        String where = " WHERE idempotency_key='ttl-shapes'";
        rejected("UPDATE banking.idempotency_record SET ttl_terminal_seconds=86400,ttl_terminal_config_version='v2'"
                + where, "23514");
        rejected("UPDATE banking.idempotency_record SET status='FAILED',completed_at=now(),response_status=422,"
                + "ttl_terminal_seconds=86400" + where, "23514");
        rejected("UPDATE banking.idempotency_record SET status='FAILED',completed_at=now(),response_status=422,"
                + "ttl_terminal_seconds=86399,ttl_terminal_config_version='v2'" + where, "23514");
        rejected("UPDATE banking.idempotency_record SET status='FAILED',completed_at=now(),response_status=422,"
                + "ttl_terminal_seconds=86400,ttl_terminal_config_version='   '" + where, "23514");
        assertThat(execute("UPDATE banking.idempotency_record SET status='FAILED',completed_at=now(),response_status=422,"
                + "response_body='{}'::jsonb,ttl_terminal_seconds=86400,ttl_terminal_config_version='v2', "
                + "expires_at=GREATEST(expires_at, now()+interval '1 day')" + where)).isEqualTo(1);
        assertThat(queryLong("SELECT ttl_t1_seconds FROM banking.idempotency_record" + where)).isEqualTo(172800);
        assertThat(queryLong("SELECT count(*) FROM banking.idempotency_record" + where
                + " AND expires_at >= created_at + interval '2 days' AND ttl_terminal_seconds=86400"))
                .isEqualTo(1);
    }

    @Test
    void missingProviderForeignKeysAndDuplicateTransferRecordAreRejected() throws SQLException {
        flyway(null).migrate();
        rejected("INSERT INTO banking.transfer(reference_no,customer_id,initiated_by_user_id,source_account_id,"
                + "destination_account_id,amount,correlation_id) VALUES ('missing-user',1,999,3,4,10,'test')", "23503");
        rejected("INSERT INTO banking.transfer(reference_no,customer_id,initiated_by_user_id,source_account_id,"
                + "destination_account_id,amount,correlation_id) VALUES ('missing-source',1,2,999,4,10,'test')", "23503");
        rejected("INSERT INTO banking.transfer(reference_no,customer_id,initiated_by_user_id,source_account_id,"
                + "destination_account_id,amount,correlation_id) VALUES ('missing-destination',1,2,3,999,10,'test')", "23503");
        rejected("INSERT INTO banking.transfer(reference_no,customer_id,initiated_by_user_id,source_account_id,"
                + "destination_account_id,amount,correlation_id,step_up_challenge_id) "
                + "VALUES ('missing-challenge',1,2,3,4,10,'test',999)", "23503");
        rejected("INSERT INTO banking.idempotency_record (idempotency_key,request_hash,transfer_id,expires_at,"
                + "ttl_t1_seconds,ttl_t1_config_version) VALUES ('missing-transfer','" + "a".repeat(64)
                + "',999,now()+interval '1 day',86400,'v1')", "23503");
        long id;
        try (Connection connection = connect()) {
            id = insertTransfer(connection, "one-idempotency", 3, 4, 6L);
        }
        String hash = "'" + "a".repeat(64) + "'";
        String columns = "(idempotency_key,request_hash,transfer_id,expires_at,ttl_t1_seconds,ttl_t1_config_version)";
        execute("INSERT INTO banking.idempotency_record " + columns + " VALUES ('one'," + hash + "," + id
                + ",now()+interval '1 day',86400,'v1')");
        rejected("INSERT INTO banking.idempotency_record " + columns + " VALUES ('two'," + hash + "," + id
                + ",now()+interval '1 day',86400,'v1')", "23505");
        rejected("UPDATE banking.transfer SET reference_no='changed' WHERE transfer_id=" + id, "23514");
        rejected("DELETE FROM banking.customer WHERE customer_id=1", "23503");
        rejected("DELETE FROM banking.app_user WHERE user_id=2", "23503");
        rejected("DELETE FROM banking.account WHERE account_id=4", "23503");
        rejected("DELETE FROM banking.auth_challenge WHERE challenge_id=6", "23503");
        rejected("DELETE FROM banking.transfer WHERE transfer_id=" + id, "23503");
    }

    @Test
    void concurrentConditionalTerminalUpdatesHaveExactlyOneWinner() throws Exception {
        flyway(null).migrate();
        long id;
        try (Connection connection = connect()) {
            id = insertTransfer(connection, "terminal-race", 3, 4, null);
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> completed = executor.submit(() -> transitionTerminal(id, true, ready, start));
            Future<Integer> failed = executor.submit(() -> transitionTerminal(id, false, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(completed.get(15, TimeUnit.SECONDS) + failed.get(15, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(queryLong("SELECT count(*) FROM banking.transfer WHERE transfer_id=" + id
                    + " AND status IN ('COMPLETED','FAILED')")).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int transitionTerminal(long id, boolean complete, CountDownLatch ready, CountDownLatch start) throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '5s'");
            }
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("terminal race timed out");
            String sql = complete
                    ? "UPDATE banking.transfer SET status='COMPLETED',completed_at=now() WHERE transfer_id=? AND status='PENDING'"
                    : "UPDATE banking.transfer SET status='FAILED',failed_at=now(),failure_code='FAIL' WHERE transfer_id=? AND status='PENDING'";
            try (PreparedStatement update = connection.prepareStatement(sql)) {
                update.setLong(1, id);
                int count = update.executeUpdate();
                connection.commit();
                return count;
            }
        }
    }

    @Test
    void idempotencyLoserRollsBackTransferAndSideEffectBeforeReread() throws Exception {
        flyway(null).migrate();
        execute("CREATE TABLE banking.test_side_effect (transfer_id BIGINT PRIMARY KEY)");
        CountDownLatch inserted = new CountDownLatch(2);
        CountDownLatch race = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> compete("race-a", inserted, race));
            Future<Boolean> second = executor.submit(() -> compete("race-b", inserted, race));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            race.countDown();
            boolean firstWon = first.get(15, TimeUnit.SECONDS);
            boolean secondWon = second.get(15, TimeUnit.SECONDS);
            assertThat(firstWon).isNotEqualTo(secondWon);
        } finally {
            race.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(queryLong("SELECT count(*) FROM banking.transfer")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM banking.idempotency_record WHERE idempotency_key='race-key'"))
                .isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM banking.test_side_effect")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM banking.transfer t JOIN banking.idempotency_record i USING (transfer_id) "
                + "JOIN banking.test_side_effect s USING (transfer_id)")).isEqualTo(1);
    }

    @Test
    void persistenceProxyMarksOuterTransactionRollbackOnlyOnCaughtKeyConflict() throws SQLException {
        flyway(null).migrate();
        execute("CREATE TABLE banking.test_side_effect (id BIGINT PRIMARY KEY)");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestTransactionConfig.class)) {
            TransferPersistence persistence = context.getBean(TransferPersistence.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            TransferPersistence.PendingTransfer winner = tx.execute(status -> persistence.insertPending(input("winner", "key", null)));
            assertThat(winner).isNotNull();
            assertThatThrownBy(() -> tx.execute(status -> {
                jdbc.update("INSERT INTO banking.test_side_effect VALUES (1)");
                try {
                    persistence.insertPending(input("loser", "key", null));
                    throw new AssertionError("expected duplicate key");
                } catch (TransferPersistence.DuplicateIdempotencyKeyException expected) {
                    // Even a caller that swallows the error cannot commit the loser transfer.
                }
                return null;
            })).isInstanceOf(UnexpectedRollbackException.class);
            assertThat(queryLong("SELECT count(*) FROM banking.transfer")).isEqualTo(1);
            assertThat(queryLong("SELECT count(*) FROM banking.idempotency_record")).isEqualTo(1);
            assertThat(queryLong("SELECT count(*) FROM banking.test_side_effect")).isZero();
            Long rereadId = tx.execute(status -> jdbc.queryForObject(
                    "SELECT transfer_id FROM banking.idempotency_record WHERE idempotency_key='key'", Long.class));
            assertThat(rereadId).isEqualTo(winner.id());

            // The distinct reference violation remains a unique violation, not key replay.
            assertThatThrownBy(() -> tx.execute(status -> persistence.insertPending(input("winner", "other-key", null))))
                    .isInstanceOf(DuplicateKeyException.class);
            tx.execute(status -> persistence.insertPending(input("challenge", "challenge-key", 6L)));
            assertThatThrownBy(() -> tx.execute(status -> persistence.insertPending(input("another-challenge", "new-key", 6L))))
                    .isInstanceOf(DuplicateKeyException.class);
            assertThatThrownBy(() -> persistence.insertPending(input("no-transaction", "free-key", null)))
                    .isInstanceOf(IllegalTransactionStateException.class);
        }
    }

    private static TransferPersistence.PendingInsert input(String reference, String key, Long challenge) {
        return new TransferPersistence.PendingInsert(new TransferIdentity(reference, 1, 2, 3, 4, challenge),
                new TransferAmount(new BigDecimal("10.00"), "VND"), null, "test", key, "a".repeat(64), 86400, "v1");
    }

    @TestConfiguration
    @EnableTransactionManagement
    static class TestTransactionConfig {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        TransferPersistence transferPersistence(JdbcTemplate jdbcTemplate) {
            return new TransferPersistence(jdbcTemplate);
        }
    }

    private boolean compete(String reference, CountDownLatch inserted, CountDownLatch race) throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '5s'");
            }
            long id = insertTransfer(connection, reference, 3, 4, null);
            try (PreparedStatement sideEffect = connection.prepareStatement("INSERT INTO banking.test_side_effect VALUES (?)")) {
                sideEffect.setLong(1, id);
                sideEffect.executeUpdate();
            }
            inserted.countDown();
            if (!race.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("race barrier timed out");
            }
            try (PreparedStatement attempt = connection.prepareStatement(
                    "INSERT INTO banking.idempotency_record (idempotency_key,request_hash,transfer_id,expires_at,"
                            + "ttl_t1_seconds,ttl_t1_config_version) VALUES ('race-key',?, ?,now()+interval '1 day',86400,'v1') "
                            + "ON CONFLICT (idempotency_key) DO NOTHING RETURNING transfer_id")) {
                attempt.setString(1, "a".repeat(64));
                attempt.setLong(2, id);
                try (ResultSet result = attempt.executeQuery()) {
                    if (result.next()) {
                        connection.commit();
                        return true;
                    }
                }
            }
            connection.rollback();
            try (PreparedStatement read = connection.prepareStatement(
                    "SELECT transfer_id FROM banking.idempotency_record WHERE idempotency_key='race-key'")) {
                try (ResultSet result = read.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isNotEqualTo(id);
                }
            }
            connection.rollback();
            return false;
        }
    }

    private static long insertTransfer(Connection connection, String reference, long source, long destination, Long challenge)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO banking.transfer (reference_no,customer_id,initiated_by_user_id,source_account_id,"
                        + "destination_account_id,amount,correlation_id,step_up_challenge_id) VALUES (?,1,2,?,?,10,'test',?) "
                        + "RETURNING transfer_id")) {
            statement.setString(1, reference);
            statement.setLong(2, source);
            statement.setLong(3, destination);
            if (challenge == null) {
                statement.setNull(4, java.sql.Types.BIGINT);
            } else {
                statement.setLong(4, challenge);
            }
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int execute(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static long queryLong(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static List<String> queryStrings(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (result.next()) {
                values.add(result.getString(1));
            }
            return values;
        }
    }

    private static void rejected(String sql, String state) {
        assertThatThrownBy(() -> execute(sql)).isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(state));
    }

    private static void rejectedAny(String sql) {
        assertThatThrownBy(() -> execute(sql)).isInstanceOf(SQLException.class);
    }
}
