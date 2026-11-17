package com.simulatedbank.banking.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FlywayMigrationTest {

    @Test
    void foundationMigrationDeclaresBankingSchemaWithoutProbeTable() throws IOException {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V1__init_schema.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("CREATE SCHEMA IF NOT EXISTS banking;");
            assertThat(sql).doesNotContain("foundation_probe");
        }
    }

    @Test
    void identityMigrationDeclaresAllIdentityAndRbacTables() throws IOException {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V2__create_identity_and_rbac_tables.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            for (String table : new String[] {
                    "app_user", "device", "session", "auth_challenge", "auth_attempt",
                    "password_history", "role", "permission", "user_role", "role_permission",
                    "user_privilege"}) {
                assertThat(sql).contains("CREATE TABLE banking." + table + " (");
            }
            // Bảng customer thuộc module customer (V3), không nằm trong V2.
            assertThat(sql).doesNotContain("CREATE TABLE banking.customer");
            // Partial unique: một challenge step-up active cho mỗi (idempotency_key, request_hash).
            assertThat(sql).contains("uq_auth_challenge_active_stepup");
            assertThat(sql).contains("WHERE purpose = 'TRANSFER_STEP_UP'");
        }
    }

    @Test
    void adminIdempotencyMigrationDeclaresScopedUniqueRecord() throws IOException {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V7__create_admin_idempotency_record_table.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("CREATE TABLE banking.admin_idempotency_record (");
            assertThat(sql).contains("uq_admin_idempotency_scope");
            assertThat(sql).contains("(actor_id, operation, scope_key, idempotency_key)");
            assertThat(sql).contains("ck_admin_idempotency_terminal_fields");
            assertThat(sql).contains("ck_admin_idempotency_received_fields");
            assertThat(sql).doesNotContain("tenant_id");
        }
    }
}
