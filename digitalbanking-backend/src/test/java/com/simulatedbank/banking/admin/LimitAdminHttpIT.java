package com.simulatedbank.banking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.support.IntegratedLimitReferences;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import com.simulatedbank.banking.identity.api.StaffDirectory;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.limit.api.LimitEnforcement;
import com.simulatedbank.banking.limit.api.LimitRules;
import com.simulatedbank.banking.support.SqlAuditFixture;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import com.simulatedbank.banking.support.SqlFixture;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import({SqlFixture.class, LimitAdminHttpIT.FakeProviders.class, SqlAuditFixture.class})
class LimitAdminHttpIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("limit/init-roles.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", () -> "banking_app");
        properties.add("spring.datasource.password", () -> "test_app");
        properties.add("spring.flyway.url", postgres::getJdbcUrl);
        properties.add("spring.flyway.user", () -> "banking_migrator");
        properties.add("spring.flyway.password", () -> "test_migrator");
        properties.add("app.limit.policy.max-per-transaction-vnd", () -> "1000");
        properties.add("app.limit.policy.max-daily-vnd", () -> "2000");
    }

    private static final String CHANGE = """
            {"customerId":"11","perTransactionLimit":"100.00","dailyLimit":"200.00","reason":"Điều chỉnh theo yêu cầu"}
            """;
    private static final String APPROVE = "{\"decision\":\"APPROVE\",\"decisionNote\":\"Đồng ý\"}";
    private static final String REJECT = "{\"decision\":\"REJECT\",\"decisionNote\":\"Chưa phù hợp\"}";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SqlFixture jdbc;
    @Autowired TestPorts ports;
    @Autowired TransactionTemplate transactions;
    @Autowired LimitEnforcement enforcement;
    @Autowired LimitRules rules;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    private String correlation;

    @BeforeEach
    void reset() throws Exception {
        correlation = UUID.randomUUID().toString();
        ports.setResponsePurpose("SENSITIVE_ACTION");
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS e4_test");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS e4_test.challenge(
                      id UUID PRIMARY KEY, user_id BIGINT, action TEXT, binding_key TEXT, request_hash TEXT,
                      status TEXT, expires_at TIMESTAMPTZ, purpose TEXT)
                    """);
            statement.execute("GRANT USAGE ON SCHEMA e4_test TO banking_app");
            statement.execute("GRANT SELECT,INSERT,UPDATE ON e4_test.challenge TO banking_app");
            statement.execute("TRUNCATE e4_test.challenge, banking.approval_request, banking.limit_config, banking.limit_usage RESTART IDENTITY CASCADE");
            IntegratedLimitReferences.seed(connection, false);
        }
    }

    @Test
    void makerCreatesRealApprovalPendingConfigAndAuditFixtureWithoutChangingAllowance() throws Exception {
        var response = create(CHANGE);
        assertThat(response.getResponse().getHeader("Location")).isEqualTo("/api/v1/admin/approvals/1");
        var body = body(response);
        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(body.path("requestType").asText()).isEqualTo("LIMIT_CHANGE");
        assertThat(body.path("maker").path("displayName").asText()).isEqualTo("limit-fixture-1");
        assertThat(body.has("payload")).isFalse();
        assertThat(body.path("correlationId").asText()).isEqualTo(correlation);
        assertThat(jdbc.queryForList("SELECT status FROM banking.approval_request", String.class)).containsExactly("PENDING");
        assertThat(jdbc.queryForList("SELECT status FROM banking.limit_config", String.class)).containsExactly("PENDING_APPROVAL");
        assertThat(rules.resolve(11, 21, Instant.now()).items()).isEmpty();
        assertThat(count("banking.limit_usage")).isZero();
        assertAudit("LIMIT_CHANGE_REQUESTED", 1);
        assertThat(jdbc.queryForList("SELECT status FROM e4_test.challenge", String.class)).containsExactly("CONSUMED");
    }

    @Test
    void missingStepUpReturnsChallengeAndCreatesNoApprovalOrConfiguration() throws Exception {
        var response = send("maker", "/api/v1/admin/limits", CHANGE, null, 428);
        assertThat(body(response).path("challenge").path("purpose").asText()).isEqualTo("SENSITIVE_ACTION");
        assertThat(count("banking.approval_request")).isZero();
        assertThat(count("banking.limit_config")).isZero();
    }

    @Test
    void authenticationRolesCheckerPrivilegeAndCookieCsrfAreEnforced() throws Exception {
        mvc.perform(post("/api/v1/admin/limits").contentType("application/json").content(CHANGE)).andExpect(status().isUnauthorized());
        for (String actor : List.of("customer", "auditor")) send(actor, "/api/v1/admin/limits", CHANGE, null, 403);
        mvc.perform(post("/api/v1/admin/limits").cookie(new Cookie("DB_SESSION", "maker"))
                .contentType("application/json").content(CHANGE)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/limits").cookie(new Cookie("DB_SESSION", "maker"))
                .header("X-CSRF-Token", "csrf").contentType("application/json").content(CHANGE))
                .andExpect(status().isPreconditionRequired());
        create(CHANGE);
        send("no-checker", "/api/v1/admin/limits/1/approve", APPROVE, null, 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"customerId\":11,\"perTransactionLimit\":\"1.00\",\"dailyLimit\":\"2.00\"}",
            "{\"customerId\":\"11\",\"perTransactionLimit\":1,\"dailyLimit\":\"2.00\"}",
            "{\"customerId\":\"11\",\"perTransactionLimit\":\"1.001\",\"dailyLimit\":\"2.00\"}",
            "{\"customerId\":\"9223372036854775808\",\"perTransactionLimit\":\"1.00\",\"dailyLimit\":\"2.00\"}",
            "{\"customerId\":\"11\",\"perTransactionLimit\":\"3.00\",\"dailyLimit\":\"2.00\"}",
            "{\"customerId\":\"11\",\"perTransactionLimit\":\"1.00\",\"dailyLimit\":\"2.00\",\"makerId\":\"99\"}",
            "{\"customerId\":\"11\",\"perTransactionLimit\":\"1.00\",\"dailyLimit\":\"2.00\",\"reason\":\"otp=123456\"}"})
    void wireValidationRejectsMalformedRequestsBeforeAnyApproval(String invalid) throws Exception {
        send("maker", "/api/v1/admin/limits", invalid, null, 400);
        assertThat(count("banking.approval_request")).isZero();
        assertThat(count("banking.limit_config")).isZero();
    }

    @Test
    void missingCustomerAccountAndOwnershipAreDistinctAndDoNotCreateData() throws Exception {
        send("maker", "/api/v1/admin/limits", CHANGE.replace("\"11\"", "\"99\""), null, 404);
        send("maker", "/api/v1/admin/limits", CHANGE.replace("\"customerId\":\"11\"", "\"customerId\":\"11\",\"accountId\":\"99\""), null, 404);
        send("maker", "/api/v1/admin/limits", CHANGE.replace("\"customerId\":\"11\"", "\"customerId\":\"11\",\"accountId\":\"22\""), null, 403);
        assertThat(count("banking.approval_request")).isZero();
    }

    @Test
    void policyFutureAndUnsafeNotesAreRejectedBeforeMutation() throws Exception {
        send("maker", "/api/v1/admin/limits", CHANGE.replace("100.00", "1001.00").replace("200.00", "2000.00"), null, 422);
        send("maker", "/api/v1/admin/limits", CHANGE.replace("\"reason\"", "\"effectiveFrom\":\"2099-01-01T00:00:00Z\",\"reason\""), null, 400);
        send("maker", "/api/v1/admin/limits", CHANGE.replace("Điều chỉnh theo yêu cầu", "x".repeat(501)), null, 400);
        assertThat(count("banking.approval_request")).isZero();
    }

    @Test
    void makerBindingRejectsChangedPayloadWrongActorExpiryAndReplay() throws Exception {
        UUID challenge = verified("maker", "/api/v1/admin/limits", CHANGE);
        send("maker", "/api/v1/admin/limits", CHANGE.replace("100.00", "90.00"), challenge, 409);
        send("checker", "/api/v1/admin/limits", CHANGE, challenge, 409);
        assertThat(count("banking.approval_request")).isZero();
        transactions.executeWithoutResult(tx -> jdbc.update("UPDATE e4_test.challenge SET expires_at=now()-interval '1 second' WHERE id=?", challenge));
        send("maker", "/api/v1/admin/limits", CHANGE, challenge, 410);
        UUID fresh = verified("maker", "/api/v1/admin/limits", CHANGE);
        send("maker", "/api/v1/admin/limits", CHANGE, fresh, 201);
        send("maker", "/api/v1/admin/limits", CHANGE, fresh, 409);
        assertThat(count("banking.approval_request")).isEqualTo(1);
    }

    @Test
    void wrongPurposeAndUnknownChallengeAreRejectedWithoutBusinessChanges() throws Exception {
        UUID challenge = verified("maker", "/api/v1/admin/limits", CHANGE);
        transactions.executeWithoutResult(tx -> jdbc.update("UPDATE e4_test.challenge SET purpose='TRANSFER_STEP_UP' WHERE id=?", challenge));
        send("maker", "/api/v1/admin/limits", CHANGE, challenge, 409);
        send("maker", "/api/v1/admin/limits", CHANGE, UUID.randomUUID(), 404);
        assertThat(count("banking.approval_request")).isZero();
    }

    @Test
    void invalidProviderChallengePurposeIsNotPresentedAsSensitiveApprovalChallenge() throws Exception {
        ports.setResponsePurpose("TRANSFER_STEP_UP");
        send("maker", "/api/v1/admin/limits", CHANGE, null, 503);
        assertThat(count("banking.approval_request")).isZero();
        assertThat(count("banking.limit_config")).isZero();
    }

    @Test
    void numericInstantAndNumericDecisionDoNotCoerceIntoAcceptedWireValues() throws Exception {
        send("maker", "/api/v1/admin/limits", CHANGE.replace("\"reason\"", "\"effectiveFrom\":0,\"reason\""), null, 400);
        create(CHANGE);
        send("checker", "/api/v1/admin/limits/1/approve", "{\"decision\":0}", null, 400);
        assertThat(jdbc.queryForObject("SELECT status FROM banking.approval_request", String.class)).isEqualTo("PENDING");
    }

    @Test
    void approveProducesDetailAndActualLimitEnforcementUsesNewConfiguration() throws Exception {
        create(CHANGE);
        var response = decide("checker", 1, APPROVE);
        assertThat(body(response).path("status").asText()).isEqualTo("APPLIED");
        assertThat(body(response).path("checker").path("userId").asText()).isEqualTo("2");
        assertThat(body(response).path("payload").path("proposed").path("effectiveFrom").asText()).isNotEqualTo("IMMEDIATE");
        assertThat(body(response).path("result").path("version").asInt()).isEqualTo(1);
        assertThat(count("banking.limit_usage")).isZero();
        complete("90.00");
        var usage = jdbc.queryForList("SELECT * FROM banking.limit_usage ORDER BY usage_id");
        create(CHANGE.replace("100.00", "50.00").replace("200.00", "60.00"));
        decide("checker", 2, APPROVE);
        assertThat(jdbc.queryForList("SELECT * FROM banking.limit_usage ORDER BY usage_id")).isEqualTo(usage);
        assertThat(jdbc.queryForList("SELECT status FROM banking.limit_config ORDER BY limit_id", String.class))
                .containsExactly("SUPERSEDED", "ACTIVE");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> complete("1.00"))
                .isInstanceOfSatisfying(BankingBusinessException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.LIMIT_EXCEEDED));
        assertAudit("LIMIT_CHANGE_APPROVED", 2);
    }

    @Test
    void rejectKeepsCurrentConfigurationAndUsageAndCannotBeAppliedAgain() throws Exception {
        create(CHANGE); decide("checker", 1, APPROVE); complete("10.00");
        var usage = jdbc.queryForList("SELECT * FROM banking.limit_usage");
        create(CHANGE.replace("100.00", "50.00"));
        var rejected = decide("checker", 2, REJECT);
        assertThat(body(rejected).path("status").asText()).isEqualTo("REJECTED");
        assertThat(body(rejected).has("result")).isFalse();
        assertThat(jdbc.queryForList("SELECT status FROM banking.limit_config ORDER BY limit_id", String.class))
                .containsExactly("ACTIVE", "REJECTED");
        assertThat(jdbc.queryForList("SELECT * FROM banking.limit_usage")).isEqualTo(usage);
        send("checker", "/api/v1/admin/limits/2/approve", APPROVE, null, 409);
        assertAudit("LIMIT_CHANGE_REJECTED", 1);
    }

    @Test
    void selfDecisionReturns409AndCallsStandaloneAuditFixture() throws Exception {
        create(CHANGE);
        send("maker", "/api/v1/admin/limits/1/approve", APPROVE, null, 409);
        assertThat(jdbc.queryForObject("SELECT status FROM banking.approval_request", String.class)).isEqualTo("PENDING");
        assertAudit("SECURITY_ACCESS_DENIED", 1);
        assertThat(count("banking.limit_usage")).isZero();
    }

    @Test
    void auditUnavailableDoesNotTurnSelfDenialIntoApproval() throws Exception {
        create(CHANGE);
        double before = metrics.counter("audit.security.fallback").count();
        permission("REVOKE INSERT ON limit_test.audit_event FROM banking_app");
        try { send("maker", "/api/v1/admin/limits/1/approve", APPROVE, null, 409); }
        finally { permission("GRANT INSERT ON limit_test.audit_event TO banking_app"); }
        assertThat(metrics.counter("audit.security.fallback").count()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT status FROM banking.approval_request", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM banking.limit_config", String.class)).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void checkerBindingRejectsChangedDecisionAndUnknownApprovalIs404() throws Exception {
        create(CHANGE);
        create(CHANGE);
        UUID challenge = verified("checker", "/api/v1/admin/limits/1/approve", APPROVE);
        send("checker", "/api/v1/admin/limits/1/approve", REJECT, challenge, 409);
        send("checker2", "/api/v1/admin/limits/1/approve", APPROVE, challenge, 409);
        send("checker", "/api/v1/admin/limits/2/approve", APPROVE, challenge, 409);
        send("checker", "/api/v1/admin/limits/999/approve", APPROVE, null, 404);
        assertThat(jdbc.queryForList("SELECT status FROM banking.approval_request", String.class)).containsExactly("PENDING", "PENDING");
    }

    @Test
    void staleConfigurationRejectsApplicationAndRollsBackChallengeConsumption() throws Exception {
        create(CHANGE);
        create(CHANGE.replace("100.00", "50.00"));
        UUID second = verified("checker", "/api/v1/admin/limits/2/approve", APPROVE);
        decide("checker", 1, APPROVE);
        send("checker", "/api/v1/admin/limits/2/approve", APPROVE, second, 409);
        assertThat(jdbc.queryForObject("SELECT status FROM e4_test.challenge WHERE id=?", String.class, second)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForList("SELECT status FROM banking.approval_request ORDER BY approval_id", String.class))
                .containsExactly("APPLIED", "PENDING");
    }

    @Test
    void newAccountScopeCannotBeEnabledAfterCustomerUsage() throws Exception {
        create(CHANGE); decide("checker", 1, APPROVE); complete("10.00");
        String accountChange = CHANGE.replace("\"customerId\":\"11\"", "\"customerId\":\"11\",\"accountId\":\"21\"");
        create(accountChange);
        UUID challenge = verified("checker", "/api/v1/admin/limits/2/approve", APPROVE);
        send("checker", "/api/v1/admin/limits/2/approve", APPROVE, challenge, 409);
        assertThat(jdbc.queryForObject("SELECT used_amount FROM banking.limit_usage", java.math.BigDecimal.class)).isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("SELECT status FROM banking.approval_request WHERE approval_id=2", String.class)).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sqlAuditFixtureFailureRollsBackRealApprovalConfigAndTestIdentityConsumption(boolean decision) throws Exception {
        String path = "/api/v1/admin/limits";
        String actor = "maker";
        String payload = CHANGE;
        if (decision) { create(CHANGE); path += "/1/approve"; actor = "checker"; payload = APPROVE; }
        UUID challenge = verified(actor, path, payload);
        var before = jdbc.queryForList("SELECT * FROM banking.limit_config ORDER BY limit_id");
        permission("REVOKE INSERT ON limit_test.audit_event FROM banking_app");
        try { send(actor, path, payload, challenge, 500); }
        finally { permission("GRANT INSERT ON limit_test.audit_event TO banking_app"); }
        assertThat(jdbc.queryForList("SELECT * FROM banking.limit_config ORDER BY limit_id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM e4_test.challenge WHERE id=?", String.class, challenge)).isEqualTo("VERIFIED");
        if (decision) assertThat(jdbc.queryForObject("SELECT status FROM banking.approval_request", String.class)).isEqualTo("PENDING");
        else assertThat(count("banking.approval_request")).isZero();
    }

    @Test
    void twoCheckersCompeteAndOnlyOneDecisionConsumesItsChallenge() throws Exception {
        create(CHANGE);
        UUID first = verified("checker", "/api/v1/admin/limits/1/approve", APPROVE);
        UUID second = verified("checker2", "/api/v1/admin/limits/1/approve", APPROVE);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var left = workers.submit(() -> { start.await(); return request("checker", "/api/v1/admin/limits/1/approve", APPROVE, first).getResponse().getStatus(); });
            var right = workers.submit(() -> { start.await(); return request("checker2", "/api/v1/admin/limits/1/approve", APPROVE, second).getResponse().getStatus(); });
            start.countDown();
            assertThat(List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForList("SELECT status FROM e4_test.challenge WHERE id IN (?,?)", String.class, first, second))
                .containsExactlyInAnyOrder("CONSUMED", "VERIFIED");
        assertAudit("LIMIT_CHANGE_APPROVED", 1);
        assertThat(jdbc.queryForList("SELECT status FROM banking.limit_config", String.class)).containsExactly("ACTIVE");
    }

    @Test
    void databaseConstraintsAndApplicationPrivilegesProtectApprovalHistory() throws Exception {
        create(CHANGE);
        for (String column : List.of("maker_id", "request_type", "target_type", "target_id", "created_at", "correlation_id")) {
            assertThat(jdbc.queryForObject("SELECT has_column_privilege(current_user,'banking.approval_request',?,'UPDATE')", Boolean.class, column)).isFalse();
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactions.executeWithoutResult(tx ->
                jdbc.update("UPDATE banking.approval_request SET status='APPLIED',checker_id=maker_id,decided_at=now(),applied_at=now()")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,'banking.approval_request','DELETE')", Boolean.class)).isFalse();
    }

    private MvcResult create(String payload) throws Exception {
        UUID challenge = verified("maker", "/api/v1/admin/limits", payload);
        return send("maker", "/api/v1/admin/limits", payload, challenge, 201);
    }
    private MvcResult decide(String actor, long id, String payload) throws Exception {
        String path = "/api/v1/admin/limits/" + id + "/approve";
        return send(actor, path, payload, verified(actor, path, payload), 200);
    }
    private UUID verified(String actor, String path, String payload) throws Exception {
        var response = send(actor, path, payload, null, 428);
        UUID id = UUID.fromString(body(response).path("challenge").path("challengeId").asText());
        transactions.executeWithoutResult(tx -> jdbc.update("UPDATE e4_test.challenge SET status='VERIFIED' WHERE id=?", id));
        return id;
    }
    private MvcResult send(String actor, String path, String payload, UUID challenge, int expected) throws Exception {
        var result = request(actor, path, payload, challenge);
        if (result.getResponse().getStatus() != expected && result.getResolvedException() != null) {
            throw new AssertionError("Unexpected HTTP status " + result.getResponse().getStatus(),
                    result.getResolvedException());
        }
        assertThat(result.getResponse().getStatus()).describedAs("%s %s: %s", actor, path, result.getResponse().getContentAsString())
                .isEqualTo(expected);
        return result;
    }
    private MvcResult request(String actor, String path, String payload, UUID challenge) throws Exception {
        String idempotencyKey = "limit-admin-" + UUID.nameUUIDFromBytes(
                (actor + "|" + path + "|" + payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request = post(path).header("Authorization", "Bearer " + actor).header("X-Correlation-ID", correlation)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json").content(payload);
        if (challenge != null) request.header("X-Step-Up-Challenge", challenge.toString());
        return mvc.perform(request).andReturn();
    }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private void assertAudit(String action, int expected) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM limit_test.audit_event WHERE correlation_id=? AND action=?", Integer.class, correlation, action))
                .isEqualTo(expected);
    }
    private void complete(String amount) {
        transactions.executeWithoutResult(tx -> enforcement.recordCompleted(enforcement.lockAndCheck(11, 21, new java.math.BigDecimal(amount))));
    }
    private static void permission(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
             var statement = connection.createStatement()) { statement.execute(sql); }
    }

    /** Fake only: no OTP/user/account production implementation is supplied by this fixture. */
    @TestConfiguration
    static class FakeProviders {
        @Bean @org.springframework.context.annotation.Primary TestPorts testPorts(SqlFixture jdbc, TransactionTemplate transactions) { return new TestPorts(jdbc, transactions); }
        @Bean @org.springframework.context.annotation.Primary CustomerQuery customerQuery() {
            CustomerQuery provider = org.mockito.Mockito.mock(CustomerQuery.class);
            org.mockito.Mockito.when(provider.findByCustomerId(11))
                    .thenReturn(Optional.of(new CustomerQuery.CustomerIdentity(11, 1, CustomerQuery.Status.ACTIVE)));
            return provider;
        }
        @Bean @org.springframework.context.annotation.Primary AccountQuery accountQuery() {
            AccountQuery provider = org.mockito.Mockito.mock(AccountQuery.class);
            org.mockito.Mockito.doAnswer(call -> {
                long accountId = call.getArgument(0);
                long customerId = call.getArgument(1);
                if (accountId != 21 && accountId != 22) {
                    throw new BankingBusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
                }
                if ((accountId == 21 ? 11 : 12) != customerId) {
                    throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
                }
                return null;
            }).when(provider).assertOwnership(org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
            return provider;
        }
        @Bean @org.springframework.context.annotation.Primary SessionAuthenticator sessionAuthenticator() {
            return token -> {
                long id = switch (token) { case "maker" -> 1; case "checker", "no-checker" -> 2; case "checker2" -> 3; default -> 4; };
                Set<String> permissions = switch (token) {
                    case "maker", "checker", "checker2" -> Set.of("ROLE_OPERATIONS", "LIMIT_MAKER", "CHECKER");
                    case "no-checker" -> Set.of("ROLE_OPERATIONS", "LIMIT_MAKER");
                    case "auditor" -> Set.of("ROLE_AUDITOR", "LIMIT_MAKER", "CHECKER");
                    case "customer" -> Set.of("ROLE_CUSTOMER");
                    default -> Set.of();
                };
                return Optional.of(new SessionAuthenticator.Session(Long.toString(id), null, permissions, HmacService.sha256Hex("csrf")));
            };
        }
    }

    /** Test-only SQL fake verifies contract vectors and caller transaction wiring, never real identity. */
    static class TestPorts implements StepUpChallengePort, StaffDirectory {
        private final SqlFixture jdbc;
        private final TransactionTemplate transactions;
        private String responsePurpose = "SENSITIVE_ACTION";
        public void setResponsePurpose(String purpose) { responsePurpose = purpose; }
        TestPorts(SqlFixture jdbc, TransactionTemplate transactions) { this.jdbc = jdbc; this.transactions = transactions; }

        @Override public StepUpChallengePort.ChallengeView requestChallenge(StepUpChallengePort.ChallengeRequest request) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            UUID id = UUID.randomUUID();
            Instant expiry = Instant.now().plusSeconds(300);
            transactions.executeWithoutResult(tx -> jdbc.update("""
                    INSERT INTO e4_test.challenge VALUES (?,?,?,?,?,'PENDING',?,'SENSITIVE_ACTION')
                    """, id, request.userId(), "SENSITIVE_ACTION", request.bindingKey(),
                    request.requestHash(), java.sql.Timestamp.from(expiry)));
            return new StepUpChallengePort.ChallengeView(id.toString(),
                    StepUpChallengePort.ChallengePurpose.SENSITIVE_ACTION, "OTP", "SMS", expiry, 5, 5);
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public StepUpChallengePort.ConsumedChallenge consumeVerified(StepUpChallengePort.ConsumeCommand command) {
            UUID id = UUID.fromString(command.challengeRef());
            var rows = jdbc.queryForList("SELECT * FROM e4_test.challenge WHERE id=? FOR UPDATE", id);
            if (rows.isEmpty()) throw new BankingBusinessException(ErrorCode.AUTH_CHALLENGE_NOT_FOUND);
            var row = rows.getFirst();
            if (!"SENSITIVE_ACTION".equals(row.get("purpose"))) throw new BankingBusinessException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
            if (!row.get("user_id").equals(command.userId())
                    || !java.util.Objects.equals(row.get("binding_key"), command.bindingKey())
                    || !row.get("request_hash").equals(command.requestHash())) throw new BankingBusinessException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
            if (((java.sql.Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now())) throw new BankingBusinessException(ErrorCode.AUTH_CHALLENGE_EXPIRED);
            if (!"VERIFIED".equals(row.get("status"))) throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
            jdbc.update("UPDATE e4_test.challenge SET status='CONSUMED' WHERE id=?", id);
            return new StepUpChallengePort.ConsumedChallenge(id.hashCode(), id.toString(),
                    StepUpChallengePort.ChallengePurpose.SENSITIVE_ACTION, Instant.now());
        }
        @Override public StepUpChallengePort.VerifiedChallenge verifyChallenge(StepUpChallengePort.VerifyCommand command) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<ActorSummary> findActor(long id) { return Optional.of(new ActorSummary(id, "Ops " + id)); }
    }
}
