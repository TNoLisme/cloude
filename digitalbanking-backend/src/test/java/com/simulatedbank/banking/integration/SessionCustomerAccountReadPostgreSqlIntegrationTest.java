package com.simulatedbank.banking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.account.application.AccountAccessService;
import com.simulatedbank.banking.account.application.AccountNumberProtection;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.application.CustomerPiiService;
import com.simulatedbank.banking.customer.application.StaffCustomerDetailService;
import com.simulatedbank.banking.customer.domain.CustomerStatus;
import com.simulatedbank.banking.identity.application.PasswordHasher;
import com.simulatedbank.banking.notification.api.OtpGateway;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(SessionCustomerAccountReadPostgreSqlIntegrationTest.GatewayConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SessionCustomerAccountReadPostgreSqlIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String PASSWORD = "Synthetic-INT03-Password";
    private static final String OWNER_NAME = "Lan Example";
    private static final String OWNER_PHONE = "+84900000123";
    private static final String OWNER_EMAIL = "lan.owner@example.com";
    private static final String OTHER_NAME = "Binh Foreign";
    private static final String OTHER_NUMBER = "0099004567";
    private static final long MISSING_ID = Long.MAX_VALUE;

    @Container
    static final PostgreSQLContainer<?> postgres = PostgreSqlIntegrationTestSupport.newContainer("be_int_03");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        registry.add("app.security.rate-limit.login-per-ip-per-minute", () -> "100");
    }

    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired DataSource dataSource;
    private JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired PasswordHasher hasher;
    @Autowired TestOtpGateway otp;
    @Autowired CustomerPiiService customerPii;
    @Autowired AccountNumberProtection accountNumbers;
    @Autowired AccountAccessService accountReads;
    @Autowired StaffCustomerDetailService customerReads;
    @Autowired MeterRegistry meters;

    @BeforeEach
    void configureFixtureConnection() {
        // External fixture/observability statements commit independently of the application pool.
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @Test
    void s01AndC01MfaBindsCustomerAndAllSelfReadsUseThatOwner() throws Exception {
        PostgreSqlIntegrationTestSupport.assertContainerDataSource(dataSource, postgres);
        flyway.validate();
        assertThat(Arrays.stream(flyway.info().applied()).filter(migration -> migration.getVersion() != null))
                .extracting(migration -> migration.getVersion().getVersion())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 22)
                        .mapToObj(Integer::toString).toList());
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("22");
        assertThat(context.getBeansOfType(SessionAuthenticator.class)).containsOnlyKeys("identitySessionAuthenticator");

        user("unmapped-fixture", "CUSTOMER", false, "CUSTOMER");
        Customer owner = customer("mfa-owner", CustomerStatus.ACTIVE, true);
        Customer other = customer("mfa-other", CustomerStatus.ACTIVE, false);
        long ownAccount = account(owner, "0011001234", "LOCKED");
        account(other, "0066005678", "ACTIVE");
        Map<String, Object> before = businessSnapshot();
        long denies = denyCount();

        Reply verified = verify(beginLogin("mfa-owner"), "int03-s01-mfa");
        JsonNode login = verified.ok().path("user");
        String token = verified.cookieToken();
        assertThat(login.path("userId").asText()).isEqualTo(Long.toString(owner.userId()));
        assertThat(login.path("customerId").asText()).isEqualTo(Long.toString(owner.customerId()));
        assertThat(owner.customerId()).isNotEqualTo(owner.userId());

        Reply refresh = send("GET", "/auth/session", "int03-s01-refresh", null,
                Map.of("Cookie", "DB_SESSION=" + token));
        assertThat(refresh.ok().path("user").path("customerId")).isEqualTo(login.path("customerId"));

        JsonNode profile = get("/customers/me", token, "int03-c01-profile").ok();
        assertThat(profile.path("customerId")).isEqualTo(login.path("customerId"));
        assertThat(profile.path("fullName").asText()).isEqualTo(OWNER_NAME);
        assertThat(profile.path("phoneMasked").asText()).isEqualTo("********123");
        assertThat(profile.path("emailMasked").asText()).isEqualTo("l***@example.com");
        assertThat(fields(profile)).containsExactlyInAnyOrder("customerId", "userId", "fullName", "phoneMasked",
                "emailMasked", "status", "createdAt", "updatedAt");
        assertThat(profile.toString()).doesNotContain(OWNER_PHONE, OWNER_EMAIL);

        JsonNode list = get("/accounts?status=LOCKED", token, "int03-c01-list").ok();
        assertThat(list.path("items")).hasSize(1);
        assertMaskedAccount(list.path("items").get(0), ownAccount, "1234", "LOCKED");
        assertThat(list.path("asOf")).isEqualTo(list.path("items").get(0).path("asOf"));

        JsonNode detail = get("/accounts/" + ownAccount, token, "int03-c01-detail").ok();
        assertMaskedAccount(detail, ownAccount, "1234", "LOCKED");
        assertThat(fields(detail.path("owner"))).containsExactlyInAnyOrder("customerId", "status");
        assertThat(detail.path("owner").path("status").asText()).isEqualTo("ACTIVE");
        assertThat(detail.toString()).doesNotContain(OWNER_NAME, "0011001234", "0066005678");
        assertThat(businessSnapshot()).isEqualTo(before);
        assertThat(denyCount()).isEqualTo(denies);
    }

    @Test
    void s02InactiveAndMissingMappingsAndStaffHaveDifferentSessionSemantics() throws Exception {
        Customer inactive = customer("inactive-user", CustomerStatus.INACTIVE, true);
        long account = account(inactive, "0044001234", "ACTIVE");
        Reply inactiveLogin = verify(beginLogin("inactive-user"), "int03-s02-inactive-login");
        assertThat(inactiveLogin.ok().path("user").path("customerId").asText())
                .isEqualTo(Long.toString(inactive.customerId()));
        String inactiveToken = inactiveLogin.cookieToken();
        assertThat(get("/auth/session", inactiveToken, "int03-s02-inactive-session").ok().path("user")
                .path("customerId").asText()).isEqualTo(Long.toString(inactive.customerId()));
        assertThat(get("/customers/me", inactiveToken, "int03-s02-inactive-self").ok().path("status").asText())
                .isEqualTo("INACTIVE");
        JsonNode detail = get("/accounts/" + account, inactiveToken, "int03-s02-inactive-account").ok();
        assertThat(detail.path("owner").path("status").asText()).isEqualTo("INACTIVE");

        user("missing-mapping", "CUSTOMER", true, "CUSTOMER");
        Reply missingLogin = verify(beginLogin("missing-mapping"), "int03-s02-missing-login");
        assertThat(missingLogin.ok().path("user").path("customerId").isNull()).isTrue();
        String missingToken = missingLogin.cookieToken();
        assertThat(get("/auth/session", missingToken, "int03-s02-missing-session").ok().path("user")
                .path("customerId").isNull()).isTrue();
        get("/customers/me", missingToken, "int03-s02-missing-self").problem(404, "CUSTOMER_NOT_FOUND");
        get("/accounts", missingToken, "int03-s02-missing-list").problem(404, "CUSTOMER_NOT_FOUND");
        get("/accounts/" + account, missingToken, "int03-s02-missing-detail").problem(404, "CUSTOMER_NOT_FOUND");

        long operator = user("staff-binding", "STAFF", true, "OPERATIONS");
        // Even an inconsistent seeded mapping cannot give a STAFF user a customer snapshot.
        customerPii.create(operator, "Staff Mapping", null, null, CustomerStatus.ACTIVE);
        Reply staffLogin = verify(beginLogin("staff-binding"), "int03-s02-staff-login");
        assertThat(staffLogin.ok().path("user").path("customerId").isNull()).isTrue();
        assertThat(get("/auth/session", staffLogin.cookieToken(), "int03-s02-staff-session").ok().path("user")
                .path("customerId").isNull()).isTrue();
    }

    @Test
    void s02AndT03CustomerDatabaseFailureFailsClosedAndMfaCanRetry() throws Exception {
        Customer customer = customer("failure-user", CustomerStatus.ACTIVE, true);
        String existing = session(customer.userId());
        String staff = session(user("failure-staff", "STAFF", false, "OPERATIONS"));
        String challenge = beginLogin("failure-user");
        String code = otp.take(challenge);
        long sessionsBefore = sessionCount(customer.userId());

        // On the disposable PostgreSQL container, a missing column makes the real provider fail.
        jdbc.execute("ALTER TABLE banking.customer RENAME COLUMN status TO int03_unavailable_status");
        try {
            get("/auth/session", existing, "int03-s02-unavailable-session").problem(503, "SERVICE_UNAVAILABLE");
            get("/customers/me", existing, "int03-s02-unavailable-self").problem(503, "SERVICE_UNAVAILABLE");
            Reply rejected = verify(challenge, code, "int03-s02-unavailable-mfa");
            rejected.problem(503, "SERVICE_UNAVAILABLE");
            assertThat(rejected.response().headers().allValues("Set-Cookie")).isEmpty();
            assertThat(sessionCount(customer.userId())).isEqualTo(sessionsBefore);
            assertThat(jdbc.queryForObject("SELECT status FROM banking.auth_challenge WHERE challenge_ref = ?",
                    String.class, UUID.fromString(challenge))).isEqualTo("SENT");
            get("/admin/customers/" + customer.customerId() + "/accounts", staff, "int03-t03-unavailable-staff")
                    .problem(500, "INTERNAL_ERROR");
        } finally {
            jdbc.execute("ALTER TABLE banking.customer RENAME COLUMN int03_unavailable_status TO status");
        }

        assertThat(verify(challenge, code, "int03-s02-retry-mfa").ok().path("user").path("customerId").asText())
                .isEqualTo(Long.toString(customer.customerId()));
        assertThat(sessionCount(customer.userId())).isEqualTo(sessionsBefore + 1);
    }

    @Test
    void s03InvalidSessionRoleRevocationAndCsrfRotationTakeEffectOnNextRequest() throws Exception {
        long denies = denyCount();
        get("/accounts", null, "int03-s03-anonymous").problem(401, "AUTH_SESSION_EXPIRED");
        get("/accounts", "invalid-" + UUID.randomUUID(), "int03-s03-invalid").problem(401, "AUTH_SESSION_EXPIRED");

        Customer owner = customer("session-lifecycle", CustomerStatus.ACTIVE, true);
        String expired = session(owner.userId());
        jdbc.update("UPDATE banking.session SET idle_expires_at = now() - interval '1 second', "
                + "last_activity_at = now() - interval '2 seconds' WHERE session_token_hash = ?", HmacService.sha256Hex(expired));
        get("/accounts", expired, "int03-s03-expired").problem(401, "AUTH_SESSION_EXPIRED");
        String revoked = session(owner.userId());
        jdbc.update("UPDATE banking.session SET status = 'LOGGED_OUT', logged_out_at = now() "
                + "WHERE session_token_hash = ?", HmacService.sha256Hex(revoked));
        get("/accounts", revoked, "int03-s03-revoked").problem(401, "AUTH_SESSION_EXPIRED");

        String disabled = session(user("disabled-user", "CUSTOMER", false, "CUSTOMER"));
        jdbc.update("UPDATE banking.app_user SET status = 'DISABLED' WHERE user_id = "
                + "(SELECT user_id FROM banking.session WHERE session_token_hash = ?)",
                HmacService.sha256Hex(disabled));
        get("/accounts", disabled, "int03-s03-disabled").problem(401, "AUTH_SESSION_EXPIRED");

        Reply login = verify(beginLogin("session-lifecycle"), "int03-s03-login");
        String token = login.cookieToken();
        String initialCsrf = login.ok().path("session").path("csrfToken").asText();
        String rotatedCsrf = get("/auth/session", token, "int03-s03-refresh").ok().path("session")
                .path("csrfToken").asText();
        assertThat(rotatedCsrf).isNotBlank().isNotEqualTo(initialCsrf);
        assertThat(send("POST", "/auth/logout", "int03-s03-old-csrf", "",
                Map.of("Cookie", "DB_SESSION=" + token, "X-CSRF-Token", initialCsrf)).response().statusCode())
                .isEqualTo(403);
        assertThat(send("POST", "/auth/logout", "int03-s03-logout", "",
                Map.of("Cookie", "DB_SESSION=" + token, "X-CSRF-Token", rotatedCsrf)).response().statusCode())
                .isEqualTo(204);
        get("/accounts", token, "int03-s03-after-logout").problem(401, "AUTH_SESSION_EXPIRED");
        assertThat(denyCount()).isEqualTo(denies);

        long staff = user("revoked-role", "STAFF", false, "OPERATIONS");
        String staffToken = session(staff);
        String path = "/admin/customers/" + owner.customerId();
        get(path, staffToken, "int03-s03-with-role").ok();
        jdbc.update("DELETE FROM banking.user_role WHERE user_id = ?", staff);
        Reply denied = get(path, staffToken, "int03-s03-without-role");
        denied.problem(403, "AUTH_FORBIDDEN");
        assertDeny(denied, staff, "UNKNOWN", "CUSTOMER", owner.customerId());
    }

    @Test
    void c02AndC03OwnershipAndTrustedPrincipalCannotBeChangedByRequest(CapturedOutput output) throws Exception {
        Customer owner = customer("ownership-user", CustomerStatus.ACTIVE, false);
        Customer other = customer("ownership-other", CustomerStatus.ACTIVE, false);
        long ownAccount = account(owner, "0011003456", "ACTIVE");
        long foreign = account(other, OTHER_NUMBER, "ACTIVE");
        String token = session(owner.userId());
        long denies = denyCount();

        Map<String, String> spoofed = Map.of("Authorization", "Bearer " + token,
                "X-User-Id", Long.toString(other.userId()), "X-Customer-Id", Long.toString(other.customerId()),
                "X-Roles", "ADMIN", "X-Permissions", "CUSTOMER_READ,ACCOUNT_READ");
        String query = "?customerId=" + other.customerId() + "&userId=" + other.userId() + "&role=ADMIN";
        JsonNode profile = send("GET", "/customers/me" + query, "int03-c03-owner", null, spoofed).ok();
        assertThat(profile.path("customerId").asText()).isEqualTo(Long.toString(owner.customerId()));
        JsonNode list = send("GET", "/accounts" + query, "int03-c03-list", null, spoofed).ok();
        assertThat(list.path("items")).hasSize(1);
        assertThat(list.path("items").get(0).path("accountId").asText()).isEqualTo(Long.toString(ownAccount));

        Reply denied = send("GET", "/accounts/" + foreign + query, "int03-c02-foreign", null, spoofed);
        denied.problem(403, "AUTH_FORBIDDEN");
        assertThat(denied.response().body()).doesNotContain(OTHER_NUMBER, OTHER_NAME, token, "4567");
        assertDeny(denied, owner.userId(), "CUSTOMER", "ACCOUNT", foreign);
        get("/accounts/" + MISSING_ID, token, "int03-c02-unknown").problem(404, "ACCOUNT_NOT_FOUND");
        get("/accounts/9999999999999999999", token, "int03-c02-overflow").problem(400, "VALIDATION_ERROR");
        get("/accounts/01", token, "int03-c02-format").problem(400, "VALIDATION_ERROR");
        assertThat(denyCount()).isEqualTo(denies + 1);
        assertThat(output.getAll()).contains("int03-c02-foreign").doesNotContain(OTHER_NUMBER, OTHER_NAME, token);
    }

    @Test
    void t01ThroughT03StaffRoleMatrixAndValidationUseProviderState() throws Exception {
        Customer owner = customer("staff-review-target", CustomerStatus.ACTIVE, false);
        Customer empty = customer("staff-empty-target", CustomerStatus.INACTIVE, false);
        long locked = account(owner, "0066004321", "LOCKED");
        String target = "/admin/customers/" + owner.customerId();
        String detail = "/accounts/" + locked;
        long operator = user("read-operator", "STAFF", false, "OPERATIONS");
        String ops = session(operator);
        String admin = session(user("read-admin", "STAFF", false, "ADMIN"));
        long auditor = user("read-auditor", "STAFF", false, "AUDITOR");
        String audit = session(auditor);
        long staffWithoutRole = user("read-no-permission", "STAFF", false);
        String noRole = session(staffWithoutRole);
        String self = session(owner.userId());
        Map<String, Object> before = businessSnapshot();

        for (String roleToken : List.of(ops, admin)) {
            JsonNode staffProfile = get(target, roleToken, "int03-t01-staff").ok();
            assertThat(staffProfile.path("fullName").asText()).isEqualTo("L*** E***");
            assertThat(staffProfile.path("phoneMasked").asText()).isEqualTo("********123");
            assertThat(staffProfile.path("emailMasked").asText()).isEqualTo("l***@example.com");
            assertThat(staffProfile.toString()).doesNotContain(OWNER_NAME, OWNER_PHONE, OWNER_EMAIL);
        }
        for (String roleToken : List.of(ops, admin, audit)) {
            JsonNode staffAccount = get(detail, roleToken, "int03-t02-detail").ok();
            assertMaskedAccount(staffAccount, locked, "4321", "LOCKED");
            assertThat(staffAccount.path("owner").path("status").asText()).isEqualTo("ACTIVE");
            JsonNode result = get(target + "/accounts?status=LOCKED", roleToken, "int03-t02-list").ok();
            assertThat(result.path("items")).hasSize(1);
            assertMaskedAccount(result.path("items").get(0), locked, "4321", "LOCKED");
        }

        Reply deniedAudit = get(target, audit, "int03-t01-auditor");
        deniedAudit.problem(403, "AUTH_FORBIDDEN");
        assertDeny(deniedAudit, auditor, "AUDITOR", "CUSTOMER", owner.customerId());
        get(target, self, "int03-t01-self-to-staff").problem(403, "AUTH_FORBIDDEN");
        get(target, noRole, "int03-t01-no-permission").problem(403, "AUTH_FORBIDDEN");
        get("/admin/customers/" + MISSING_ID, noRole, "int03-t01-no-enumeration").problem(403, "AUTH_FORBIDDEN");
        get("/admin/customers/" + MISSING_ID, ops, "int03-t01-missing").problem(404, "CUSTOMER_NOT_FOUND");
        get("/admin/customers/9999999999999999999", ops, "int03-t01-overflow").problem(400, "VALIDATION_ERROR");

        Reply deniedList = get(target + "/accounts", self, "int03-t02-self-to-staff");
        deniedList.problem(403, "AUTH_FORBIDDEN");
        assertDeny(deniedList, owner.userId(), "CUSTOMER", "CUSTOMER", owner.customerId());
        get(detail, noRole, "int03-t02-no-permission").problem(403, "AUTH_FORBIDDEN");
        get("/admin/customers/" + MISSING_ID + "/accounts", audit, "int03-t03-missing-customer")
                .problem(404, "CUSTOMER_NOT_FOUND");
        assertThat(get("/admin/customers/" + empty.customerId() + "/accounts", ops, "int03-t03-empty")
                .ok().path("items")).isEmpty();
        get("/accounts/" + MISSING_ID, ops, "int03-t03-missing-account").problem(404, "ACCOUNT_NOT_FOUND");
        get(target + "/accounts?status=UNKNOWN", ops, "int03-t03-invalid-status").problem(400, "VALIDATION_ERROR");
        assertThat(businessSnapshot()).isEqualTo(before);
    }

    @Test
    void c03SyntheticMixedSnapshotIsDeniedByRealServicesBeforeMissingTargetLookup() {
        Customer owner = customer("mixed-role-user", CustomerStatus.ACTIVE, false);
        var mixed = new SessionAuthenticator.Session(Long.toString(owner.userId()), Long.toString(owner.customerId()),
                Set.of("ROLE_CUSTOMER", "ROLE_ADMIN", "CUSTOMER_READ", "ACCOUNT_READ", "ACCOUNT_READ_SELF"),
                "a".repeat(64));
        assertForbidden(() -> accountReads.getAccountForStaff(MISSING_ID, mixed, "int03-c03-mixed-detail"));
        assertForbidden(() -> accountReads.listCustomerAccounts(MISSING_ID, null, mixed, "int03-c03-mixed-list"));
        assertForbidden(() -> customerReads.getCustomerDetail(MISSING_ID, owner.userId(), mixed.authorities(),
                "ADMIN,CUSTOMER", "int03-c03-mixed-profile"));

        var permissionOnly = new SessionAuthenticator.Session(mixed.userId(), null, Set.of("ACCOUNT_READ"), "b".repeat(64));
        assertForbidden(() -> accountReads.getAccountForStaff(MISSING_ID, permissionOnly, "int03-c03-no-staff-role"));
        assertThat(jdbc.queryForList("SELECT entity_type FROM banking.audit_log WHERE actor_user_id = ? "
                + "AND action = 'SECURITY_ACCESS_DENIED' ORDER BY audit_id", String.class, owner.userId()))
                .containsExactly("ACCOUNT", "CUSTOMER", "CUSTOMER", "ACCOUNT");
    }

    @Test
    void a01PostgresAuditAppendFailureKeeps403AndEmitsSafeFallback(CapturedOutput output) throws Exception {
        Customer owner = customer("fallback-owner", CustomerStatus.ACTIVE, false);
        Customer other = customer("fallback-other", CustomerStatus.ACTIVE, false);
        long foreign = account(other, "0011006677", "ACTIVE");
        String token = session(owner.userId());
        double failures = auditFailures();
        long denies = denyCount();
        jdbc.execute("""
                ALTER TABLE banking.audit_log ADD CONSTRAINT int03_reject_audit
                CHECK (correlation_id NOT IN ('int03-a01-service-fail', 'int03-a01-chain-fail'))
                """);
        try {
            get("/accounts/" + foreign, token, "int03-a01-service-fail").problem(403, "AUTH_FORBIDDEN");
            get("/admin/customers/" + other.customerId(), token, "int03-a01-chain-fail")
                    .problem(403, "AUTH_FORBIDDEN");
        } finally {
            jdbc.execute("ALTER TABLE banking.audit_log DROP CONSTRAINT int03_reject_audit");
        }
        assertThat(denyCount()).isEqualTo(denies);
        assertThat(auditFailures()).isEqualTo(failures + 2);
        assertFallback(output, "int03-a01-service-fail", owner.userId());
        assertFallback(output, "int03-a01-chain-fail", owner.userId());
        assertThat(output.getAll()).doesNotContain(token, OWNER_PHONE, OWNER_EMAIL, "0011006677");

        Reply recovered = get("/accounts/" + foreign, token, "int03-a01-recovered");
        recovered.problem(403, "AUTH_FORBIDDEN");
        assertDeny(recovered, owner.userId(), "CUSTOMER", "ACCOUNT", foreign);
    }

    private Customer customer(String username, CustomerStatus status, boolean mfa) {
        long userId = user(username, "CUSTOMER", mfa, "CUSTOMER");
        long customerId = customerPii.create(userId, username.contains("other") ? OTHER_NAME : OWNER_NAME,
                OWNER_PHONE, OWNER_EMAIL, status).getCustomerId();
        return new Customer(userId, customerId);
    }

    private long user(String username, String type, boolean mfa, String... roles) {
        long userId = jdbc.queryForObject("""
                INSERT INTO banking.app_user (username, password_hash, user_type, status)
                VALUES (?, ?, ?, 'ACTIVE') RETURNING user_id
                """, Long.class, username, mfa ? hasher.hash(PASSWORD.toCharArray()) : "synthetic-fixture", type);
        for (String role : roles) {
            jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                    + "SELECT ?, role_id FROM banking.role WHERE role_code = ?", userId, role);
        }
        return userId;
    }

    private long account(Customer owner, String number, String status) {
        var protectedNumber = accountNumbers.protect(number);
        return jdbc.queryForObject("""
                INSERT INTO banking.account (customer_id, account_no_enc, account_no_hash, account_no_last4,
                                             status, available_balance, ledger_balance)
                VALUES (?, ?, ?, ?, ?, 125.00, 125.00) RETURNING account_id
                """, Long.class, owner.customerId(), protectedNumber.encrypted(), protectedNumber.blindIndex(),
                protectedNumber.accountNumberLast4(), status);
    }

    private String session(long userId) {
        String token = "int03-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id,
                                             idle_expires_at, absolute_expires_at)
                VALUES (?, ?, ?, now() + interval '20 minutes', now() + interval '1 hour')
                """, HmacService.sha256Hex(token), "a".repeat(64), userId);
        return token;
    }

    private long sessionCount(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM banking.session WHERE user_id = ?", Long.class, userId);
    }

    private String beginLogin(String username) throws Exception {
        Reply pending = send("POST", "/auth/login", "int03-primary-" + username,
                JSON.writeValueAsString(Map.of("username", username, "password", PASSWORD)), Map.of());
        assertThat(pending.response().statusCode()).isEqualTo(202);
        assertThat(pending.response().headers().allValues("Set-Cookie")).isEmpty();
        return pending.body().path("challenge").path("challengeId").asText();
    }

    private Reply verify(String challenge, String correlation) throws Exception {
        return verify(challenge, otp.take(challenge), correlation);
    }

    private Reply verify(String challenge, String code, String correlation) throws Exception {
        return send("POST", "/auth/otp/verify", correlation,
                JSON.writeValueAsString(Map.of("challengeId", challenge, "otp", code)), Map.of());
    }

    private Reply get(String path, String token, String correlation) throws Exception {
        return send("GET", path, correlation, null,
                token == null ? Map.of() : Map.of("Authorization", "Bearer " + token));
    }

    private Reply send(String method, String path, String correlation, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15)).header("X-Correlation-ID", correlation);
        headers.forEach(request::header);
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return new Reply(HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString()), correlation);
    }

    private long denyCount() {
        return jdbc.queryForObject("SELECT count(*) FROM banking.audit_log WHERE action = 'SECURITY_ACCESS_DENIED'",
                Long.class);
    }

    private double auditFailures() {
        return meters.counter("audit.write.failures", "class", "C3", "action", "SECURITY_ACCESS_DENIED").count();
    }

    private void assertDeny(Reply reply, long actorUserId, String role, String type, long target) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT actor_user_id, actor_role, entity_type, entity_id, outcome, request_id, after_state::text
                  FROM banking.audit_log
                 WHERE action = 'SECURITY_ACCESS_DENIED' AND correlation_id = ?
                """, reply.correlation());
        assertThat(row).containsEntry("actor_user_id", actorUserId).containsEntry("actor_role", role)
                .containsEntry("entity_type", type).containsEntry("entity_id", Long.toString(target))
                .containsEntry("outcome", "REJECTED").containsEntry("request_id",
                        reply.response().headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(row.get("after_state")).isEqualTo("{\"reasonCode\": \"AUTH_FORBIDDEN\"}");
    }

    private Map<String, Object> businessSnapshot() {
        return jdbc.queryForMap("""
                SELECT (SELECT md5(string_agg(to_jsonb(c)::text, ',' ORDER BY customer_id)) FROM banking.customer c)
                           AS customer_checksum,
                       (SELECT md5(string_agg(to_jsonb(a)::text, ',' ORDER BY account_id)) FROM banking.account a)
                           AS account_checksum,
                       (SELECT count(*) FROM banking.transfer) AS transfers,
                       (SELECT count(*) FROM banking.ledger_entry) AS ledger_entries,
                       (SELECT count(*) FROM banking.limit_config) AS limits,
                       (SELECT count(*) FROM banking.limit_usage) AS limit_usage,
                       (SELECT count(*) FROM banking.notification) AS notifications,
                       (SELECT count(*) FROM banking.approval_request) AS approvals
                """);
    }

    private static void assertMaskedAccount(JsonNode node, long accountId, String last4, String status) {
        assertThat(node.path("accountId").asText()).isEqualTo(Long.toString(accountId));
        assertThat(node.path("accountNumberMasked").asText()).isEqualTo("**** **** " + last4);
        assertThat(node.path("accountNumberLast4").asText()).isEqualTo(last4);
        assertThat(node.path("status").asText()).isEqualTo(status);
        assertThat(node.path("currency").asText()).isEqualTo("VND");
        assertThat(node.path("availableBalance")).isEqualTo(JSON.getNodeFactory().textNode("125.00"));
        assertThat(node.path("ledgerBalance")).isEqualTo(JSON.getNodeFactory().textNode("125.00"));
        assertThat(Instant.parse(node.path("asOf").asText())).isNotNull();
    }

    private static void assertFallback(CapturedOutput output, String correlationId, long actorUserId) {
        assertThat(output.getAll().lines().filter(line -> line.contains("\"event\":\"SECURITY_AUDIT_FALLBACK\"")
                && line.contains("\"correlation_id\":\"" + correlationId + "\""))).singleElement()
                .asString().contains("\"action\":\"SECURITY_ACCESS_DENIED\"", "\"outcome\":\"REJECTED\"",
                        HmacService.sha256Hex(Long.toString(actorUserId)));
    }

    private static List<String> fields(JsonNode node) {
        return java.util.stream.StreamSupport.stream(
                java.util.Spliterators.spliteratorUnknownSize(node.fieldNames(), 0), false).toList();
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable lookup) {
        assertThatThrownBy(lookup).isInstanceOfSatisfying(BankingBusinessException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    }

    private record Customer(long userId, long customerId) {}

    private record Reply(HttpResponse<String> response, String correlation) {
        JsonNode body() throws Exception {
            return JSON.readTree(response.body());
        }

        JsonNode ok() throws Exception {
            assertThat(response.statusCode()).as("HTTP %s", correlation).isEqualTo(200);
            assertHeaders();
            return body();
        }

        void problem(int status, String code) throws Exception {
            assertThat(response.statusCode()).as("HTTP %s", correlation).isEqualTo(status);
            assertHeaders();
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("application/problem+json");
            assertThat(body().path("code").asText()).isEqualTo(code);
            assertThat(body().path("correlationId").asText()).isEqualTo(correlation);
            assertThat(response.body()).doesNotContain("banking.", "org.postgresql", "SQLException", OWNER_PHONE, OWNER_EMAIL);
        }

        String cookieToken() {
            return response.headers().allValues("Set-Cookie").stream().filter(cookie -> cookie.startsWith("DB_SESSION="))
                    .map(cookie -> cookie.substring("DB_SESSION=".length(), cookie.indexOf(';'))).findFirst().orElseThrow();
        }

        private void assertHeaders() {
            assertThat(response.headers().firstValue("X-Correlation-ID")).contains(correlation);
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayConfiguration {
        @Bean
        TestOtpGateway int03OtpGateway() {
            return new TestOtpGateway();
        }
    }

    static final class TestOtpGateway implements OtpGateway {
        private final Map<String, char[]> codes = new ConcurrentHashMap<>();

        @Override
        public Result sendOtp(String ref, long userId, String channel, char[] otp, Instant expiresAt,
                String correlationId) {
            codes.put(ref, otp.clone());
            return new Result(Outcome.ACCEPTED, Failure.NONE);
        }

        String take(String ref) {
            char[] code = codes.remove(ref);
            assertThat(code).isNotNull();
            try {
                return new String(code);
            } finally {
                Arrays.fill(code, '\0');
            }
        }
    }
}
