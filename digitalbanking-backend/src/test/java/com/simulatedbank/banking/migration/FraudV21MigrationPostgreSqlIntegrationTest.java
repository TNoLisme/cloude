package com.simulatedbank.banking.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class FraudV21MigrationPostgreSqlIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("limit/init-roles.sql");

    @Test
    void reconcilesDuplicateActiveVersionsAndUpgradesWithoutReplacingManagedRules() {
        var v20 = configure().target("20").load();
        assertThat(v20.migrate().migrationsExecuted).isEqualTo(20);
        var original = checksums(v20);
        JdbcTemplate owner = jdbc("banking_migrator", "test_migrator");
        Long actor = owner.queryForObject("""
                INSERT INTO banking.app_user(username,password_hash,user_type,status)
                VALUES ('fraud-migration-operator','!DISABLED','STAFF','DISABLED') RETURNING user_id
                """, Long.class);
        owner.update("""
                INSERT INTO banking.fraud_rule_config (rule_code,version,rule_name,rule_type,threshold_amount,
                    severity,effective_from,created_by)
                VALUES ('LARGE_VALUE_TRANSFER',2,'Managed baseline','LARGE_VALUE',7000000,'HIGH','2026-01-01',?),
                       ('LARGE_VALUE_TRANSFER',3,'Managed later','LARGE_VALUE',8000000,'LOW','2027-01-01',?)
                """, actor, actor);
        var v21 = configure().load();
        assertThatThrownBy(v21::migrate).isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("Reconcile duplicate open-ended enabled fraud rule versions");
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM banking.fraud_rule_config WHERE enabled AND effective_to IS NULL
                """, Integer.class)).isEqualTo(2);
        owner.update("""
                UPDATE banking.fraud_rule_config SET effective_to='2027-01-01'
                WHERE rule_code='LARGE_VALUE_TRANSFER' AND version=2
                """);
        assertThat(v21.migrate().migrationsExecuted).isEqualTo(1);
        v21.validate();
        assertThat(v21.migrate().migrationsExecuted).isZero();
        assertThat(checksums(v21)).containsAllEntriesOf(original).hasSize(21);
        JdbcTemplate app = jdbc("banking_app", "test_app");
        assertThat(app.queryForList("""
                SELECT version || ':' || threshold_amount || ':' || severity || ':' || auto_block
                FROM banking.fraud_rule_config WHERE rule_code='LARGE_VALUE_TRANSFER' ORDER BY version
                """, String.class)).containsExactly("2:7000000.00:HIGH:false", "3:8000000.00:LOW:false");
        assertThat(app.queryForObject("""
                SELECT count(*) FROM banking.fraud_rule_config r JOIN banking.app_user u ON u.user_id=r.created_by
                WHERE r.rule_code='HIGH_FREQUENCY_TRANSFER' AND r.version=1 AND r.threshold_count=5
                  AND r.window_seconds=600 AND r.enabled AND NOT r.auto_block
                  AND u.status='DISABLED' AND u.username='__fraud_rule_bootstrap__'
                  AND NOT EXISTS (SELECT 1 FROM banking.user_role ur WHERE ur.user_id=u.user_id)
                """, Integer.class)).isEqualTo(1);
        assertThat(app.queryForObject("""
                SELECT has_table_privilege(current_user, 'banking.fraud_transfer_intent_history', 'SELECT')
                    AND NOT has_table_privilege(current_user, 'banking.fraud_transfer_intent_history', 'INSERT')
                    AND NOT has_table_privilege(current_user, 'banking.fraud_transfer_intent_history', 'UPDATE')
                    AND NOT has_table_privilege(current_user, 'banking.fraud_transfer_intent_history', 'DELETE')
                """, Boolean.class)).isTrue();
        assertThatThrownBy(() -> owner.update("""
                UPDATE banking.fraud_rule_config SET effective_to=effective_from WHERE rule_code='HIGH_FREQUENCY_TRANSFER'
                """)).hasStackTraceContaining("ck_fraud_effective_interval");
        assertThatThrownBy(() -> owner.update("""
                UPDATE banking.fraud_rule_config SET auto_block=TRUE WHERE rule_code='HIGH_FREQUENCY_TRANSFER'
                """)).hasStackTraceContaining("ck_fraud_auto_block_type");
        assertThatThrownBy(() -> owner.update("""
                UPDATE banking.fraud_rule_config SET effective_to=NULL
                WHERE rule_code='LARGE_VALUE_TRANSFER' AND version=2
                """)).hasStackTraceContaining("uq_fraud_rule_active");
        System.out.println("INT07 schema: V1–V20 checksums unchanged, V21 re-run is empty, managed versions preserved.");
    }

    private static JdbcTemplate jdbc(String user, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), user, password));
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration configure() {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "banking_migrator", "test_migrator")
                .schemas("banking").defaultSchema("banking").locations("classpath:db/migration");
    }

    private static Map<String, Integer> checksums(Flyway flyway) {
        return Arrays.stream(flyway.info().applied()).filter(info -> info.getVersion() != null)
                .collect(Collectors.toMap(info -> info.getVersion().getVersion(), MigrationInfo::getChecksum));
    }
}
