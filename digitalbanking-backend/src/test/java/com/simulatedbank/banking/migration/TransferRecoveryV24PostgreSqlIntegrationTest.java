package com.simulatedbank.banking.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.support.IntegratedLimitReferences;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferRecoveryStore;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TransferRecoveryV24PostgreSqlIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("limit/init-roles.sql");

    @Test
    void upgradesV23WithoutInventingHistoryAndRestrictsRuntimePurge() throws Exception {
        var before = flyway("23");
        assertThat(before.migrate().migrationsExecuted).isEqualTo(23);
        var checksums = checksums(before);
        var fixture = new DriverManagerDataSource(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
        try (var connection = fixture.getConnection()) {
            IntegratedLimitReferences.seed(connection, false);
        }
        var observer = new JdbcTemplate(fixture);
        observer.update("""
                INSERT INTO banking.transfer (reference_no, customer_id, initiated_by_user_id, source_account_id,
                        destination_account_id, amount, correlation_id, status, completed_at, created_at)
                VALUES ('pre24-pending', 11, 11, 21, 22, 170, 'v24-upgrade', 'PENDING', NULL, now()-interval '2 days'),
                       ('pre24-posted', 11, 11, 21, 22, 170, 'v24-upgrade', 'COMPLETED',
                            now()-interval '2 days', now()-interval '3 days')
                """);
        observer.update("""
                INSERT INTO banking.idempotency_record (idempotency_key, request_hash, transfer_id, status,
                    response_status, created_at, completed_at, expires_at, ttl_t1_seconds, ttl_t1_config_version,
                    ttl_terminal_seconds, ttl_terminal_config_version)
                SELECT reference_no, repeat('a',64), transfer_id,
                    CASE WHEN status='COMPLETED' THEN 'COMPLETED' ELSE 'RECEIVED' END,
                    CASE WHEN status='COMPLETED' THEN 201 END, created_at, completed_at,
                    created_at+interval '24 hours', 86400, 'v23',
                    CASE WHEN status='COMPLETED' THEN 86400 END,
                    CASE WHEN status='COMPLETED' THEN 'v23' END
                FROM banking.transfer WHERE correlation_id='v24-upgrade'
                """);

        var after = flyway("24");
        assertThat(after.migrate().migrationsExecuted).isEqualTo(1);
        after.validate();
        assertThat(checksums(after)).containsAllEntriesOf(checksums).hasSize(24);
        assertThat(after.migrate().migrationsExecuted).isZero();

        var runtime = new DriverManagerDataSource(postgres.getJdbcUrl(), "banking_app", "test_app");
        var app = new JdbcTemplate(runtime);
        assertThat(app.queryForObject("""
                SELECT count(*) FROM banking.transfer WHERE recovery_t1_policy IS NULL AND recovery_t1_date IS NULL
                """, Integer.class)).isEqualTo(2);
        assertThat(app.queryForObject("SELECT count(*) FROM banking.account_transfer_movement", Integer.class)).isZero();
        assertThat(app.queryForObject("""
                SELECT has_table_privilege(current_user, 'banking.account_transfer_movement', 'SELECT')
                   AND has_table_privilege(current_user, 'banking.account_transfer_movement', 'INSERT')
                   AND NOT has_table_privilege(current_user, 'banking.account_transfer_movement', 'UPDATE,DELETE,TRUNCATE')
                """, Boolean.class)).isTrue();
        assertThatThrownBy(() -> app.update("DELETE FROM banking.idempotency_record WHERE status='RECEIVED'"))
                .isInstanceOf(DataAccessException.class);
        var store = new TransferRecoveryStore(app);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(runtime));
        int removed = tx.execute(status -> store.purge(100));
        assertThat(removed).isEqualTo(1);
        assertThat(app.queryForList("SELECT status FROM banking.idempotency_record", String.class))
                .containsExactly("RECEIVED");
        assertThat(app.queryForList("SELECT status FROM banking.transfer ORDER BY status", String.class))
                .containsExactly("COMPLETED", "PENDING");
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), "banking_migrator", "test_migrator")
                .schemas("banking").defaultSchema("banking").locations("classpath:db/migration").target(target).load();
    }

    private static Map<String, Integer> checksums(Flyway flyway) {
        return Stream.of(flyway.info().applied()).filter(info -> info.getVersion() != null)
                .collect(Collectors.toMap(info -> info.getVersion().getVersion(), MigrationInfo::getChecksum));
    }
}
