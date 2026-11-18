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
            // Bảng customer thuộc module customer (V5), không nằm trong V2.
            assertThat(sql).doesNotContain("CREATE TABLE banking.customer");
            // Partial unique: một challenge step-up active cho mỗi (idempotency_key, request_hash).
            assertThat(sql).contains("uq_auth_challenge_active_stepup");
            assertThat(sql).contains("WHERE purpose = 'TRANSFER_STEP_UP'");
        }
    }

    @Test
    void approvalMigrationDeclaresCanonicalAdminTable() throws IOException {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V6__create_approval_request_table.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("CREATE TABLE banking.approval_request (");
            assertThat(sql).contains("ck_approval_maker_checker");
            assertThat(sql).contains("ck_approval_decided_fields");
            assertThat(sql).contains("ck_approval_applied_at");
            assertThat(sql).contains("ix_approval_status_time");
            // Canonical 04 §4.5.3 baseline: no overview-only UUID/expiry/challenge columns.
            assertThat(sql).doesNotContain("EXPIRED", "expires_at", "step_up_challenge_id");
        }
    }

    @Test
    void accountMigrationDeclaresCanonicalAccountTable() throws IOException {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V8__create_account_table.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("CREATE TABLE banking.account (");
            assertThat(sql).contains("account_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY");
            assertThat(sql).contains("fk_account_customer");
            assertThat(sql).contains("REFERENCES banking.customer (customer_id) ON DELETE RESTRICT ON UPDATE RESTRICT");
            assertThat(sql).contains("uq_account_no_hash");
            assertThat(sql).contains("ck_account_status");
            assertThat(sql).contains("ck_account_currency");
            assertThat(sql).contains("ck_account_balance_within_ledger");
            assertThat(sql).contains("ck_account_closed_at_required");
            assertThat(sql).contains("ix_account_customer");
            assertThat(sql).contains("ix_account_status");
            // No cascading delete of financial records; ACC-DR-03/07 column omissions are asserted
            // against real PostgreSQL metadata in AccountV8MigrationPostgreSqlIntegrationTest.
            assertThat(sql).doesNotContain("CASCADE");
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
