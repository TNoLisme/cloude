package com.simulatedbank.banking.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import com.simulatedbank.banking.support.IntegratedLimitReferences;
import com.simulatedbank.banking.support.SqlFixture;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class LimitPersistenceIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("limit/init-roles.sql");

    static SqlFixture jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("banking").defaultSchema("banking").load().migrate();
        jdbc = new SqlFixture(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("TRUNCATE banking.limit_config, banking.limit_usage RESTART IDENTITY");
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            IntegratedLimitReferences.seed(connection, false);
        }
    }

    @Test
    void integratedMigrationCreatesLimitTablesWithDefaultsAndRealReferences() {
        jdbc.update(configSql("NULL", "DEFAULT", "DEFAULT", "NULL", "NULL", "100", "1000"));
        var row = jdbc.queryForMap("SELECT * FROM banking.limit_config");
        assertThat(row).containsEntry("status", "PENDING_APPROVAL").containsEntry("version", 1)
                .containsEntry("currency", "VND").containsEntry("customer_id", 11L);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
        jdbc.update("INSERT INTO banking.limit_usage(customer_id,usage_date) VALUES (11,'2026-11-15')");
        assertThat(jdbc.queryForMap("SELECT * FROM banking.limit_usage"))
                .containsEntry("currency", "VND").containsEntry("transfer_count", 0);
        assertThat(jdbc.queryForObject("SELECT used_amount FROM banking.limit_usage", java.math.BigDecimal.class))
                .isEqualByComparingTo("0");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='banking'",
                String.class)).contains("approval_request", "customer", "account", "app_user");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_schema='banking' AND table_name IN ('limit_config','limit_usage') "
                + "AND constraint_type='FOREIGN KEY'", Integer.class)).isEqualTo(7);
    }

    @Test
    void upgradeFromV1PreservesExistingSchemaAndInstallsLimitMigrations() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE limit_upgrade");
        }
        String url = postgres.getJdbcUrl().replace("/test", "/limit_upgrade");
        var baseline = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .schemas("banking").defaultSchema("banking").target("1").load();
        assertThat(baseline.migrate().migrationsExecuted).isEqualTo(1);
        var upgradeJdbc = new SqlFixture(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
        upgradeJdbc.execute("COMMENT ON SCHEMA banking IS 'preexisting baseline'");
        var latest = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .schemas("banking").defaultSchema("banking").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(18);
        assertThat(latest.validateWithResult().validationSuccessful).isTrue();
        assertThat(upgradeJdbc.queryForObject("SELECT obj_description('banking'::regnamespace, 'pg_namespace')", String.class))
                .isEqualTo("preexisting baseline");
        assertThat(upgradeJdbc.queryForObject("SELECT count(*) FROM banking.limit_config", Integer.class)).isZero();
    }

    @Test
    void upgradeRejectsExistingNanWithoutChangingHistoryOrGuessingAMonetaryValue() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE limit_nan_upgrade");
        }
        String url = postgres.getJdbcUrl().replace("/test", "/limit_nan_upgrade");
        Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .schemas("banking").defaultSchema("banking").target("18").load().migrate();
        try (var connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
            IntegratedLimitReferences.seed(connection, false);
        }
        var old = new SqlFixture(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
        old.update(configSql("NULL", "DEFAULT", "DEFAULT", "NULL", "NULL", "100", "'NaN'"));
        var latest = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .schemas("banking").defaultSchema("banking").load();
        assertThatThrownBy(latest::migrate).hasMessageContaining("ck_limit_amounts_finite");
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("18");
        assertThat(old.queryForObject("SELECT count(*) FROM banking.flyway_schema_history WHERE version IS NOT NULL", Integer.class))
                .isEqualTo(18);
        assertThat(old.queryForObject("SELECT daily_limit::text FROM banking.limit_config", String.class)).isEqualTo("NaN");
    }

    @Test
    void activeUniquenessIsPerCustomerOrPerAccountAndHistoryIsRetained() {
        jdbc.update(configSql("NULL", "'ACTIVE'", "1", "2", "now()", "100", "1000"));
        assertRejected(configSql("NULL", "'ACTIVE'", "2", "2", "now()", "100", "1000"));
        jdbc.update(configSql("21", "'ACTIVE'", "1", "2", "now()", "100", "1000"));
        assertRejected(configSql("21", "'ACTIVE'", "2", "2", "now()", "100", "1000"));
        jdbc.update(configSql("22", "'ACTIVE'", "1", "2", "now()", "100", "1000"));
        jdbc.update(configSql("NULL", "'SUPERSEDED'", "2", "2", "now()", "100", "1000"));
        jdbc.update(configSql("NULL", "'REJECTED'", "3", "NULL", "NULL", "100", "1000"));
        jdbc.update(configSql("NULL", "'PENDING_APPROVAL'", "4", "NULL", "NULL", "100", "1000"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_config", Integer.class)).isEqualTo(6);
        jdbc.update("INSERT INTO banking.limit_config(customer_id,per_transaction_limit,daily_limit,effective_from,created_by) "
                + "VALUES(12,100,1000,now(),1)");
    }

    @Test
    void usageUniquenessDistinguishesNullScopeAccountAndDate() {
        jdbc.update("INSERT INTO banking.limit_usage(customer_id,account_id,usage_date) VALUES (11,NULL,'2026-11-15')");
        assertRejected("INSERT INTO banking.limit_usage(customer_id,account_id,usage_date) VALUES (11,NULL,'2026-11-15')");
        jdbc.update("INSERT INTO banking.limit_usage(customer_id,account_id,usage_date) VALUES (11,21,'2026-11-15')");
        assertRejected("INSERT INTO banking.limit_usage(customer_id,account_id,usage_date) VALUES (12,21,'2026-11-15')");
        jdbc.update("INSERT INTO banking.limit_usage(customer_id,account_id,usage_date) VALUES "
                + "(11,22,'2026-11-15'),(12,NULL,'2026-11-15'),(11,NULL,'2026-11-16'),(11,21,'2026-11-16')");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_usage", Integer.class)).isEqualTo(6);
    }

    static Stream<String> invalidConfigurations() {
        return Stream.of(
                "per_transaction_limit=0", "per_transaction_limit=-1", "daily_limit=0", "daily_limit=-1",
                "per_transaction_limit=1001", "currency='USD'", "version=0", "version=-1", "status='INACTIVE'",
                "status='GLOBAL'", "effective_to=effective_from", "effective_to=effective_from-interval '1 second'",
                "approved_by=created_by", "status='ACTIVE'", "status='SUPERSEDED'",
                "status='ACTIVE',approved_by=2", "status='ACTIVE',approved_at=now()",
                "status='REJECTED',approved_by=2", "status='REJECTED',approved_at=now()",
                "per_transaction_limit='NaN'", "daily_limit='NaN'", "daily_limit=100000000000000000.00",
                "customer_id=NULL", "effective_from=NULL", "created_by=NULL", "status=NULL", "currency=NULL");
    }

    @ParameterizedTest
    @MethodSource("invalidConfigurations")
    void invalidConfigurationIsRejectedByPostgres(String assignment) {
        jdbc.update(configSql("NULL", "DEFAULT", "DEFAULT", "NULL", "NULL", "100", "1000"));
        assertRejected("UPDATE banking.limit_config SET " + assignment);
    }

    static Stream<String> invalidUsage() {
        return Stream.of("used_amount=-0.01", "transfer_count=-1", "currency='USD'", "used_amount='NaN'",
                "used_amount=100000000000000000.00", "customer_id=NULL", "usage_date=NULL", "currency=NULL");
    }

    @ParameterizedTest
    @MethodSource("invalidUsage")
    void invalidUsageIsRejectedByPostgres(String assignment) {
        jdbc.update("INSERT INTO banking.limit_usage(customer_id,usage_date) VALUES (11,?)", LocalDate.of(2026, 11, 15));
        assertRejected("UPDATE banking.limit_usage SET " + assignment);
    }

    @Test
    void applicationRoleCanPersistAndReadButCannotHardDeleteHistory() {
        var app = new SqlFixture(new DriverManagerDataSource(postgres.getJdbcUrl(), "banking_app", "test_app"));
        app.update(configSql("NULL", "DEFAULT", "DEFAULT", "NULL", "NULL", "100", "1000"));
        app.update("INSERT INTO banking.limit_usage(customer_id,usage_date) VALUES (11,'2026-11-15')");
        assertThat(app.queryForObject("SELECT count(*) FROM banking.limit_config", Integer.class)).isEqualTo(1);
        assertThat(app.queryForObject("SELECT count(*) FROM banking.limit_usage", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> app.execute("DELETE FROM banking.limit_config")).hasRootCauseInstanceOf(java.sql.SQLException.class)
                .rootCause().extracting("SQLState").isEqualTo("42501");
        assertThatThrownBy(() -> app.execute("TRUNCATE banking.limit_usage")).hasRootCauseInstanceOf(java.sql.SQLException.class)
                .rootCause().extracting("SQLState").isEqualTo("42501");
    }

    private static void assertRejected(String sql) {
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String configSql(String account, String status, String version, String checker,
            String approvedAt, String perTransaction, String daily) {
        return "INSERT INTO banking.limit_config(customer_id,account_id,per_transaction_limit,daily_limit,"
                + "effective_from,status,version,created_by,approved_by,approved_at) VALUES(11," + account + ","
                + perTransaction + "," + daily + ",'2026-11-15T00:00:00Z'," + status + "," + version + ",1,"
                + checker + "," + approvedAt + ")";
    }
}
