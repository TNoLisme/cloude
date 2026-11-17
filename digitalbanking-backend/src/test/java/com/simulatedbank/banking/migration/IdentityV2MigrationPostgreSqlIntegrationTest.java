package com.simulatedbank.banking.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import javax.sql.DataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-ID-IMP-01: kiểm chứng migration V2 trên PostgreSQL thật (Testcontainers), theo quy ước
 * `docs-backend/00. architecture & conventions.md` §11 (không H2, không mock chứng minh invariant).
 * Nguồn kỳ vọng schema: `docs-common/04. Database design & analysis.md` §4.2 + Appendix A.1/A.2.
 *
 * <p>Repo đặt `spring.datasource.hikari.auto-commit: false` (transaction boundary thuộc tầng
 * `application`, BE-CON-16/27): JdbcTemplate đứng ngoài transaction sẽ bị rollback khi trả
 * connection về pool, nên mọi đơn vị mutation chạy qua {@link TransactionTemplate} (commit thật,
 * PostgreSQL thấy nhau giữa các bước). Các assert gây lỗi constraint chạy trong transaction
 * riêng — lỗi statement không abort các transaction khác (SQLSTATE 25P02 chỉ trong cùng block).</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class IdentityV2MigrationPostgreSqlIntegrationTest {

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

    private static final List<String> V2_TABLES = List.of(
            "app_user", "device", "session", "auth_challenge", "auth_attempt",
            "password_history", "role", "permission", "user_role", "role_permission",
            "user_privilege");

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private TransactionTemplate txTemplate;

    @BeforeEach
    void createJdbcHarness() {
        // Theo pattern `PostgreSqlErrorPipelineIntegrationTest.Probe`: TransactionTemplate gắn
        // trực tiếp DataSource (JdbcTransactionManager) — JpaTransactionManager không expose
        // connection JDBC cho JdbcTemplate trong setup hiện tại.
        this.jdbc = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }

    @Test
    void flywayAppliesV2AndAllIdentityRbacTablesExist() {
        Integer v2Applied = jdbc.queryForObject(
                "SELECT count(*) FROM banking.flyway_schema_history WHERE version = '2' AND success = true",
                Integer.class
        );
        assertThat(v2Applied).isEqualTo(1);

        Integer tableCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'banking' "
                        + "AND table_name = ANY (?)",
                Integer.class,
                (Object) V2_TABLES.toArray(new String[0])
        );
        assertThat(tableCount).isEqualTo(V2_TABLES.size());
    }

    @Test
    void rbacSeedMatchesAppendixA2() {
        Integer roleCount = jdbc.queryForObject(
                "SELECT count(*) FROM banking.role", Integer.class);
        assertThat(roleCount).isEqualTo(4);

        Integer permissionCount = jdbc.queryForObject(
                "SELECT count(*) FROM banking.permission", Integer.class);
        assertThat(permissionCount).isEqualTo(15);

        List<Map<String, Object>> mapping = jdbc.queryForList(
                "SELECT r.role_code, p.permission_code FROM banking.role_permission rp "
                        + "JOIN banking.role r ON r.role_id = rp.role_id "
                        + "JOIN banking.permission p ON p.permission_id = rp.permission_id "
                        + "ORDER BY r.role_code, p.permission_code");

        assertThat(mapping).hasSize(22);
        assertThat(permissionsOf(mapping, "CUSTOMER")).containsExactly(
                "ACCOUNT_READ_SELF", "LIMIT_READ_SELF", "TRANSACTION_READ_SELF", "TRANSFER_CREATE");
        assertThat(permissionsOf(mapping, "OPERATIONS")).containsExactly(
                "ACCOUNT_READ", "AUDIT_READ", "CUSTOMER_READ", "FRAUD_REVIEW",
                "LIMIT_MAKER", "TRANSACTION_READ");
        assertThat(permissionsOf(mapping, "ADMIN")).containsExactly(
                "ACCOUNT_READ", "AUDIT_READ", "CUSTOMER_READ", "FRAUD_READ", "LIMIT_MANAGE",
                "RETENTION_MANAGE", "ROLE_MANAGE", "TRANSACTION_READ");
        assertThat(permissionsOf(mapping, "AUDITOR")).containsExactly(
                "ACCOUNT_READ", "AUDIT_EXPORT", "AUDIT_READ", "TRANSACTION_READ");

        // CHECKER là privilege gắn user, không seed thành role/permission (04 §4.2.8).
        Integer checkerRows = jdbc.queryForObject(
                "SELECT count(*) FROM banking.permission WHERE permission_code = 'CHECKER'",
                Integer.class);
        assertThat(checkerRows).isZero();
    }

    private List<String> permissionsOf(List<Map<String, Object>> mapping, String roleCode) {
        return mapping.stream()
                .filter(row -> roleCode.equals(row.get("role_code")))
                .map(row -> String.valueOf(row.get("permission_code")))
                .toList();
    }

    @Test
    void sessionStatusEnumExcludesRevokedAndChallengeExcludesConsumed() {
        long userId = insertUser("v2_enum_session");

        // OD-ID-04: enum session.status theo 04 §4.2.3 — không có 'REVOKED'.
        assertThatThrownBy(() -> tx(() -> jdbc.update(
                "INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, status, "
                        + "idle_expires_at, absolute_expires_at) VALUES (?, ?, ?, 'REVOKED', "
                        + "now() + interval '15 minutes', now() + interval '8 hours')",
                "a".repeat(64), "b".repeat(64), userId)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // OD-ID-06: consume = consumed_at, enum không có 'CONSUMED'.
        assertThatThrownBy(() -> tx(() -> jdbc.update(
                "INSERT INTO banking.auth_challenge (challenge_ref, user_id, purpose, method, "
                        + "otp_hash, channel, status, expires_at, correlation_id) "
                        + "VALUES (?, ?, 'LOGIN_NEW_DEVICE', 'OTP', ?, 'SMS', 'CONSUMED', "
                        + "now() + interval '5 minutes', 'corr')",
                UUID.randomUUID(), userId, "b".repeat(64))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void activeStepUpChallengeIsUniquePerIdempotencyKeyAndRequestHash() {
        long userId = insertUser("v2_stepup_unique");
        String idempotencyKey = "key-" + UUID.randomUUID();
        String requestHash = "c".repeat(64);

        insertStepUpChallenge(userId, idempotencyKey, requestHash);

        assertThatThrownBy(() -> tx(
                () -> insertStepUpChallenge(userId, idempotencyKey, requestHash)))
                .isInstanceOf(DuplicateKeyException.class);

        // Consume (set consumed_at) giải phóng key — khớp OD-ID-06 / 04 §4.2.5 C-03.
        tx(() -> jdbc.update(
                "UPDATE banking.auth_challenge SET consumed_at = now() "
                        + "WHERE user_id = ? AND idempotency_key = ?",
                userId, idempotencyKey));

        insertStepUpChallenge(userId, idempotencyKey, requestHash);
    }

    @Test
    void otpChallengeRequiresHashAndChannel() {
        long userId = insertUser("v2_otp_required");

        assertThatThrownBy(() -> tx(() -> jdbc.update(
                "INSERT INTO banking.auth_challenge (challenge_ref, user_id, purpose, method, "
                        + "status, expires_at, correlation_id) "
                        + "VALUES (?, ?, 'LOGIN_NEW_DEVICE', 'OTP', 'CREATED', "
                        + "now() + interval '5 minutes', 'corr')",
                UUID.randomUUID(), userId)))
                .isInstanceOf(DataIntegrityViolationException.class);

        tx(() -> assertThat(jdbc.update(
                "INSERT INTO banking.auth_challenge (challenge_ref, user_id, purpose, method, "
                        + "otp_hash, channel, status, expires_at, correlation_id) "
                        + "VALUES (?, ?, 'LOGIN_NEW_DEVICE', 'OTP', ?, 'SMS', 'CREATED', "
                        + "now() + interval '5 minutes', 'corr')",
                UUID.randomUUID(), userId, "d".repeat(64))).isEqualTo(1));
    }

    @Test
    void appUserDeleteIsRestrictedWhenSessionExists() {
        String username = "v2_fk_restrict_" + UUID.randomUUID();

        assertThatThrownBy(() -> tx(() -> {
            long userId = insertUser(username);
            jdbc.update(
                    "INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, "
                            + "idle_expires_at, absolute_expires_at) VALUES (?, ?, ?, "
                            + "now() + interval '15 minutes', now() + interval '8 hours')",
                    "e".repeat(64), "f".repeat(64), userId);
            jdbc.update("DELETE FROM banking.app_user WHERE user_id = ?", userId);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void appUserUsernameIsUnique() {
        String username = "v2_dupe_" + UUID.randomUUID();
        insertUser(username);

        assertThatThrownBy(() -> tx(() -> insertUser(username)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private void tx(Runnable action) {
        txTemplate.executeWithoutResult(status -> action.run());
    }

    private <T> T txReturning(java.util.function.Supplier<T> action) {
        return txTemplate.execute(status -> action.get());
    }

    /** Insert + đọc user_id trong MỘT transaction commit — auto-commit=false của pool. */
    private long insertUser(String username) {
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

    private void insertStepUpChallenge(long userId, String idempotencyKey, String requestHash) {
        tx(() -> jdbc.update(
                "INSERT INTO banking.auth_challenge (challenge_ref, user_id, purpose, method, "
                        + "otp_hash, channel, request_hash, idempotency_key, status, expires_at, "
                        + "correlation_id) VALUES (?, ?, 'TRANSFER_STEP_UP', 'OTP', ?, 'SMS', ?, "
                        + "?, 'CREATED', now() + interval '5 minutes', 'corr')",
                UUID.randomUUID(), userId, "g".repeat(64), requestHash, idempotencyKey));
    }
}
