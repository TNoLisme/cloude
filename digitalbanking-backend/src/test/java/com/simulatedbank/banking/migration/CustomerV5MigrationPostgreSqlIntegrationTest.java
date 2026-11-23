package com.simulatedbank.banking.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-CUS-IMP-01: verifies the V5 Customer migration against a fresh PostgreSQL 16 database.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class CustomerV5MigrationPostgreSqlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("digital_banking")
            .withUsername("postgres")
            .withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.schemas", () -> "banking");
        registry.add("spring.flyway.default-schema", () -> "banking");
    }

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private TransactionTemplate txTemplate;

    @BeforeEach
    void createJdbcHarness() {
        this.jdbc = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }

    @Test
    void freshMigrationCreatesExactCustomerColumnsAndDefaults() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.flyway_schema_history "
                        + "WHERE version = '5' AND success = true",
                Integer.class)).isEqualTo(1);

        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, character_maximum_length, is_nullable, "
                        + "column_default, is_identity, identity_generation "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = 'banking' AND table_name = 'customer' "
                        + "ORDER BY ordinal_position");

        assertThat(columns).extracting(row -> row.get("column_name")).containsExactly(
                "customer_id", "user_id", "full_name_enc", "phone_enc", "email_enc",
                "status", "created_at", "updated_at");
        assertThat(dataType(columns, "customer_id")).isEqualTo("bigint");
        assertThat(dataType(columns, "user_id")).isEqualTo("bigint");
        assertThat(dataType(columns, "full_name_enc")).isEqualTo("bytea");
        assertThat(dataType(columns, "phone_enc")).isEqualTo("bytea");
        assertThat(dataType(columns, "email_enc")).isEqualTo("bytea");
        assertThat(dataType(columns, "status")).isEqualTo("character varying");
        assertThat(dataType(columns, "created_at")).isEqualTo("timestamp with time zone");
        assertThat(dataType(columns, "updated_at")).isEqualTo("timestamp with time zone");

        Map<String, Object> customerId = column(columns, "customer_id");
        assertThat(customerId.get("is_identity")).isEqualTo("YES");
        assertThat(customerId.get("identity_generation")).isEqualTo("ALWAYS");
        assertThat(column(columns, "status").get("character_maximum_length")).isEqualTo(16);
        assertThat(column(columns, "phone_enc").get("is_nullable")).isEqualTo("YES");
        assertThat(column(columns, "email_enc").get("is_nullable")).isEqualTo("YES");
        assertThat(columns.stream()
                .filter(row -> !List.of("phone_enc", "email_enc").contains(row.get("column_name")))
                .map(row -> row.get("is_nullable")))
                .containsOnly("NO");
        assertThat(String.valueOf(column(columns, "status").get("column_default")))
                .contains("ACTIVE");
        assertThat(String.valueOf(column(columns, "created_at").get("column_default")))
                .isEqualTo("now()");
        assertThat(String.valueOf(column(columns, "updated_at").get("column_default")))
                .isEqualTo("now()");
    }

    @Test
    void primaryForeignUniqueCheckConstraintsAndIndexesMatchApprovedDesign() {
        assertThat(jdbc.queryForList(
                "SELECT a.attname FROM pg_constraint c "
                        + "JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON true "
                        + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum "
                        + "WHERE c.conrelid = 'banking.customer'::regclass AND c.contype = 'p' "
                        + "ORDER BY k.ord",
                String.class)).containsExactly("customer_id");

        Map<String, Object> foreignKey = jdbc.queryForMap(
                "SELECT c.conname, ns.nspname AS referenced_schema, r.relname AS referenced_table, "
                        + "a.attname AS local_column, ra.attname AS referenced_column, "
                        + "c.confdeltype::text AS delete_action, c.confupdtype::text AS update_action "
                        + "FROM pg_constraint c "
                        + "JOIN pg_class r ON r.oid = c.confrelid "
                        + "JOIN pg_namespace ns ON ns.oid = r.relnamespace "
                        + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1] "
                        + "JOIN pg_attribute ra ON ra.attrelid = c.confrelid AND ra.attnum = c.confkey[1] "
                        + "WHERE c.conrelid = 'banking.customer'::regclass AND c.contype = 'f'");
        assertThat(foreignKey).containsEntry("conname", "fk_customer_user")
                .containsEntry("referenced_schema", "banking")
                .containsEntry("referenced_table", "app_user")
                .containsEntry("local_column", "user_id")
                .containsEntry("referenced_column", "user_id")
                .containsEntry("delete_action", "r")
                .containsEntry("update_action", "r");

        assertThat(jdbc.queryForList(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE schemaname = 'banking' AND tablename = 'customer'",
                String.class)).containsExactlyInAnyOrder(
                        "customer_pkey", "uq_customer_user_id", "ix_customer_status");
        assertThat(jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'banking' "
                        + "AND tablename = 'customer' AND indexname = 'uq_customer_user_id'",
                String.class)).contains("UNIQUE").contains("(user_id)");
        assertThat(jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'banking' "
                        + "AND tablename = 'customer' AND indexname = 'ix_customer_status'",
                String.class)).doesNotContain("UNIQUE").contains("(status)");

        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid = 'banking.customer'::regclass "
                        + "AND conname = 'ck_customer_status'",
                String.class)).contains("ACTIVE").contains("INACTIVE");
    }

    @Test
    void validCustomerWithNullableContactsUsesApprovedDefaultsAndIdentityRelationship() {
        long userId = insertUser();
        byte[] encryptedName = new byte[] {1, 2, 3, 4};

        long customerId = insertCustomer(userId, encryptedName);

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT c.user_id, c.full_name_enc, c.phone_enc, c.email_enc, c.status, "
                        + "c.created_at, c.updated_at, u.user_type "
                        + "FROM banking.customer c JOIN banking.app_user u ON u.user_id = c.user_id "
                        + "WHERE c.customer_id = ?",
                customerId);
        assertThat(stored).containsEntry("user_id", userId)
                .containsEntry("status", "ACTIVE")
                .containsEntry("user_type", "CUSTOMER");
        assertThat((byte[]) stored.get("full_name_enc")).containsExactly(encryptedName);
        assertThat(stored.get("phone_enc")).isNull();
        assertThat(stored.get("email_enc")).isNull();
        assertThat(stored.get("created_at")).isNotNull();
        assertThat(stored.get("updated_at")).isNotNull();
    }

    @Test
    void duplicatePrimaryKeyIsRejected() {
        long firstUserId = insertUser();
        long secondUserId = insertUser();
        long customerId = insertCustomer(firstUserId, new byte[] {10});

        assertThatThrownBy(() -> tx(() -> jdbc.update(
                "INSERT INTO banking.customer (customer_id, user_id, full_name_enc) "
                        + "OVERRIDING SYSTEM VALUE VALUES (?, ?, ?)",
                customerId, secondUserId, new byte[] {11})))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void duplicateUserIdIsRejected() {
        long userId = insertUser();
        insertCustomer(userId, new byte[] {20});

        assertThatThrownBy(() -> insertCustomer(userId, new byte[] {21}))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void invalidIdentityReferenceIsRejected() {
        assertThatThrownBy(() -> insertCustomer(Long.MAX_VALUE, new byte[] {30}))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void missingRequiredFullNameIsRejected() {
        long userId = insertUser();

        assertThatThrownBy(() -> tx(() -> jdbc.update(
                "INSERT INTO banking.customer (user_id) VALUES (?)", userId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidCustomerStatusIsRejected() {
        long userId = insertUser();

        assertThatThrownBy(() -> tx(() -> jdbc.update(
                "INSERT INTO banking.customer (user_id, full_name_enc, status) VALUES (?, ?, ?)",
                userId, new byte[] {40}, "LOCKED")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void customerForeignKeyRestrictsIdentityDeleteAndKeyUpdate() {
        long userId = insertUser();
        insertCustomer(userId, new byte[] {50});

        assertThatThrownBy(() -> tx(() -> jdbc.update(
                "DELETE FROM banking.app_user WHERE user_id = ?", userId)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx(() -> jdbc.update(
                "UPDATE banking.app_user SET user_id = DEFAULT WHERE user_id = ?", userId)))
                .isInstanceOf(DataIntegrityViolationException.class);
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

    private long insertUser() {
        String username = "cus_v5_" + UUID.randomUUID();
        return txReturning(() -> {
            jdbc.update(
                    "INSERT INTO banking.app_user (username, password_hash, user_type) "
                            + "VALUES (?, ?, 'CUSTOMER')",
                    username, "f".repeat(60));
            return jdbc.queryForObject(
                    "SELECT user_id FROM banking.app_user WHERE username = ?",
                    Long.class, username);
        });
    }

    private long insertCustomer(long userId, byte[] fullNameEnc) {
        return txReturning(() -> jdbc.queryForObject(
                "INSERT INTO banking.customer (user_id, full_name_enc) VALUES (?, ?) "
                        + "RETURNING customer_id",
                Long.class, userId, fullNameEnc));
    }

    private void tx(Runnable action) {
        txTemplate.executeWithoutResult(status -> action.run());
    }

    private <T> T txReturning(java.util.function.Supplier<T> action) {
        return txTemplate.execute(status -> action.get());
    }
}
