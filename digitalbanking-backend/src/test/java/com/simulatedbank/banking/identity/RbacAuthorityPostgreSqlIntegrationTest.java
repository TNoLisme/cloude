package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.identity.application.AuthorityReader;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real Flyway schema, JPA projections and transactions; HTTP RBAC remains covered by IdentitySecurityIntegrationTest. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Transactional
class RbacAuthorityPostgreSqlIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("rbac_authority").withUsername("postgres").withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthorityReader authorities;
    @Autowired private SessionAuthenticator authenticator;

    @Test
    void disabledRoleHasNeitherRoleNorPermissionGrants() {
        long user = user("STAFF", "ADMIN");
        jdbc.update("UPDATE banking.role SET status = 'DISABLED' WHERE role_code = 'ADMIN'");
        assertThat(authorities.loadAuthorities(user)).isEmpty();
    }

    @Test
    void disabledPermissionIsExcludedWhileOtherActiveGrantsRemain() {
        long user = user("STAFF", "ADMIN");
        jdbc.update("UPDATE banking.permission SET status = 'DISABLED' WHERE permission_code = 'ROLE_MANAGE'");
        assertThat(authorities.loadAuthorities(user)).contains("ROLE_ADMIN", "AUDIT_READ")
                .doesNotContain("ROLE_MANAGE");
    }

    @Test
    void revokedCheckerPrivilegeIsExcludedAndSnapshotRefreshes() {
        long user = user("STAFF", "OPERATIONS");
        checker(user);
        var before = authorities.loadAuthorities(user);
        assertThat(before).contains("CHECKER", "ROLE_OPERATIONS");
        jdbc.update("UPDATE banking.user_privilege SET status = 'REVOKED' WHERE user_id = ?", user);
        assertThat(authorities.loadAuthorities(user)).contains("ROLE_OPERATIONS").doesNotContain("CHECKER");
        assertThat(before).contains("CHECKER");
    }

    @Test
    void missingUserAndUserWithoutRolesDefaultDeny() {
        assertThat(authorities.loadAuthorities(Long.MAX_VALUE)).isEmpty();
        assertThat(authorities.loadAuthorities(user("STAFF"))).isEmpty();
        assertThat(authorities.loadAuthorities(user("CUSTOMER"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOCKED", "DISABLED"})
    void inactiveUserHasNoGrantsIncludingChecker(String status) {
        long user = user("STAFF", "ADMIN");
        checker(user);
        jdbc.update("UPDATE banking.app_user SET status = ? WHERE user_id = ?", status, user);
        assertThat(authorities.loadAuthorities(user)).isEmpty();
    }

    @Test
    void customerCannotAcquireStaffRolesPermissionsOrChecker() {
        long user = user("CUSTOMER", "CUSTOMER", "ADMIN", "OPERATIONS", "AUDITOR");
        checker(user);
        assertThat(authorities.loadAuthorities(user)).containsExactlyInAnyOrder(
                "ROLE_CUSTOMER", "ACCOUNT_READ_SELF", "TRANSACTION_READ_SELF", "TRANSFER_CREATE", "LIMIT_READ_SELF");
        long mismatched = user("CUSTOMER", "ADMIN");
        checker(mismatched);
        assertThat(authorities.loadAuthorities(mismatched)).isEmpty();
    }

    @Test
    void staffCannotAcquireCustomerRoleOrSelfServicePermissions() {
        long user = user("STAFF", "CUSTOMER", "AUDITOR");
        assertThat(authorities.loadAuthorities(user)).containsExactlyInAnyOrder(
                "ROLE_AUDITOR", "AUDIT_READ", "AUDIT_EXPORT", "TRANSACTION_READ", "ACCOUNT_READ");
        assertThat(authorities.loadAuthorities(user("STAFF", "CUSTOMER"))).isEmpty();
    }

    @Test
    void crossTypePermissionMappingsDoNotBypassSegregation() {
        jdbc.update("INSERT INTO banking.role_permission (role_id, permission_id) "
                + "SELECT r.role_id, p.permission_id FROM banking.role r CROSS JOIN banking.permission p "
                + "WHERE r.role_code = 'CUSTOMER' AND p.permission_code = 'ROLE_MANAGE'");
        jdbc.update("INSERT INTO banking.role_permission (role_id, permission_id) "
                + "SELECT r.role_id, p.permission_id FROM banking.role r CROSS JOIN banking.permission p "
                + "WHERE r.role_code = 'ADMIN' AND p.permission_code = 'TRANSFER_CREATE'");
        assertThat(authorities.loadAuthorities(user("CUSTOMER", "CUSTOMER"))).doesNotContain("ROLE_MANAGE");
        assertThat(authorities.loadAuthorities(user("STAFF", "ADMIN"))).doesNotContain("TRANSFER_CREATE");
    }

    @Test
    void authenticatorUsesFreshDatabaseGrantsNotForgedSecurityContextClaims() {
        long user = user("STAFF", "AUDITOR");
        String token = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, "
                        + "idle_expires_at, absolute_expires_at) VALUES (?, ?, ?, now() + interval '20 minutes', "
                        + "now() + interval '1 hour')",
                HmacService.sha256Hex(token), "a".repeat(64), user);
        var context = SecurityContextHolder.getContext();
        var previous = context.getAuthentication();
        try {
            context.setAuthentication(new UsernamePasswordAuthenticationToken("forged-admin", null,
                    AuthorityUtils.createAuthorityList("ROLE_ADMIN", "ROLE_MANAGE", "CHECKER")));
            assertThat(authenticator.authenticate(token).orElseThrow().authorities())
                    .contains("ROLE_AUDITOR", "AUDIT_READ").doesNotContain("ROLE_ADMIN", "ROLE_MANAGE", "CHECKER");
            jdbc.update("UPDATE banking.role SET status = 'DISABLED' WHERE role_code = 'AUDITOR'");
            assertThat(authenticator.authenticate(token).orElseThrow().authorities()).isEmpty();
        } finally {
            context.setAuthentication(previous);
        }
    }

    private long user(String type, String... roles) {
        Long id = jdbc.queryForObject("INSERT INTO banking.app_user (username, password_hash, user_type) "
                + "VALUES (?, 'unused-test-hash', ?) RETURNING user_id", Long.class, "rbac-" + UUID.randomUUID(), type);
        for (String role : roles) {
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = ?", id, role);
        }
        return id;
    }

    private void checker(long user) {
        jdbc.update("INSERT INTO banking.user_privilege (user_id, privilege_code) VALUES (?, 'CHECKER')", user);
    }
}
