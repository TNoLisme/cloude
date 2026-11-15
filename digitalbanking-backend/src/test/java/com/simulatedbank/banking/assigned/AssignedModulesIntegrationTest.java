package com.simulatedbank.banking.assigned;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simulatedbank.banking.account.application.AccountService;
import com.simulatedbank.banking.admin.application.ApprovalAuditSink;
import com.simulatedbank.banking.admin.application.ApprovalService;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.application.CustomerService;
import com.simulatedbank.banking.identity.api.OtpDeliveryPort;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.application.IdentitySecurityAuditSink;
import com.simulatedbank.banking.identity.application.IdentityService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@AutoConfigureMockMvc
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(AssignedModulesIntegrationTest.TestPorts.class)
class AssignedModulesIntegrationTest {
    private static final String COOKIE_NAME = "TEST_SESSION";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("digital_banking").withUsername("postgres").withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 12);
        registry.add("banking.modules.enabled", () -> true);
        registry.add("app.security.cookie.name", () -> COOKIE_NAME);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.schemas", () -> "banking");
        registry.add("spring.flyway.default-schema", () -> "banking");
    }

    @Autowired IdentityService identity;
    @Autowired CustomerService customers;
    @Autowired AccountService accounts;
    @Autowired ApprovalService approvals;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired CapturingOtp otp;
    @Autowired ThrowingIdentityAudit identityAudit;
    @Autowired CapturingApprovalAudit approvalAudit;

    @BeforeEach
    void clearBusinessData() {
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM banking.approval_request");
            jdbc.update("DELETE FROM banking.account");
            jdbc.update("DELETE FROM banking.auth_attempt");
            jdbc.update("DELETE FROM banking.auth_challenge");
            jdbc.update("DELETE FROM banking.session");
            jdbc.update("DELETE FROM banking.device");
            jdbc.update("DELETE FROM banking.customer");
            jdbc.update("DELETE FROM banking.password_history");
            jdbc.update("DELETE FROM banking.user_privilege");
            jdbc.update("DELETE FROM banking.user_role");
            jdbc.update("DELETE FROM banking.app_user");
        });
        otp.clear();
        identityAudit.reset();
        approvalAudit.reset();
    }

    @Test
    void identityUsesConfiguredCookieRejectsStaleOtpStateAndRevokesDeviceSessions() throws Exception {
        long userId = identity.createUser("customer01", "P@ssw0rd!", "CUSTOMER", "CUSTOMER");
        customers.create(new CustomerService.CreateCustomer(userId, "CIF00000001", "Nguyen Van A",
                "+84901234123", "a@example.com", "001234567890", "VERIFIED"));
        UUID deviceRef = UUID.fromString("11111111-1111-4111-8111-111111111111");

        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                .cookie(new Cookie(COOKIE_NAME, "attacker-fixed-token"))
                .contentType("application/json").content("""
                {"username":"CUSTOMER01","password":"P@ssw0rd!","deviceRef":"%s","deviceName":"Browser","deviceFingerprint":"fp-a"}
                """.formatted(deviceRef))).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("AUTH_CHALLENGE_REQUIRED")).andReturn();
        UUID challengeId = challengeId(login);
        String oneTimeCode = otp.code(challengeId);

        transactions.executeWithoutResult(status ->
                assertThat(jdbc.update("UPDATE banking.app_user SET status = 'DISABLED' WHERE user_id = ?", userId))
                        .isOne());
        assertThat(jdbc.queryForObject("SELECT status FROM banking.app_user WHERE user_id = ?", String.class, userId))
                .isEqualTo("DISABLED");
        mvc.perform(post("/api/v1/auth/otp/verify").contentType("application/json")
                .content("{\"challengeId\":\"" + challengeId + "\",\"otp\":\"" + oneTimeCode + "\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT status FROM banking.auth_challenge WHERE challenge_ref = ?",
                String.class, challengeId)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT trusted FROM banking.device WHERE device_ref = ?", Boolean.class,
                deviceRef)).isFalse();

        transactions.executeWithoutResult(status ->
                jdbc.update("UPDATE banking.app_user SET status = 'ACTIVE' WHERE user_id = ?", userId));
        MvcResult verified = mvc.perform(post("/api/v1/auth/otp/verify").contentType("application/json")
                .content("{\"challengeId\":\"" + challengeId + "\",\"otp\":\"" + oneTimeCode + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.displayName").value("Nguyen Van A"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.startsWith(COOKIE_NAME + "=")))
                .andReturn();
        Cookie cookie = sessionCookie(verified);
        assertThat(cookie.getValue()).isNotEqualTo("attacker-fixed-token");

        identity.revokeDevice(userId, deviceRef);
        assertThat(identity.authenticate(cookie.getValue())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM banking.session WHERE user_id = ? ORDER BY session_id DESC LIMIT 1",
                String.class, userId)).isEqualTo("REVOKED");
    }

    @Test
    void identityResetsExpiredLockAndC3AuditFailureCannotRollbackLockout() {
        long userId = identity.createUser("locked-user", "P@ssw0rd!", "STAFF", "OPERATIONS");
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE banking.app_user SET status = 'LOCKED', failed_attempt_count = 5, locked_until = ?
                WHERE user_id = ?
                """, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), userId));
        identityAudit.fail = true;

        assertInvalidCredentials(() -> identity.login(new IdentityService.LoginCommand(
                "locked-user", "wrong", null, null, null)));
        assertThat(jdbc.queryForObject("SELECT failed_attempt_count FROM banking.app_user WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM banking.app_user WHERE user_id = ?", String.class, userId))
                .isEqualTo("ACTIVE");

        for (int attempt = 2; attempt <= 4; attempt++) {
            assertInvalidCredentials(() -> identity.login(new IdentityService.LoginCommand(
                    "locked-user", "wrong", null, null, null)));
        }
        assertThatThrownBy(() -> identity.login(new IdentityService.LoginCommand(
                "locked-user", "wrong", null, null, null)))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_ACCOUNT_LOCKED));
        assertThat(jdbc.queryForObject("SELECT status FROM banking.app_user WHERE user_id = ?", String.class, userId))
                .isEqualTo("LOCKED");
        assertThat(jdbc.queryForObject("SELECT failed_attempt_count FROM banking.app_user WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(5);
        assertThat(identityAudit.calls.get()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void identityResendRotatesOtpAndExtendsExpiry() throws Exception {
        long userId = identity.createUser("resend-user", "P@ssw0rd!", "STAFF", "OPERATIONS");
        IdentityService.LoginResult login = identity.login(new IdentityService.LoginCommand("resend-user", "P@ssw0rd!",
                UUID.randomUUID(), "Browser", "fingerprint"));
        UUID challengeId = login.challenge().challengeId();
        String oldCode = otp.code(challengeId);
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE banking.auth_challenge SET last_sent_at = NULL,
                    expires_at = now() + interval '1 second' WHERE challenge_ref = ?
                """, challengeId));
        Instant shortenedExpiry = jdbc.queryForObject(
                "SELECT expires_at FROM banking.auth_challenge WHERE challenge_ref = ?",
                OffsetDateTime.class, challengeId).toInstant();

        StepUpChallengePort.Challenge resent = identity.resend(challengeId, null);
        String newCode = otp.code(challengeId);
        assertThat(newCode).isNotEqualTo(oldCode);
        assertThat(resent.expiresAt()).isAfter(shortenedExpiry);
        assertThatThrownBy(() -> identity.verifyLoginOtp(challengeId, oldCode))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_OTP_INVALID));
        assertThat(identity.verifyLoginOtp(challengeId, newCode)).isNotNull();
    }

    @Test
    void customerAndAccountServicesEnforceOwnershipPermissionsAndCanonicalDtos() throws Exception {
        LoginFixture first = customerFixture("first", "CIF00000011", "001122334455", "100000000001");
        LoginFixture second = customerFixture("second", "CIF00000012", "009988776655", "100000000002");

        MvcResult profile = mvc.perform(get("/api/v1/customers/me").cookie(first.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullName").value("Nguyen Van first"))
                .andExpect(jsonPath("$.cifNumber").doesNotExist())
                .andExpect(jsonPath("$.maskedNationalId").doesNotExist())
                .andExpect(jsonPath("$.kycStatus").doesNotExist()).andReturn();
        assertThat(profile.getResponse().getContentAsString()).doesNotContain("001122334455");

        mvc.perform(get("/api/v1/accounts/{id}", second.accountId()).cookie(first.cookie()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        mvc.perform(get("/api/v1/accounts").cookie(first.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].accountType").doesNotExist())
                .andExpect(jsonPath("$.items[0].accountNumberMasked").value("**** **** 0001"));

        long orphanUser = identity.createUser("orphan", "P@ssw0rd!", "CUSTOMER", "CUSTOMER");
        LoginSession orphan = login("orphan", UUID.randomUUID());
        mvc.perform(get("/api/v1/accounts/{id}", second.accountId()).cookie(orphan.cookie()))
                .andExpect(status().isForbidden());

        long staffId = identity.createUser("operations", "P@ssw0rd!", "STAFF", "OPERATIONS");
        LoginSession staff = login("operations", UUID.randomUUID());
        mvc.perform(get("/api/v1/admin/customers/{id}", first.customerId()).cookie(staff.cookie()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        mvc.perform(get("/api/v1/admin/customers/{id}/accounts", first.customerId()).cookie(staff.cookie()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        mvc.perform(get("/api/v1/accounts/{id}", first.accountId()).cookie(staff.cookie()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

        SessionAuthenticator.Session customerActor = identity.authenticate(first.session().rawToken()).orElseThrow();
        assertThatThrownBy(() -> customers.staffProfile(customerActor, second.customerId()))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        SessionAuthenticator.Session forgedOwner = new SessionAuthenticator.Session(customerActor.userId(),
                Long.toString(second.customerId()), customerActor.authorities(), customerActor.csrfTokenHash());
        assertThatThrownBy(() -> customers.selfProfile(forgedOwner))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        assertThatThrownBy(() -> accounts.listOwn(forgedOwner, null))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        assertThatThrownBy(() -> accounts.detail(forgedOwner, second.accountId()))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        assertThat(staffId).isPositive();
        assertThat(orphanUser).isPositive();
    }

    @Test
    void customerSelfProfileRejectsSameRoleCrossCustomerScopeAtServiceBoundary() throws Exception {
        LoginFixture first = customerFixture("scope-owner-a", "CIF00000071", "777788889999", "700000000001");
        LoginFixture second = customerFixture("scope-owner-b", "CIF00000072", "888899990000", "700000000002");
        SessionAuthenticator.Session firstActor = identity.authenticate(first.session().rawToken()).orElseThrow();
        SessionAuthenticator.Session secondActor = identity.authenticate(second.session().rawToken()).orElseThrow();

        assertThat(firstActor.authorities()).contains("ROLE_CUSTOMER", "ACCOUNT_READ_SELF");
        assertThat(secondActor.authorities()).contains("ROLE_CUSTOMER", "ACCOUNT_READ_SELF");
        assertThat(customers.selfProfile(firstActor).customerId()).isEqualTo(Long.toString(first.customerId()));

        SessionAuthenticator.Session forgedCrossCustomer = new SessionAuthenticator.Session(firstActor.userId(),
                secondActor.customerId(), firstActor.authorities(), firstActor.csrfTokenHash());
        assertThatThrownBy(() -> customers.selfProfile(forgedCrossCustomer))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    }

    @Test
    void accountCreationAndMutationFailClosedWithoutLedgerIntegration() {
        long userId = identity.createUser("money-owner", "P@ssw0rd!", "CUSTOMER", "CUSTOMER");
        long customerId = customers.create(new CustomerService.CreateCustomer(userId, "CIF00000021", "Money Owner",
                "+84901111222", "money@example.com", "111122223333", "VERIFIED"));
        assertThatThrownBy(() -> accounts.create(new AccountService.CreateAccount(customerId, "300000000001",
                "CHECKING", new BigDecimal("1.00"))))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        long accountId = accounts.create(new AccountService.CreateAccount(customerId, "300000000001",
                "CHECKING", new BigDecimal("0.00")));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                accounts.debitConditional(accountId, BigDecimal.ONE)))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(accounts.getAccountOrThrow(accountId).availableBalance()).isEqualByComparingTo("0.00");
        assertThat(accounts.getAccountOrThrow(accountId).ledgerBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void everySupportedAccountCreationVariantPersistsZeroOpeningBalances() {
        long userId = identity.createUser("zero-balance-owner", "P@ssw0rd!", "CUSTOMER", "CUSTOMER");
        long customerId = customers.create(new CustomerService.CreateCustomer(userId, "CIF00000073",
                "Zero Balance Owner", "+84907777333", "zero@example.com", "999900001111", "VERIFIED"));

        long checkingId = accounts.create(new AccountService.CreateAccount(customerId, "700000000003",
                "CHECKING", BigDecimal.ZERO));
        long savingsId = accounts.create(new AccountService.CreateAccount(customerId, "700000000004",
                "SAVINGS", new BigDecimal("0.00")));

        for (long accountId : List.of(checkingId, savingsId)) {
            AccountService.AccountSnapshot account = accounts.getAccountOrThrow(accountId);
            assertThat(account.availableBalance()).isEqualByComparingTo("0.00");
            assertThat(account.ledgerBalance()).isEqualByComparingTo("0.00");
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM banking.account
                WHERE customer_id = ? AND available_balance = 0 AND ledger_balance = 0
                """, Integer.class, customerId)).isEqualTo(2);
    }

    @Test
    void customerProvisioningRejectsStaffIdentity() {
        long staff = identity.createUser("staff-profile", "P@ssw0rd!", "STAFF", "OPERATIONS");
        assertThatThrownBy(() -> customers.create(new CustomerService.CreateCustomer(staff, "CIF00000031",
                "Invalid Staff", "+84901111222", "staff@example.com", "222233334444", "VERIFIED")))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.ROLE_CONFLICT));
    }

    @Test
    void customerValidationUniquenessMassAssignmentAndRollbackAreEnforced() throws Exception {
        long userId = identity.createUser("customer-integrity", "P@ssw0rd!", "CUSTOMER", "CUSTOMER");
        assertThatThrownBy(() -> customers.create(new CustomerService.CreateCustomer(userId, "bad cif",
                "Customer Integrity", "not-a-phone", "invalid", "123", "UNKNOWN")))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.customer WHERE user_id = ?",
                Integer.class, userId)).isZero();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            customers.create(new CustomerService.CreateCustomer(userId, "CIF00000041", "Customer Integrity",
                    "+84901111222", "integrity@example.com", "444455556666", "VERIFIED"));
            throw new IllegalStateException("rollback probe");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.customer WHERE user_id = ?",
                Integer.class, userId)).isZero();

        long customerId = customers.create(new CustomerService.CreateCustomer(userId, "CIF00000041",
                "Customer Integrity", "+84901111222", "integrity@example.com", "444455556666", "VERIFIED"));
        long secondUser = identity.createUser("customer-duplicate", "P@ssw0rd!", "CUSTOMER", "CUSTOMER");
        assertThatThrownBy(() -> customers.create(new CustomerService.CreateCustomer(secondUser, "CIF00000042",
                "Duplicate Identity", "+84902222333", "duplicate@example.com", "444455556666", "VERIFIED")))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.customer", Integer.class)).isOne();

        LoginSession session = login("customer-integrity", UUID.randomUUID());
        mvc.perform(post("/api/v1/customers/me").cookie(session.cookie())
                        .header("X-CSRF-Token", session.session().csrfToken())
                        .contentType("application/json")
                        .content("{\"status\":\"INACTIVE\",\"fullName\":\"Attacker Controlled\"}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        assertThat(customers.getCustomerById(customerId).status()).isEqualTo("ACTIVE");
        assertThat(customers.getCustomerById(customerId).fullName()).isEqualTo("Customer Integrity");
    }

    @Test
    void accountValidationLifecycleReadsMassAssignmentAndRollbackAreEnforced() throws Exception {
        long userId = identity.createUser("account-integrity", "P@ssw0rd!", "CUSTOMER", "CUSTOMER");
        long customerId = customers.create(new CustomerService.CreateCustomer(userId, "CIF00000051",
                "Account Integrity", "+84903333444", "account@example.com", "555566667777", "VERIFIED"));

        assertThatThrownBy(() -> accounts.create(new AccountService.CreateAccount(customerId, "invalid",
                "CURRENT", BigDecimal.ZERO)))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        transactions.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.customer SET status = 'INACTIVE' WHERE customer_id = ?", customerId));
        assertThatThrownBy(() -> accounts.create(new AccountService.CreateAccount(customerId, "500000000002",
                "CHECKING", new BigDecimal("0.00"))))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        transactions.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.customer SET status = 'ACTIVE' WHERE customer_id = ?", customerId));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            accounts.create(new AccountService.CreateAccount(customerId, "500000000001", "CHECKING",
                    new BigDecimal("0.00")));
            throw new IllegalStateException("rollback probe");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.account WHERE customer_id = ?",
                Integer.class, customerId)).isZero();

        long accountId = accounts.create(new AccountService.CreateAccount(customerId, "500000000001", "CHECKING",
                new BigDecimal("0.00")));
        assertThatThrownBy(() -> accounts.create(new AccountService.CreateAccount(customerId, "5000-0000-0001",
                "SAVINGS", new BigDecimal("0.00"))))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));

        LoginSession session = login("account-integrity", UUID.randomUUID());
        mvc.perform(post("/api/v1/accounts/{id}", accountId).cookie(session.cookie())
                        .header("X-CSRF-Token", session.session().csrfToken())
                        .contentType("application/json")
                        .content("{\"availableBalance\":\"999999.00\",\"status\":\"CLOSED\"}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(get("/api/v1/accounts/{id}", -1).cookie(session.cookie()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/accounts").param("status", "FROZEN").cookie(session.cookie()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        transactions.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.account SET status = 'LOCKED', updated_at = now() WHERE account_id = ?", accountId));
        MvcResult list = mvc.perform(get("/api/v1/accounts").param("status", "LOCKED").cookie(session.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("LOCKED")).andReturn();
        JsonNode response = json.readTree(list.getResponse().getContentAsString());
        assertThat(response.path("items").get(0).path("asOf").asText()).isEqualTo(response.path("asOf").asText());
        assertThat(accounts.getAccountOrThrow(accountId).availableBalance()).isEqualByComparingTo("0.00");
        assertThat(accounts.getAccountOrThrow(accountId).ledgerBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void concurrentCustomerAndAccountProvisioningPreservesUniqueConstraints() throws Exception {
        long userId = identity.createUser("race-customer", "P@ssw0rd!", "CUSTOMER", "CUSTOMER");
        AtomicInteger winners = new AtomicInteger();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        race(() -> customers.create(new CustomerService.CreateCustomer(userId, "CIF00000061", "Race Customer",
                "+84904444555", "race@example.com", "666677778888", "VERIFIED")), winners, failures);
        assertThat(winners).hasValue(1);
        assertThat(failures).singleElement().isInstanceOfSatisfying(BankingBusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));

        long customerId = customers.getCustomerByUserId(userId).customerId();
        winners.set(0);
        failures.clear();
        race(() -> accounts.create(new AccountService.CreateAccount(customerId, "600000000001", "CHECKING",
                new BigDecimal("0.00"))), winners, failures);
        assertThat(winners).hasValue(1);
        assertThat(failures).singleElement().isInstanceOfSatisfying(BankingBusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.account WHERE account_no_last4 = '0001'",
                Integer.class)).isOne();
    }

    @Test
    void approvalListUsesTypedFiltersHidesPayloadAndEnforcesApplicationPermissions() throws Exception {
        AdminFixture fixture = adminFixture();
        ApprovalService.ApprovalSummary created = createRoleApproval(fixture.maker(), fixture.target(), "AUDITOR");
        long approvalId = Long.parseLong(created.approvalId());

        ApprovalService.ApprovalPage page = approvals.list(fixture.maker().actor(), new ApprovalService.ApprovalFilter(
                null, null, fixture.maker().userId(), null, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60),
                0, 20, "createdAt,desc"));
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().approvalId()).isEqualTo(created.approvalId());

        mvc.perform(get("/api/v1/admin/approvals").cookie(fixture.maker().cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].payload").doesNotExist());

        long operations = identity.createUser("ops-reader", "P@ssw0rd!", "STAFF", "OPERATIONS");
        LoginSession opsSession = login("ops-reader", UUID.randomUUID());
        ApprovalService.Actor ops = actor(opsSession.session());
        assertThat(approvals.list(ops, new ApprovalService.ApprovalFilter(null, null, null, null, null, null,
                0, 20, "createdAt,desc")).items()).isEmpty();
        assertThatThrownBy(() -> approvals.get(ops, approvalId))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));

        ApprovalService.Actor unauthorized = opsSession.actor();
        assertThatThrownBy(() -> approvals.requireStepUp(unauthorized, ApprovalService.RequestType.ROLE_CHANGE,
                json.createObjectNode(), null))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    }

    @Test
    void approvalExpiryCheckerPermissionAndConcurrentDecisionAreEnforced() throws Exception {
        AdminFixture fixture = adminFixture();
        ApprovalService.ApprovalSummary expired = createRoleApproval(fixture.maker(), fixture.target(), "AUDITOR");
        long expiredId = Long.parseLong(expired.approvalId());
        transactions.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.approval_request SET expires_at = now() - interval '1 second' WHERE approval_id = ?",
                expiredId));
        UUID expiredChallenge = decisionChallenge(fixture.checkerOne(), expiredId, "APPROVE", "expired");
        assertThatThrownBy(() -> approvals.decide(expiredId, ApprovalService.RequestType.ROLE_CHANGE,
                fixture.checkerOne().actor(), ApprovalService.Decision.APPROVE, "expired", expiredChallenge))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        assertThat(jdbc.queryForObject("SELECT status FROM banking.auth_challenge WHERE challenge_ref = ?",
                String.class, expiredChallenge)).isEqualTo("VERIFIED");
        transactions.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.approval_request SET status = 'CANCELLED' WHERE approval_id = ?", expiredId));

        ApprovalService.ApprovalSummary active = createRoleApproval(fixture.maker(), fixture.target(), "AUDITOR");
        long activeId = Long.parseLong(active.approvalId());
        transactions.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.user_privilege SET status = 'REVOKED' WHERE user_id = ?",
                fixture.checkerOne().userId()));
        ApprovalService.Actor noChecker = actor(fixture.checkerOne().session());
        assertThatThrownBy(() -> approvals.decide(activeId, ApprovalService.RequestType.ROLE_CHANGE, noChecker,
                ApprovalService.Decision.APPROVE, "no privilege", UUID.randomUUID()))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        transactions.executeWithoutResult(status -> jdbc.update(
                "UPDATE banking.user_privilege SET status = 'ACTIVE' WHERE user_id = ?",
                fixture.checkerOne().userId()));

        UUID challengeOne = decisionChallenge(fixture.checkerOne(), activeId, "APPROVE", "approved");
        UUID challengeTwo = decisionChallenge(fixture.checkerTwo(), activeId, "APPROVE", "approved");
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        var pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> decide(start, activeId, fixture.checkerOne().actor(), challengeOne, winners, failures));
        pool.submit(() -> decide(start, activeId, fixture.checkerTwo().actor(), challengeTwo, winners, failures));
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(winners).hasValue(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).isInstanceOfSatisfying(BankingBusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED));
        assertThat(approvals.get(fixture.maker().actor(), activeId).status()).isEqualTo("APPLIED");
        assertThat(identity.authenticate(fixture.targetSession().session().rawToken())).isEmpty();
    }

    @Test
    void makerCannotSelfApproveAndLimitFlowIsExplicitlyBlocked() throws Exception {
        AdminFixture fixture = adminFixture();
        ApprovalService.ApprovalSummary created = createRoleApproval(fixture.maker(), fixture.target(), "AUDITOR");
        long approvalId = Long.parseLong(created.approvalId());
        approvalAudit.failStandalone = true;
        assertThatThrownBy(() -> approvals.decide(approvalId, ApprovalService.RequestType.ROLE_CHANGE,
                fixture.maker().actor(), ApprovalService.Decision.APPROVE, "self", UUID.randomUUID()))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.ROLE_CONFLICT));
        assertThat(approvals.get(fixture.maker().actor(), approvalId).status()).isEqualTo("PENDING");

        ObjectNode limitPayload = json.createObjectNode().put("customerId", "1")
                .put("perTransactionLimit", "10.00").put("dailyLimit", "20.00").put("currency", "VND");
        ApprovalService.Actor operations = fixture.checkerOne().actor();
        assertThatThrownBy(() -> approvals.requireStepUp(operations, ApprovalService.RequestType.LIMIT_CHANGE,
                limitPayload, null)).isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    private ApprovalService.ApprovalSummary createRoleApproval(LoginSession maker, LoginSession target,
            String roleCode) {
        ObjectNode payload = json.createObjectNode().put("operation", "ASSIGN_ROLE")
                .put("targetUserId", Long.toString(target.userId())).put("roleCode", roleCode).put("reason", "rotation");
        JsonNode binding = approvals.binding("CREATE", "ROLE_CHANGE", Long.toString(target.userId()), payload);
        UUID challenge = challengeFromRequired(maker, ApprovalService.RequestType.ROLE_CHANGE, binding);
        return approvals.create(maker.actor(), ApprovalService.RequestType.ROLE_CHANGE, "APP_USER",
                Long.toString(target.userId()), payload, challenge);
    }

    private UUID decisionChallenge(LoginSession checker, long approvalId, String decision, String note) {
        JsonNode payload = json.valueToTree(Map.of("decision", decision, "decisionNote", note));
        JsonNode binding = approvals.binding("DECIDE_" + decision, "ROLE_CHANGE", Long.toString(approvalId), payload);
        return challengeFromRequired(checker, ApprovalService.RequestType.ROLE_CHANGE, binding);
    }

    private UUID challengeFromRequired(LoginSession actor, ApprovalService.RequestType type, JsonNode binding) {
        try {
            approvals.requireStepUp(actor.actor(), type, binding, null);
            throw new AssertionError("Expected step-up requirement");
        } catch (BankingBusinessException required) {
            assertThat(required.code()).isEqualTo(ErrorCode.STEP_UP_REQUIRED);
            @SuppressWarnings("unchecked")
            Map<String, Object> challenge = (Map<String, Object>) required.extensions().get("challenge");
            UUID id = UUID.fromString(challenge.get("challengeId").toString());
            identity.verifyStepUp(actor.userId(), id, "OTP", otp.code(id));
            return id;
        }
    }

    private void decide(CountDownLatch start, long approvalId, ApprovalService.Actor checker, UUID challenge,
            AtomicInteger winners, List<Throwable> failures) {
        try {
            start.await();
            approvals.decide(approvalId, ApprovalService.RequestType.ROLE_CHANGE, checker,
                    ApprovalService.Decision.APPROVE, "approved", challenge);
            winners.incrementAndGet();
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    private void race(Runnable action, AtomicInteger winners, List<Throwable> failures)
            throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> attempt(start, action, winners, failures));
        pool.submit(() -> attempt(start, action, winners, failures));
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }

    private void attempt(CountDownLatch start, Runnable action, AtomicInteger winners, List<Throwable> failures) {
        try {
            start.await();
            action.run();
            winners.incrementAndGet();
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    private AdminFixture adminFixture() throws Exception {
        LoginSession maker = staffFixture("maker", "ADMIN", true);
        LoginSession checkerOne = staffFixture("checker1", "ADMIN", true);
        LoginSession checkerTwo = staffFixture("checker2", "ADMIN", true);
        LoginSession target = staffFixture("target", "OPERATIONS", false);
        return new AdminFixture(maker, checkerOne, checkerTwo, target, target);
    }

    private LoginSession staffFixture(String username, String role, boolean checker) throws Exception {
        long userId = identity.createUser(username, "P@ssw0rd!", "STAFF", role);
        if (checker) {
            transactions.executeWithoutResult(status -> jdbc.update(
                    "INSERT INTO banking.user_privilege(user_id, privilege_code) VALUES (?, 'CHECKER')", userId));
        }
        return login(username, UUID.randomUUID());
    }

    private LoginFixture customerFixture(String username, String cif, String nationalId, String accountNumber)
            throws Exception {
        long userId = identity.createUser(username, "P@ssw0rd!", "CUSTOMER", "CUSTOMER");
        long customerId = customers.create(new CustomerService.CreateCustomer(userId, cif, "Nguyen Van " + username,
                "+84901111222", username + "@example.com", nationalId, "VERIFIED"));
        long accountId = accounts.create(new AccountService.CreateAccount(customerId, accountNumber, "CHECKING",
                new BigDecimal("0.00")));
        LoginSession session = login(username, UUID.randomUUID());
        return new LoginFixture(userId, customerId, accountId, session.cookie(), session.session());
    }

    private LoginSession login(String username, UUID deviceRef) throws Exception {
        IdentityService.LoginResult login = identity.login(new IdentityService.LoginCommand(username, "P@ssw0rd!",
                deviceRef, "Test", "fingerprint-" + deviceRef));
        IdentityService.SessionGrant session = identity.verifyLoginOtp(login.challenge().challengeId(),
                otp.code(login.challenge().challengeId()));
        return new LoginSession(session.userId(), session, new Cookie(COOKIE_NAME, session.rawToken()), actor(session));
    }

    private ApprovalService.Actor actor(IdentityService.SessionGrant session) {
        SessionAuthenticator.Session authenticated = identity.authenticate(session.rawToken()).orElseThrow();
        return ApprovalService.Actor.from(authenticated);
    }

    private static UUID challengeId(MvcResult result) throws Exception {
        return UUID.fromString(new ObjectMapper().readTree(result.getResponse().getContentAsString())
                .path("challenge").path("challengeId").asText());
    }

    private static Cookie sessionCookie(MvcResult result) {
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        String prefix = COOKIE_NAME + "=";
        String token = setCookie.substring(prefix.length(), setCookie.indexOf(';'));
        return new Cookie(COOKIE_NAME, token);
    }

    private static void assertInvalidCredentials(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BankingBusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));
    }

    record LoginFixture(long userId, long customerId, long accountId, Cookie cookie,
            IdentityService.SessionGrant session) { }
    record LoginSession(long userId, IdentityService.SessionGrant session, Cookie cookie,
            ApprovalService.Actor actor) { }
    record AdminFixture(LoginSession maker, LoginSession checkerOne, LoginSession checkerTwo,
            LoginSession target, LoginSession targetSession) { }

    @TestConfiguration
    static class TestPorts {
        @Bean CapturingOtp otpDeliveryPort() { return new CapturingOtp(); }
        @Bean ThrowingIdentityAudit identitySecurityAuditSink() { return new ThrowingIdentityAudit(); }
        @Bean CapturingApprovalAudit approvalAuditSink() { return new CapturingApprovalAudit(); }
    }

    static final class CapturingOtp implements OtpDeliveryPort {
        private final Map<UUID, String> codes = new ConcurrentHashMap<>();
        @Override public void deliver(OtpDelivery delivery) { codes.put(delivery.challengeId(), delivery.oneTimeCode()); }
        String code(UUID id) { return codes.get(id); }
        void clear() { codes.clear(); }
    }

    static final class ThrowingIdentityAudit implements IdentitySecurityAuditSink {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean fail;
        @Override public void recordStandalone(SecurityEvent event) {
            calls.incrementAndGet();
            if (fail) throw new IllegalStateException("synthetic audit failure");
        }
        void reset() { calls.set(0); fail = false; }
    }

    static final class CapturingApprovalAudit implements ApprovalAuditSink {
        private final List<AuditEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile boolean failStandalone;
        @Override public void recordInTransaction(AuditEvent event) { events.add(event); }
        @Override public void recordStandalone(AuditEvent event) {
            if (failStandalone) throw new IllegalStateException("synthetic standalone audit failure");
            events.add(event);
        }
        void reset() { events.clear(); failStandalone = false; }
    }
}
