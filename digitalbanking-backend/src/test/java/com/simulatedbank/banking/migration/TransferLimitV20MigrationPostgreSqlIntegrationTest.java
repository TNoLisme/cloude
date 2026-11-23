package com.simulatedbank.banking.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.support.IntegratedLimitReferences;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TransferLimitV20MigrationPostgreSqlIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("limit/init-roles.sql");

    @Test
    void upgradesV19WithoutChangingExistingChecksumsUsageOrTransferHistory() throws Exception {
        var before = configureFlyway().target("19").load();
        assertThat(before.migrate().migrationsExecuted).isEqualTo(19);
        var priorChecksums = checksums(before);
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
             var statement = connection.createStatement()) {
            IntegratedLimitReferences.seed(connection, false);
            statement.execute("""
                    INSERT INTO banking.transfer (reference_no, customer_id, initiated_by_user_id,
                        source_account_id, destination_account_id, amount, status, completed_at, correlation_id)
                    VALUES ('pre-v20', 11, 11, 21, 22, 170, 'COMPLETED', now(), 'migration-it')
                    """);
            statement.execute("""
                    INSERT INTO banking.limit_usage(customer_id, usage_date, used_amount, transfer_count)
                    VALUES (11, DATE '2026-11-18', 170, 1)
                    """);
        }

        var after = configureFlyway().target("20").load();
        assertThat(after.migrate().migrationsExecuted).isEqualTo(1);
        after.validate();
        var finalChecksums = checksums(after);
        assertThat(finalChecksums).containsAllEntriesOf(priorChecksums).hasSize(20);
        assertThat(after.info().current().getVersion().getVersion()).isEqualTo("20");
        assertThat(after.migrate().migrationsExecuted).isZero();
        var app = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), "banking_app", "test_app"));
        assertThat(app.queryForObject("SELECT status FROM banking.transfer WHERE reference_no = 'pre-v20'",
                String.class)).isEqualTo("COMPLETED");
        assertThat(app.queryForObject("SELECT used_amount FROM banking.limit_usage WHERE customer_id = 11",
                BigDecimal.class)).isEqualByComparingTo("170");
        assertThat(app.queryForObject("SELECT transfer_count FROM banking.limit_usage WHERE customer_id = 11",
                Integer.class)).isEqualTo(1);
        assertThat(app.queryForObject("SELECT count(*) FROM banking.limit_transfer_reservation", Integer.class)).isZero();
        assertThat(app.queryForObject("SELECT count(*) FROM banking.limit_transfer_usage_effect", Integer.class)).isZero();
        assertThat(app.queryForObject("""
                SELECT has_column_privilege(current_user, 'banking.limit_transfer_reservation', 'state', 'UPDATE')
                       AND NOT has_column_privilege(current_user, 'banking.limit_transfer_reservation', 'amount', 'UPDATE')
                       AND NOT has_table_privilege(current_user, 'banking.limit_transfer_usage_effect', 'DELETE')
                """, Boolean.class)).isTrue();

        System.out.printf("PostgreSQL %s, Flyway V20 checksum %s%n",
                app.queryForObject("SHOW server_version", String.class), finalChecksums.get("20"));
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration configureFlyway() {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), "banking_migrator", "test_migrator")
                .schemas("banking").defaultSchema("banking").locations("classpath:db/migration");
    }

    private static Map<String, Integer> checksums(Flyway flyway) {
        return Arrays.stream(flyway.info().applied()).filter(info -> info.getVersion() != null)
                .collect(Collectors.toMap(info -> info.getVersion().getVersion(), MigrationInfo::getChecksum));
    }
}
