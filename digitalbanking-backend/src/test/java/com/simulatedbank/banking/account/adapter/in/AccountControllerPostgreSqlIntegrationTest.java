package com.simulatedbank.banking.account.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.account.application.AccountNumberProtection;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-ACC-05 HTTP integration của `API-ACC-01`/`API-ACC-02` trên security chain thật (session
 * filter, rule `ACCOUNT_READ_SELF`/`ACCOUNT_READ`, correlation filter, Problem writer,
 * `Cache-Control: no-store`) và schema PostgreSQL 16 thật (Flyway `V1`–`V8`, AES-GCM/HMAC thật).
 *
 * <p>Không mock bỏ bước bảo mật: 401/403 do shared security layer sinh ra; 403 owner mismatch
 * kèm audit C3 best-effort (structured log + metric `account.audit.events`); 404 không audit;
 * 400 do adapter validate trước persistence. GET phải là read thuần.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AccountControllerPostgreSqlIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Set<String> SUMMARY_FIELDS = Set.of(
            "accountId", "accountNumberMasked", "accountNumberLast4", "currency", "status",
            "availableBalance", "ledgerBalance", "asOf");
    private static final Set<String> DETAIL_FIELDS = Set.of(
            "accountId", "accountNumberMasked", "accountNumberLast4", "currency", "status",
            "availableBalance", "ledgerBalance", "asOf", "owner", "createdAt", "updatedAt");

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("account_http");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
        // Functional security scenarios, not throughput: keep shared rate limiting out of the way.
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "10000");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountNumberProtection protection;

    @Autowired
    private MeterRegistry meterRegistry;

    private TransactionTemplate transaction;

    @BeforeEach
    void clearBankingData() {
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM banking.account");
            jdbc.update("DELETE FROM banking.customer");
        });
    }

    // ------------------------------------------------------------------ API-ACC-01

    @Test
    void ownerListReturnsOnlyOwnAccountsWithExactSummaryContractAndMaskedNumbers() throws Exception {
        Owner owner = seedCustomer("owner-list");
        Owner stranger = seedCustomer("stranger-list");
        long first = seedAccount(owner.customerId(), "0011111111", "ACTIVE", "100.00");
        long second = seedAccount(owner.customerId(), "0022222222", "LOCKED", "0.00");
        long foreign = seedAccount(stranger.customerId(), "0033333333", "ACTIVE", "999.00");
        String token = seedSession(owner.userId());
        String plaintextOfForeign = "0033333333";

        HttpResponse<String> response = send("/api/v1/accounts", token, "acc-list-200");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains("acc-list-200");
        JsonNode body = JSON.readTree(response.body());
        assertThat(fieldNames(body)).isEqualTo(Set.of("items", "asOf"));
        assertThat(body.get("items").isArray()).isTrue();
        assertThat(body.get("items")).hasSize(2);
        Instant responseAsOf = Instant.parse(body.get("asOf").asText());

        JsonNode firstItem = body.get("items").get(0);
        assertThat(fieldNames(firstItem)).isEqualTo(SUMMARY_FIELDS);
        assertThat(firstItem.get("accountId").asText()).isEqualTo(Long.toString(first));
        assertThat(firstItem.get("accountNumberMasked").asText()).matches("^\\*{4} \\*{4} [0-9]{4}$");
        assertThat(firstItem.get("accountNumberMasked").asText()).isEqualTo("**** **** 1111");
        assertThat(firstItem.get("accountNumberLast4").asText()).isEqualTo("1111");
        assertThat(firstItem.get("currency").asText()).isEqualTo("VND");
        assertThat(firstItem.get("status").asText()).isEqualTo("ACTIVE");
        // OpenAPI Money is a decimal string with exactly two fraction digits (not a JSON number).
        assertThat(firstItem.get("availableBalance").isTextual()).isTrue();
        assertThat(firstItem.get("availableBalance").asText()).isEqualTo("100.00");
        assertThat(firstItem.get("ledgerBalance").asText()).isEqualTo("100.00");
        assertThat(Instant.parse(firstItem.get("asOf").asText())).isEqualTo(responseAsOf);
        assertThat(Instant.parse(firstItem.get("asOf").asText()))
                .isBeforeOrEqualTo(Instant.now().plusSeconds(5));

        JsonNode secondItem = body.get("items").get(1);
        assertThat(secondItem.get("accountId").asText()).isEqualTo(Long.toString(second));
        assertThat(secondItem.get("status").asText()).isEqualTo("LOCKED");
        assertThat(body.toString()).doesNotContain(plaintextOfForeign, "0033333333");

        // The foreign account is never derivable from any JSON id in the response.
        List<Long> returnedIds = List.of(
                body.get("items").get(0).get("accountId").asLong(),
                body.get("items").get(1).get("accountId").asLong());
        assertThat(returnedIds).doesNotContain(foreign);
        assertThat(response.body()).doesNotContain("accountNoHash", "account_no_hash", "accountNoEnc");
    }

    @Test
    void ownerListAppliesTheOptionalStatusFilter() throws Exception {
        Owner owner = seedCustomer("owner-filter");
        seedAccount(owner.customerId(), "0011111111", "ACTIVE", "10.00");
        long locked = seedAccount(owner.customerId(), "0022222222", "LOCKED", "0.00");
        String token = seedSession(owner.userId());

        HttpResponse<String> lockedResponse = send("/api/v1/accounts?status=LOCKED", token, "acc-filter-1");
        HttpResponse<String> closedResponse = send("/api/v1/accounts?status=CLOSED", token, "acc-filter-2");

        assertThat(lockedResponse.statusCode()).isEqualTo(200);
        JsonNode lockedBody = JSON.readTree(lockedResponse.body());
        assertThat(lockedBody.get("items")).hasSize(1);
        assertThat(lockedBody.get("items").get(0).get("accountId").asText()).isEqualTo(Long.toString(locked));
        assertThat(closedResponse.statusCode()).isEqualTo(200);
        JsonNode closedBody = JSON.readTree(closedResponse.body());
        assertThat(closedBody.get("items")).isEmpty();
        assertThat(closedBody.get("asOf").isTextual()).isTrue();
    }

    @Test
    void ownerListRejectsAnUnknownStatusFilterBeforePersistence() throws Exception {
        Owner owner = seedCustomer("owner-bad-filter");
        String token = seedSession(owner.userId());

        for (String invalid : List.of("FROZEN", "active", "")) {
            HttpResponse<String> response = send("/api/v1/accounts?status=" + invalid, token, "acc-bad-filter");
            assertProblem(response, 400, "VALIDATION_ERROR", "acc-bad-filter");
        }
    }

    @Test
    void ownerWithoutAccountsReceivesAnEmptyListWithSnapshotTime() throws Exception {
        Owner owner = seedCustomer("owner-empty");
        String token = seedSession(owner.userId());

        HttpResponse<String> response = send("/api/v1/accounts", token, "acc-empty");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("items")).isEmpty();
        assertThat(Instant.parse(body.get("asOf").asText())).isNotNull();
    }

    // ------------------------------------------------------------------ API-ACC-02

    @Test
    void ownerDetailReturnsTheExactMaskedDetailContract() throws Exception {
        Owner owner = seedCustomer("owner-detail");
        long account = seedAccount(owner.customerId(), "0012345678", "ACTIVE", "12500000.00");
        String token = seedSession(owner.userId());
        Row before = row(account);

        HttpResponse<String> response = send("/api/v1/accounts/" + account, token, "acc-detail-200");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains("acc-detail-200");
        JsonNode body = JSON.readTree(response.body());
        assertThat(fieldNames(body)).isEqualTo(DETAIL_FIELDS);
        assertThat(body.get("accountId").asText()).isEqualTo(Long.toString(account));
        assertThat(body.get("accountNumberMasked").asText()).isEqualTo("**** **** 5678");
        assertThat(body.get("accountNumberLast4").asText()).isEqualTo("5678");
        assertThat(body.get("currency").asText()).isEqualTo("VND");
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("availableBalance").asText()).isEqualTo("12500000.00");
        assertThat(body.get("ledgerBalance").asText()).isEqualTo("12500000.00");
        assertThat(Instant.parse(body.get("asOf").asText())).isNotNull();
        assertThat(Instant.parse(body.get("createdAt").asText())).isEqualTo(before.createdAt());
        assertThat(Instant.parse(body.get("updatedAt").asText())).isEqualTo(before.updatedAt());
        JsonNode ownerNode = body.get("owner");
        // ACC-DR-10 BLOCKED: no Customer PII is available to Account; existence of the field
        // is asserted so a future approved provider can add `fullName` without breaking shape.
        assertThat(fieldNames(ownerNode)).isEqualTo(Set.of("customerId", "status"));
        assertThat(ownerNode.get("customerId").asText()).isEqualTo(Long.toString(owner.customerId()));
        assertThat(ownerNode.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(response.body()).doesNotContain("0012345678", "account_no_hash", "accountNoEnc",
                "fullName", "full_name");
        // GET is side-effect free: the row is byte-for-byte unchanged afterwards.
        assertThat(row(account)).isEqualTo(before);
    }

    @Test
    void ownerDetailIsReadableForLockedAndClosedAccountsWithoutChangingState() throws Exception {
        Owner owner = seedCustomer("owner-nonactive");
        long locked = seedAccount(owner.customerId(), "0011111111", "LOCKED", "0.00");
        long closed = seedClosedAccount(owner.customerId(), "0022222222");
        String token = seedSession(owner.userId());

        HttpResponse<String> lockedResponse = send("/api/v1/accounts/" + locked, token, "acc-locked");
        HttpResponse<String> closedResponse = send("/api/v1/accounts/" + closed, token, "acc-closed");

        assertThat(lockedResponse.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(lockedResponse.body()).get("status").asText()).isEqualTo("LOCKED");
        assertThat(closedResponse.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(closedResponse.body()).get("status").asText()).isEqualTo("CLOSED");
    }

    @Test
    void crossCustomerDetailIsForbiddenAuditedAndLeaksNoAccountData() throws Exception {
        Owner owner = seedCustomer("owner-cross");
        Owner attacker = seedCustomer("attacker-cross");
        long victimAccount = seedAccount(owner.customerId(), "0012345678", "ACTIVE", "100.00");
        String token = seedSession(attacker.userId());
        double deniedBefore = auditDeniedCount();

        HttpResponse<String> response = send("/api/v1/accounts/" + victimAccount, token, "acc-cross-403");

        assertProblem(response, 403, "AUTH_FORBIDDEN", "acc-cross-403");
        assertThat(response.body()).doesNotContain("5678", "0012345678", "12500000");
        assertThat(JSON.readTree(response.body()).has("availableBalance")).isFalse();
        // C3 SECURITY_ACCESS_DENIED is emitted (best-effort) for the owner mismatch.
        assertThat(auditDeniedCount()).isEqualTo(deniedBefore + 1);
    }

    @Test
    void unknownAccountIsNotFoundAndIsNotAuditedAsASecurityDenial() throws Exception {
        Owner owner = seedCustomer("owner-404");
        String token = seedSession(owner.userId());
        double deniedBefore = auditDeniedCount();

        HttpResponse<String> response = send("/api/v1/accounts/999999", token, "acc-404");

        assertProblem(response, 404, "ACCOUNT_NOT_FOUND", "acc-404");
        assertThat(auditDeniedCount()).isEqualTo(deniedBefore);
    }

    @Test
    void invalidAccountIdIsRejectedWithValidationErrorBeforePersistence() throws Exception {
        Owner owner = seedCustomer("owner-invalid-id");
        String token = seedSession(owner.userId());

        for (String invalid : List.of("0", "-1", "abc", "1.5", "01", "9223372036854775808")) {
            HttpResponse<String> response = send("/api/v1/accounts/" + invalid, token, "acc-bad-id");
            assertProblem(response, 400, "VALIDATION_ERROR", "acc-bad-id");
        }
    }

    @Test
    void authenticatedIdentityWithoutCustomerMappingIsCustomerNotFound() throws Exception {
        long userId = seedUser("owner-no-customer", "CUSTOMER", "CUSTOMER");
        String token = seedSession(userId);

        HttpResponse<String> response = send("/api/v1/accounts", token, "acc-no-customer");

        assertProblem(response, 404, "CUSTOMER_NOT_FOUND", "acc-no-customer");
    }

    @Test
    void staffWithAccountReadMayReadAnyAccountDetailUnderDocumentedScopeGap() throws Exception {
        Owner owner = seedCustomer("owner-staff-read");
        long account = seedAccount(owner.customerId(), "0012345678", "ACTIVE", "1.00");
        long staff = seedUser("staff-reader", "STAFF", "OPERATIONS");
        String token = seedSession(staff);

        HttpResponse<String> response = send("/api/v1/accounts/" + account, token, "acc-staff-200");

        // ACC-DR-13 (staff data scope) is BLOCKED; the approved gate is the ACCOUNT_READ permission.
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("accountId").asText()).isEqualTo(Long.toString(account));
        assertThat(body.get("owner").get("customerId").asText()).isEqualTo(Long.toString(owner.customerId()));
    }

    // ------------------------------------------------------------------ security

    @Test
    void missingAuthenticationFailsClosedOnBothEndpoints() throws Exception {
        Owner owner = seedCustomer("owner-anon");
        long account = seedAccount(owner.customerId(), "0011111111", "ACTIVE", "0.00");

        assertProblem(send("/api/v1/accounts", null, "acc-anon-list"),
                401, "AUTH_SESSION_EXPIRED", "acc-anon-list");
        assertProblem(send("/api/v1/accounts/" + account, null, "acc-anon-detail"),
                401, "AUTH_SESSION_EXPIRED", "acc-anon-detail");
    }

    @Test
    void invalidAndExpiredSessionsFailClosedWithoutDisclosure() throws Exception {
        Owner owner = seedCustomer("owner-session");
        seedAccount(owner.customerId(), "0011111111", "ACTIVE", "0.00");

        HttpResponse<String> invalid = send("/api/v1/accounts?customerId=1&userId=1",
                "invalid-" + UUID.randomUUID(), "acc-invalid-token");
        assertProblem(invalid, 401, "AUTH_SESSION_EXPIRED", "acc-invalid-token");

        long userId = seedUser("owner-expired", "CUSTOMER", "CUSTOMER");
        seedCustomerRow(userId, "ACTIVE");
        String expiredToken = "expired-" + UUID.randomUUID();
        transaction.executeWithoutResult(ignored -> jdbc.update(
                "INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id, "
                        + "last_activity_at, idle_expires_at, absolute_expires_at) "
                        + "VALUES (?, ?, ?, now() - interval '5 minutes', now() - interval '1 minute', "
                        + "now() + interval '1 hour')",
                HmacService.sha256Hex(expiredToken), "b".repeat(64), userId));
        assertProblem(send("/api/v1/accounts", expiredToken, "acc-expired"), 401,
                "AUTH_SESSION_EXPIRED", "acc-expired");
    }

    @Test
    void staffWithoutSelfReadAuthorityCannotListOwnAccounts() throws Exception {
        long staff = seedUser("staff-list", "STAFF", "OPERATIONS");
        String token = seedSession(staff);

        assertProblem(send("/api/v1/accounts", token, "acc-staff-list"), 403, "AUTH_FORBIDDEN",
                "acc-staff-list");
    }

    @Test
    void staffWithoutAccountReadAuthorityCannotReadAnyDetail() throws Exception {
        Owner owner = seedCustomer("owner-no-perm");
        long account = seedAccount(owner.customerId(), "0011111111", "ACTIVE", "0.00");
        long staffWithoutRole = seedUser("staff-no-role", "STAFF");
        String token = seedSession(staffWithoutRole);

        // Authenticated but lacking ACCOUNT_READ/ACCOUNT_READ_SELF: denied by the shared chain.
        assertProblem(send("/api/v1/accounts/" + account, token, "acc-staff-no-perm"), 403,
                "AUTH_FORBIDDEN", "acc-staff-no-perm");
    }

    @Test
    void customerWithSelfReadButNoStaffPermissionStillCannotReadForeignAccounts() throws Exception {
        Owner owner = seedCustomer("owner-perm");
        Owner other = seedCustomer("other-perm");
        long foreignClose = seedClosedAccount(other.customerId(), "0022222222");
        String token = seedSession(owner.userId());

        assertProblem(send("/api/v1/accounts/" + foreignClose, token, "acc-foreign-403"),
                403, "AUTH_FORBIDDEN", "acc-foreign-403");
    }

    @Test
    void writeVerbsCannotModifyAccountDataBecauseAccountExposesReadOnlyRoutes() throws Exception {
        Owner owner = seedCustomer("owner-methods");
        long account = seedAccount(owner.customerId(), "0012345678", "ACTIVE", "100.00");
        long staff = seedUser("staff-methods", "STAFF", "OPERATIONS");
        String ownerToken = seedSession(owner.userId());
        String staffToken = seedSession(staff);
        Row before = row(account);

        List<String> paths = List.of(
                "/api/v1/accounts",
                "/api/v1/accounts/" + account,
                "/api/v1/admin/customers/" + owner.customerId() + "/accounts");
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            for (String path : paths) {
                String token = path.startsWith("/api/v1/admin/") ? staffToken : ownerToken;
                HttpResponse<String> response = sendMethod(method, path, token, "acc-method");
                // Authenticated caller reaches MVC, which has no write mapping for these paths.
                assertThat(response.statusCode())
                        .as("%s %s", method, path)
                        .isEqualTo(405);
                assertThat(response.body()).doesNotContain("0012345678", "account_no_hash",
                        "account_no_enc", "availableBalance");
            }
        }

        // No write path exists: the persisted row and the account set are unchanged.
        assertThat(row(account)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM banking.account", Long.class)).isEqualTo(1);
    }

    @Test
    void anonymousWriteVerbsAreRejectedBeforeReachingAnyHandler() throws Exception {
        Owner owner = seedCustomer("owner-anon-method");
        long account = seedAccount(owner.customerId(), "0011111111", "ACTIVE", "100.00");
        Row before = row(account);

        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            HttpResponse<String> response =
                    sendMethod(method, "/api/v1/accounts/" + account, null, "acc-anon-method");
            assertThat(response.statusCode()).as(method).isEqualTo(401);
            assertThat(response.body()).doesNotContain("0011111111");
        }
        assertThat(row(account)).isEqualTo(before);
    }

    @Test
    void malformedCredentialIsRejectedWithoutLeakingRequestData() throws Exception {
        String credential = "Basic c3ludGhldGljOnNlY3JldA==";
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(base() + "/api/v1/accounts?accountId=1&userId=1"))
                        .header("Authorization", credential)
                        .header("X-Correlation-ID", "acc-malformed")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertProblem(response, 401, "AUTH_SESSION_EXPIRED", "acc-malformed");
        assertThat(response.body()).doesNotContain(credential, "c3ludGhldGljOnNlY3JldA==");
    }

    // ------------------------------------------------------------------ helpers

    private record Owner(long userId, long customerId) {
    }

    private record Row(Instant createdAt, Instant updatedAt, BigDecimal availableBalance,
            BigDecimal ledgerBalance, String status) {
    }

    private HttpResponse<String> send(String path, String token, String correlationId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (correlationId != null) {
            builder.header("X-Correlation-ID", correlationId);
        }
        return HTTP.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendMethod(String method, String path, String token, String correlationId)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (correlationId != null) {
            builder.header("X-Correlation-ID", correlationId);
        }
        return HTTP.send(
                builder.method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private Owner seedCustomer(String prefix) {
        long userId = seedUser(prefix, "CUSTOMER", "CUSTOMER");
        long customerId = seedCustomerRow(userId, "ACTIVE");
        return new Owner(userId, customerId);
    }

    private long seedCustomerRow(long userId, String status) {
        return transaction.execute(ignored -> jdbc.queryForObject("""
                INSERT INTO banking.customer (user_id, full_name_enc, status)
                VALUES (?, ?, ?)
                RETURNING customer_id
                """, Long.class, userId, new byte[] {1, 2, 3}, status));
    }

    private long seedUser(String prefix, String userType, String... roleCodes) {
        return transaction.execute(ignored -> {
            String username = prefix + "-" + UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO banking.app_user (username, password_hash, user_type, status)
                    VALUES (?, 'synthetic-not-a-real-hash', ?, 'ACTIVE')
                    """, username, userType);
            long userId = jdbc.queryForObject(
                    "SELECT user_id FROM banking.app_user WHERE username = ?", Long.class, username);
            for (String roleCode : roleCodes) {
                jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                        + "SELECT ?, role_id FROM banking.role WHERE role_code = ?", userId, roleCode);
            }
            return userId;
        });
    }

    private String seedSession(long userId) {
        String token = "acc-" + UUID.randomUUID();
        transaction.executeWithoutResult(ignored -> jdbc.update("""
                INSERT INTO banking.session (session_token_hash, csrf_token_hash, user_id,
                    idle_expires_at, absolute_expires_at)
                VALUES (?, ?, ?, now() + interval '20 minutes', now() + interval '1 hour')
                """, HmacService.sha256Hex(token), "a".repeat(64), userId));
        return token;
    }

    private long seedAccount(long customerId, String accountNumber, String status, String available) {
        AccountNumberProtection.ProtectedAccountNumber protectedAccount = protection.protect(accountNumber);
        return transaction.execute(ignored -> jdbc.queryForObject("""
                INSERT INTO banking.account
                    (customer_id, account_no_enc, account_no_hash, account_no_last4,
                     status, available_balance, ledger_balance)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING account_id
                """,
                Long.class,
                customerId,
                protectedAccount.encrypted(),
                protectedAccount.blindIndex(),
                protectedAccount.accountNumberLast4(),
                status,
                new BigDecimal(available),
                new BigDecimal(available)));
    }

    private long seedClosedAccount(long customerId, String accountNumber) {
        AccountNumberProtection.ProtectedAccountNumber protectedAccount = protection.protect(accountNumber);
        return transaction.execute(ignored -> jdbc.queryForObject("""
                INSERT INTO banking.account
                    (customer_id, account_no_enc, account_no_hash, account_no_last4,
                     status, available_balance, ledger_balance, closed_at)
                VALUES (?, ?, ?, ?, 'CLOSED', 0, 0, ?)
                RETURNING account_id
                """,
                Long.class,
                customerId,
                protectedAccount.encrypted(),
                protectedAccount.blindIndex(),
                protectedAccount.accountNumberLast4(),
                OffsetDateTime.ofInstant(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)));
    }

    private Row row(long accountId) {
        return jdbc.queryForObject("""
                SELECT created_at, updated_at, available_balance, ledger_balance, status
                FROM banking.account WHERE account_id = ?
                """,
                (rs, index) -> new Row(
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getBigDecimal("available_balance"),
                        rs.getBigDecimal("ledger_balance"),
                        rs.getString("status")),
                accountId);
    }

    private double auditDeniedCount() {
        return meterRegistry.find("account.audit.events")
                .tag("action", "SECURITY_ACCESS_DENIED")
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private static Set<String> fieldNames(JsonNode node) {
        TreeSet<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code,
            String correlationId) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("type").asText()).startsWith("https://");
        assertThat(body.get("title").asText()).isNotBlank();
        assertThat(body.get("detail").asText())
                .isNotBlank()
                .doesNotContain("Exception", "org.springframework", "PSQLException", "banking.");
        assertThat(body.get("instance").asText()).startsWith("/api/");
        assertThat(body.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains(correlationId);
    }
}
