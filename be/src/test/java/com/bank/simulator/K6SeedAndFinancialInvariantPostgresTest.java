package com.bank.simulator;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class K6SeedAndFinancialInvariantPostgresTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static final String SYNTHETIC_BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @BeforeAll
    static void setUp() {
        postgres = DisposablePostgresTestSupport.newDisposablePostgres("banking_k6_verify");
        postgres.start();

        // Run Flyway migrations V1 through V7
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // Copy seed and invariant SQL files into container
        Path seedPath = resolveArtifactPath("scripts/seed-k6-accounts.sql");
        Path invariantPath = resolveArtifactPath("queries/verify-financial-invariant.sql");

        postgres.copyFileToContainer(MountableFile.forHostPath(seedPath), "/tmp/seed-k6-accounts.sql");
        postgres.copyFileToContainer(MountableFile.forHostPath(invariantPath), "/tmp/verify-financial-invariant.sql");
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static Path resolveArtifactPath(String relative) {
        Path direct = Path.of(relative);
        if (Files.exists(direct)) {
            return direct.toAbsolutePath();
        }
        Path parent = Path.of("..", relative);
        if (Files.exists(parent)) {
            return parent.toAbsolutePath();
        }
        throw new IllegalStateException("Required SQL artifact not found: " + relative);
    }

    @Test
    void seedRejectsInvalidHashAndRollsBack() throws Exception {
        DisposablePostgresTestSupport.clearBusinessTables(jdbc, postgres);

        ExecResult result = postgres.execInContainer(
                "sh", "-c",
                "export K6_PASSWORD_HASH='invalid_hash_value' && " +
                "export K6_PIN_HASH='invalid_pin_value' && " +
                "psql -U " + postgres.getUsername() + " -d " + postgres.getDatabaseName() +
                " -v ON_ERROR_STOP=1 -f /tmp/seed-k6-accounts.sql"
        );

        assertThat(result.getExitCode()).isNotZero();
        assertThat(result.getStderr()).contains("K6_PASSWORD_HASH and K6_PIN_HASH must be valid BCrypt hashes");

        // Verify transaction rolled back cleanly: 0 rows inserted
        Integer userCount = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
        Integer customerCount = jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class);
        Integer accountCount = jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class);
        assertThat(userCount).isZero();
        assertThat(customerCount).isZero();
        assertThat(accountCount).isZero();
    }

    @Test
    void seedCreatesExact100AccountsAndInvariantQueryPasses() throws Exception {
        DisposablePostgresTestSupport.clearBusinessTables(jdbc, postgres);

        // 1. Run seed script with valid BCrypt hashes
        ExecResult seedResult = postgres.execInContainer(
                "sh", "-c",
                "export K6_PASSWORD_HASH='" + SYNTHETIC_BCRYPT_HASH + "' && " +
                "export K6_PIN_HASH='" + SYNTHETIC_BCRYPT_HASH + "' && " +
                "psql -U " + postgres.getUsername() + " -d " + postgres.getDatabaseName() +
                " -v ON_ERROR_STOP=1 -f /tmp/seed-k6-accounts.sql"
        );

        assertThat(seedResult.getExitCode()).isZero();
        assertThat(seedResult.getStdout())
                .contains("seeded_customers")
                .contains("100")
                .contains("1000000000");

        // 2. Validate row counts and schema integrity in PostgreSQL
        Integer userCount = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
        Integer customerCount = jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class);
        Integer pinCount = jdbc.queryForObject("SELECT count(*) FROM customer_pins", Integer.class);
        Integer accountCount = jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class);
        Long totalBalance = jdbc.queryForObject("SELECT sum(balance)::bigint FROM accounts", Long.class);

        assertThat(userCount).isEqualTo(100);
        assertThat(customerCount).isEqualTo(100);
        assertThat(pinCount).isEqualTo(100);
        assertThat(accountCount).isEqualTo(100);
        assertThat(totalBalance).isEqualTo(1_000_000_000L);

        // Verify account range and customer ranges
        Integer minPhone = jdbc.queryForObject(
                "SELECT min(phone::bigint) FROM users WHERE phone LIKE '0800000%'", Integer.class);
        Integer maxPhone = jdbc.queryForObject(
                "SELECT max(phone::bigint) FROM users WHERE phone LIKE '0800000%'", Integer.class);
        assertThat(minPhone).isEqualTo(800000001);
        assertThat(maxPhone).isEqualTo(800000100);

        String minAccount = jdbc.queryForObject("SELECT min(account_number) FROM accounts", String.class);
        String maxAccount = jdbc.queryForObject("SELECT max(account_number) FROM accounts", String.class);
        assertThat(minAccount).isEqualTo("700000000001");
        assertThat(maxAccount).isEqualTo("700000000100");

        // 3. Run verify-financial-invariant.sql via psql with expected balance 1,000,000,000
        ExecResult invariantResult = postgres.execInContainer(
                "sh", "-c",
                "export K6_EXPECTED_BALANCE_VND=1000000000 && " +
                "psql -U " + postgres.getUsername() + " -d " + postgres.getDatabaseName() +
                " -v ON_ERROR_STOP=1 -f /tmp/verify-financial-invariant.sql"
        );

        assertThat(invariantResult.getExitCode()).isZero();
        assertThat(invariantResult.getStdout())
                .contains("account_count")
                .contains("current_balance_vnd")
                .contains("invariant_pass")
                .contains("t"); // psql boolean true displays as 't'

        // 4. Also execute the exact query via JDBC to verify strongly typed values
        String invariantSql = """
                WITH fixture_accounts AS (
                    SELECT id, balance
                    FROM accounts
                    WHERE account_number BETWEEN '700000000001' AND '700000000100'
                ), totals AS (
                    SELECT count(*) AS account_count,
                           coalesce(sum(balance), 0)::numeric(19,0) AS current_balance_vnd,
                           count(*) FILTER (WHERE balance < 0) AS negative_balance_count
                    FROM fixture_accounts
                ), transfer_duplicates AS (
                    SELECT count(*) AS duplicate_idempotency_resources
                    FROM (
                        SELECT idempotency_record_id
                        FROM transfers
                        WHERE status = 'COMPLETED'
                          AND idempotency_record_id IS NOT NULL
                        GROUP BY idempotency_record_id
                        HAVING count(*) > 1
                    ) duplicates
                )
                SELECT account_count,
                       current_balance_vnd,
                       ?::numeric(19,0) AS expected_balance_vnd,
                       current_balance_vnd - ?::numeric(19,0) AS delta_vnd,
                       negative_balance_count,
                       duplicate_idempotency_resources,
                       account_count = 100
                           AND current_balance_vnd = ?::numeric(19,0)
                           AND negative_balance_count = 0
                           AND duplicate_idempotency_resources = 0 AS invariant_pass
                FROM totals
                CROSS JOIN transfer_duplicates
                """;

        Map<String, Object> row = jdbc.queryForMap(invariantSql, 1_000_000_000L, 1_000_000_000L, 1_000_000_000L);
        assertThat(((Number) row.get("account_count")).intValue()).isEqualTo(100);
        assertThat(((Number) row.get("current_balance_vnd")).longValue()).isEqualTo(1_000_000_000L);
        assertThat(((Number) row.get("delta_vnd")).longValue()).isEqualTo(0L);
        assertThat(((Number) row.get("negative_balance_count")).intValue()).isEqualTo(0);
        assertThat(((Number) row.get("duplicate_idempotency_resources")).intValue()).isEqualTo(0);
        assertThat((Boolean) row.get("invariant_pass")).isTrue();
    }
}
