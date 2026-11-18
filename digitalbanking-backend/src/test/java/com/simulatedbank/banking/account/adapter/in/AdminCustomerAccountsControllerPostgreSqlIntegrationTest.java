package com.simulatedbank.banking.account.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.account.application.AccountNumberProtection;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.testsupport.PostgreSqlIntegrationTestSupport;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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
 * BE-ACC-09 HTTP integration của `API-ADM-02` `GET /api/v1/admin/customers/{customerId}/accounts`
 * trên security chain thật (session filter, rule `ACCOUNT_READ`, correlation filter, Problem
 * writer, `Cache-Control: no-store`) và schema PostgreSQL 16 thật (Flyway `V1`–`V8`, AES-GCM/HMAC).
 *
 * <p>Không mock bỏ bước bảo mật: 401/403 do shared security layer sinh ra; staff thiếu
 * `ACCOUNT_READ` bị chặn trước handler; CUSTOMER chỉ có `ACCOUNT_READ_SELF` nhận 403; 400 do
 * adapter validate `customerId`/`status` trước persistence; customer không tồn tại nhận
 * `404 CUSTOMER_NOT_FOUND` từ provider exact-ID `customer.api` (ACC-DR-10 đã đóng, không đọc bảng
 * customer từ Account). Data scope staff theo chi nhánh/segment (ACC-DR-13/ADM-OI-06) vẫn BLOCKED —
 * gate hiện tại là permission `ACCOUNT_READ`, không coi là production-complete.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AdminCustomerAccountsControllerPostgreSqlIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Set<String> SUMMARY_FIELDS = Set.of(
            "accountId", "accountNumberMasked", "accountNumberLast4", "currency", "status",
            "availableBalance", "ledgerBalance", "asOf");

    @Container
    static final PostgreSQLContainer<?> postgres =
            PostgreSqlIntegrationTestSupport.newContainer("account_admin_http");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        PostgreSqlIntegrationTestSupport.registerSpringProperties(registry, postgres);
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

    private TransactionTemplate transaction;

    @BeforeEach
    void clearBankingData() {
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM banking.account");
            jdbc.update("DELETE FROM banking.customer");
        });
    }

    @Test
    void staffListReturnsOnlyTheTargetCustomerAccountsWithTheExactMaskedContract() throws Exception {
        Owner target = seedCustomer("admin-target");
        Owner other = seedCustomer("admin-other");
        long first = seedAccount(target.customerId(), "0011111111", "ACTIVE", "100.00");
        long second = seedAccount(target.customerId(), "0022222222", "LOCKED", "0.00");
        long foreign = seedAccount(other.customerId(), "0033333333", "ACTIVE", "999.00");
        long staff = seedUser("admin-staff", "STAFF", "OPERATIONS");
        String token = seedSession(staff);

        HttpResponse<String> response = send(
                "/api/v1/admin/customers/" + target.customerId() + "/accounts",
                token, "adm-list-200");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains("adm-list-200");
        JsonNode body = JSON.readTree(response.body());
        assertThat(fieldNames(body)).isEqualTo(Set.of("items", "asOf"));
        assertThat(body.get("items")).hasSize(2);
        Instant responseAsOf = Instant.parse(body.get("asOf").asText());

        JsonNode firstItem = body.get("items").get(0);
        assertThat(fieldNames(firstItem)).isEqualTo(SUMMARY_FIELDS);
        assertThat(firstItem.get("accountId").asText()).isEqualTo(Long.toString(first));
        assertThat(firstItem.get("accountNumberMasked").asText()).isEqualTo("**** **** 1111");
        assertThat(firstItem.get("accountNumberLast4").asText()).isEqualTo("1111");
        assertThat(firstItem.get("currency").asText()).isEqualTo("VND");
        assertThat(firstItem.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(firstItem.get("availableBalance").asText()).isEqualTo("100.00");
        assertThat(firstItem.get("ledgerBalance").asText()).isEqualTo("100.00");
        assertThat(Instant.parse(firstItem.get("asOf").asText())).isEqualTo(responseAsOf);

        JsonNode secondItem = body.get("items").get(1);
        assertThat(secondItem.get("accountId").asText()).isEqualTo(Long.toString(second));
        assertThat(secondItem.get("status").asText()).isEqualTo("LOCKED");

        // No plaintext account numbers, ciphertext, blind index or foreign account id leaks.
        assertThat(response.body())
                .doesNotContain("0011111111", "0022222222", "0033333333", "account_no_hash", "accountNoEnc");
        assertThat(body.at("/items/0/accountId").asLong()).isNotEqualTo(foreign);
    }

    @Test
    void targetCustomerIdIsBoundByThePathAndNeverFallsBackToTheActorIdentity() throws Exception {
        Owner first = seedCustomer("admin-bound-a");
        Owner second = seedCustomer("admin-bound-b");
        long firstAccount = seedAccount(first.customerId(), "0011111111", "ACTIVE", "10.00");
        long secondAccount = seedAccount(second.customerId(), "0022222222", "ACTIVE", "20.00");
        long staff = seedUser("admin-bound-staff", "STAFF", "OPERATIONS");
        String token = seedSession(staff);

        JsonNode firstBody = JSON.readTree(send(
                "/api/v1/admin/customers/" + first.customerId() + "/accounts", token, "adm-bound-a").body());
        JsonNode secondBody = JSON.readTree(send(
                "/api/v1/admin/customers/" + second.customerId() + "/accounts", token, "adm-bound-b").body());

        assertThat(firstBody.get("items")).hasSize(1);
        assertThat(firstBody.at("/items/0/accountId").asLong()).isEqualTo(firstAccount);
        assertThat(secondBody.get("items")).hasSize(1);
        assertThat(secondBody.at("/items/0/accountId").asLong()).isEqualTo(secondAccount);
    }

    @Test
    void staffListAppliesTheOptionalStatusFilter() throws Exception {
        Owner target = seedCustomer("admin-filter");
        seedAccount(target.customerId(), "0011111111", "ACTIVE", "10.00");
        long locked = seedAccount(target.customerId(), "0022222222", "LOCKED", "0.00");
        long staff = seedUser("admin-filter-staff", "STAFF", "OPERATIONS");
        String token = seedSession(staff);

        HttpResponse<String> response = send(
                "/api/v1/admin/customers/" + target.customerId() + "/accounts?status=LOCKED",
                token, "adm-filter");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("items")).hasSize(1);
        assertThat(body.at("/items/0/accountId").asLong()).isEqualTo(locked);
    }

    @Test
    void unknownStatusFilterIsValidationErrorBeforePersistence() throws Exception {
        Owner target = seedCustomer("admin-bad-filter");
        long staff = seedUser("admin-bad-filter-staff", "STAFF", "OPERATIONS");
        String token = seedSession(staff);

        for (String invalid : List.of("FROZEN", "active", "")) {
            assertProblem(send("/api/v1/admin/customers/" + target.customerId()
                    + "/accounts?status=" + invalid, token, "adm-bad-filter"),
                    400, "VALIDATION_ERROR", "adm-bad-filter");
        }
    }

    @Test
    void invalidCustomerIdIsValidationErrorBeforePersistence() throws Exception {
        long staff = seedUser("admin-bad-id-staff", "STAFF", "OPERATIONS");
        String token = seedSession(staff);

        for (String invalid : List.of("0", "-1", "abc", "1.5", "01", "9223372036854775808")) {
            assertProblem(send("/api/v1/admin/customers/" + invalid + "/accounts",
                    token, "adm-bad-id"), 400, "VALIDATION_ERROR", "adm-bad-id");
        }
    }

    @Test
    void unknownCustomerIsNotFoundInsteadOfAnEmptyPage() throws Exception {
        long staff = seedUser("admin-unknown-staff", "STAFF", "OPERATIONS");
        String token = seedSession(staff);

        HttpResponse<String> response = send("/api/v1/admin/customers/999999/accounts",
                token, "adm-unknown");

        // 07 §8.2.5: the contract status is 404 CUSTOMER_NOT_FOUND, not an empty account page.
        assertProblem(response, 404, "CUSTOMER_NOT_FOUND", "adm-unknown");
        assertThat(response.body()).doesNotContain("items", "asOf", "accountNumber");
    }

    @Test
    void knownCustomerWithoutAccountsYieldsAnEmptyPage() throws Exception {
        Owner target = seedCustomer("admin-empty");
        long staff = seedUser("admin-empty-staff", "STAFF", "OPERATIONS");
        String token = seedSession(staff);

        HttpResponse<String> response = send("/api/v1/admin/customers/" + target.customerId() + "/accounts",
                token, "adm-empty");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("items")).isEmpty();
        assertThat(body.get("asOf").isTextual()).isTrue();
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void anonymousCallerIsRejected() throws Exception {
        Owner target = seedCustomer("admin-anon");

        assertProblem(send("/api/v1/admin/customers/" + target.customerId() + "/accounts",
                null, "adm-anon"), 401, "AUTH_SESSION_EXPIRED", "adm-anon");
    }

    @Test
    void customerWithOnlySelfReadIsForbidden() throws Exception {
        Owner target = seedCustomer("admin-customer");
        Owner caller = seedCustomer("admin-customer-caller");
        seedAccount(target.customerId(), "0011111111", "ACTIVE", "10.00");
        String token = seedSession(caller.userId());

        assertProblem(send("/api/v1/admin/customers/" + target.customerId() + "/accounts",
                token, "adm-customer-403"), 403, "AUTH_FORBIDDEN", "adm-customer-403");
    }

    @Test
    void staffWithoutAccountReadIsForbidden() throws Exception {
        Owner target = seedCustomer("admin-no-perm");
        seedAccount(target.customerId(), "0011111111", "ACTIVE", "10.00");
        long staff = seedUser("admin-no-perm-staff", "STAFF");
        String token = seedSession(staff);

        assertProblem(send("/api/v1/admin/customers/" + target.customerId() + "/accounts",
                token, "adm-no-perm-403"), 403, "AUTH_FORBIDDEN", "adm-no-perm-403");
    }

    @Test
    void auditorMayReadUnderTheApprovedAccountReadPermission() throws Exception {
        Owner target = seedCustomer("admin-auditor");
        long account = seedAccount(target.customerId(), "0011111111", "ACTIVE", "10.00");
        long auditor = seedUser("admin-auditor-user", "STAFF", "AUDITOR");
        String token = seedSession(auditor);

        HttpResponse<String> response = send(
                "/api/v1/admin/customers/" + target.customerId() + "/accounts",
                token, "adm-auditor-200");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).at("/items/0/accountId").asLong()).isEqualTo(account);
    }

    @Test
    void theEndpointIsReadOnly() throws Exception {
        Owner target = seedCustomer("admin-readonly");
        long account = seedAccount(target.customerId(), "0011111111", "ACTIVE", "10.00");
        long staff = seedUser("admin-readonly-staff", "STAFF", "OPERATIONS");
        String token = seedSession(staff);
        BigDecimal balanceBefore = jdbc.queryForObject(
                "SELECT available_balance FROM banking.account WHERE account_id = ?",
                BigDecimal.class, account);

        assertThat(send("/api/v1/admin/customers/" + target.customerId() + "/accounts",
                token, "adm-readonly").statusCode()).isEqualTo(200);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.account", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT available_balance FROM banking.account WHERE account_id = ?",
                BigDecimal.class, account)).isEqualByComparingTo(balanceBefore);
    }

    // ------------------------------------------------------------------ helpers

    private record Owner(long userId, long customerId) {
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
        String token = "adm-" + UUID.randomUUID();
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
