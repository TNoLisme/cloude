package com.simulatedbank.banking.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.simulatedbank.banking.common.crypto.HmacService;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.*;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({HttpSecurityTest.Harness.class, HttpSecurityTest.Fixture.class})
class HttpSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;

    @BeforeEach
    void nextWindow() { clock.advance(Duration.ofHours(2)); }

    @Test
    void anonymousExpiredAndDeniedUseProblemAndTraceWithNoRedirect() throws Exception {
        problem(mvc.perform(get("/api/v1/accounts")).andReturn(), 401, "AUTH_SESSION_EXPIRED");
        problem(mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer expired")).andReturn(),
                401, "AUTH_SESSION_EXPIRED");
        problem(mvc.perform(get("/api/v1/accounts").cookie(new Cookie("DB_SESSION", "expired"))).andReturn(),
                401, "AUTH_SESSION_EXPIRED");
        problem(mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer auditor")).andReturn(),
                403, "AUTH_FORBIDDEN");
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer customer-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.synthetic").value(true));
        problem(mvc.perform(get("/api/v1/fixture/method").header("Authorization", "Bearer customer-a")).andReturn(),
                403, "AUTH_FORBIDDEN");
        mvc.perform(get("/api/v1/fixture/method").header("Authorization", "Bearer method-owner"))
                .andExpect(status().isOk());
    }

    @Test
    void cookieMutationsRequireMatchingSessionCsrfWhileAuthenticatedBearerDoesNot() throws Exception {
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            var builder = request(org.springframework.http.HttpMethod.valueOf(method), "/api/v1/fixture/body")
                    .cookie(new Cookie("DB_SESSION", "customer-a")).content("synthetic");
            problem(mvc.perform(builder).andReturn(), 403, "AUTH_FORBIDDEN");
        }
        problem(mvc.perform(post("/api/v1/fixture/body").cookie(new Cookie("DB_SESSION", "customer-a"))
                .header("X-CSRF-Token", "csrf-b")).andReturn(), 403, "AUTH_FORBIDDEN");
        problem(mvc.perform(post("/api/v1/fixture/body").cookie(new Cookie("DB_SESSION", "customer-a"))
                .header("X-CSRF-Token", "csrf-a", "csrf-a")).andReturn(), 403, "AUTH_FORBIDDEN");
        mvc.perform(post("/api/v1/fixture/body").cookie(new Cookie("DB_SESSION", "customer-a"))
                .header("X-CSRF-Token", "csrf-a").content("hello"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bytes").value(5));
        mvc.perform(post("/api/v1/fixture/body").header("Authorization", "Bearer customer-a").content("hello"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").content("synthetic"))
                .andExpect(status().isOk()); // Bootstrap fixture, not an implementation of login.
        problem(mvc.perform(post("/api/v1/fixture/body").header("Authorization", "Bearer expired"))
                .andReturn(), 401, "AUTH_SESSION_EXPIRED");
        problem(mvc.perform(post("/api/v1/fixture/body").cookie(new Cookie("DB_SESSION", "customer-a"))
                .header("Authorization", "Bearer expired").header("X-CSRF-Token", "csrf-a")).andReturn(),
                401, "AUTH_SESSION_EXPIRED");
        problem(mvc.perform(post("/api/v1/fixture/body").cookie(new Cookie("DB_SESSION", "customer-a"))
                .header("Authorization", "Bearer customer-a")).andReturn(), 401, "AUTH_SESSION_EXPIRED");
    }

    @Test
    void expiredAmbientCookieDoesNotPreventPublicLoginButActiveCookieStillNeedsCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/login").cookie(new Cookie("DB_SESSION", "expired")))
                .andExpect(status().isOk());
        problem(mvc.perform(post("/api/v1/auth/login").cookie(new Cookie("DB_SESSION", "customer-a")))
                .andReturn(), 403, "AUTH_FORBIDDEN");
        mvc.perform(post("/api/v1/auth/login").cookie(new Cookie("DB_SESSION", "customer-a"))
                .header("X-CSRF-Token", "csrf-a")).andExpect(status().isOk());
        problem(mvc.perform(post("/api/v1/auth/login").header("Authorization", "Bearer expired"))
                .andReturn(), 401, "AUTH_SESSION_EXPIRED");
    }

    @Test
    void corsAllowsOnlyConfiguredOriginMethodsHeadersAndExposesContractHeaders() throws Exception {
        mvc.perform(options("/api/v1/transfers").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization,X-CSRF-Token,Idempotency-Key,X-Step-Up-Challenge"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        for (String[] values : new String[][] {
                {"https://untrusted.example", "POST", "Authorization"},
                {"http://localhost:5173", "PATCH", "Authorization"},
                {"http://localhost:5173", "POST", "X-Unapproved"}}) {
            var result = mvc.perform(options("/api/v1/transfers").header("Origin", values[0])
                    .header("Access-Control-Request-Method", values[1])
                    .header("Access-Control-Request-Headers", values[2])).andReturn();
            problem(result, 403, "AUTH_FORBIDDEN");
            assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        }
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer customer-a")
                .header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Expose-Headers",
                        org.hamcrest.Matchers.containsString("Content-Disposition")));
        problem(mvc.perform(get("/api/v1/accounts").header("Origin", "https://untrusted.example"))
                .andReturn(), 403, "AUTH_FORBIDDEN");
    }

    @Test
    void headersAlsoCoverPreSecurityErrorsAndHstsRequiresActualSecureTransport() throws Exception {
        var malformed = mvc.perform(get("/api/v1/accounts").header("X-Correlation-ID", "invalid id")).andReturn();
        problem(malformed, 400, "VALIDATION_ERROR");
        assertHeaders(malformed);
        assertHeaders(mvc.perform(get("/api/v1/accounts")).andReturn());
        assertHeaders(mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer customer-a")).andReturn());
        mvc.perform(get("/actuator/health").secure(true))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000; includeSubDomains"));
        mvc.perform(get("/actuator/health").header("X-Forwarded-Proto", "https"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void bodyBoundaryUsesBytesAndDoesNotLeakRejectedBody() throws Exception {
        mvc.perform(post("/api/v1/fixture/body").header("Authorization", "Bearer customer-a")
                .content(new byte[65536])).andExpect(status().isOk()).andExpect(jsonPath("$.bytes").value(65536));
        var excessive = mvc.perform(post("/api/v1/fixture/body").header("Authorization", "Bearer customer-a")
                .content("é".repeat(32769))).andReturn();
        problem(excessive, 413, "REQUEST_TOO_LARGE");
        assertHeaders(excessive);
        assertThat(excessive.getResponse().getContentAsString()).doesNotContain("é");
    }

    @Test
    void transferQuotaIsSharedAcrossSessionsAndUsersOfOneCustomerWithRealRetryAfter() throws Exception {
        for (int i = 0; i < 30; i++) {
            String token = i % 2 == 0 ? "customer-a" : "customer-b";
            mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        clock.advance(Duration.ofMillis(1100));
        var limited = mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-b")).andReturn();
        problem(limited, 429, "RATE_LIMITED");
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("1");
        mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer other-customer"))
                .andExpect(status().isOk());
        clock.advance(Duration.ofMillis(900));
        mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a"))
                .andExpect(status().isOk());
    }

    @Test
    void loginIpGlobalAndAuditExportQuotasAreActuallyEnforced() throws Exception {
        for (int i = 0; i < 10; i++) mvc.perform(post("/api/v1/auth/login")).andExpect(status().isOk());
        var login = mvc.perform(post("/api/v1/auth/login")).andReturn();
        problem(login, 429, "RATE_LIMITED");
        assertThat(login.getResponse().getHeader("Retry-After")).isEqualTo("6");
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/v1/audit/export").header("Authorization", "Bearer auditor")).andExpect(status().isOk());
        }
        var export = mvc.perform(get("/api/v1/audit/export").header("Authorization", "Bearer auditor")).andReturn();
        problem(export, 429, "RATE_LIMITED");
        assertThat(export.getResponse().getHeader("Retry-After")).isEqualTo("720");
        for (int i = 0; i < 120; i++) {
            mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer other-customer")).andExpect(status().isOk());
        }
        problem(mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer other-customer")).andReturn(),
                429, "RATE_LIMITED");
    }

    @Test
    void stepUpAndOtpUserQuotasDoNotBecomePerSessionQuotas() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/auth/step-up/verify").header("Authorization", "Bearer customer-a"))
                    .andExpect(status().isOk());
        }
        problem(mvc.perform(post("/api/v1/auth/step-up/verify")
                .header("Authorization", "Bearer customer-a2")).andReturn(), 429, "RATE_LIMITED");
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/api/v1/auth/otp/verify").header("Authorization", "Bearer customer-a"))
                    .andExpect(status().isOk());
        }
        problem(mvc.perform(post("/api/v1/auth/otp/verify")
                .header("Authorization", "Bearer customer-a2")).andReturn(), 429, "RATE_LIMITED");
    }

    @Test
    void permissionAndCheckerGatesApplyBeforeOwnersBusinessChecks() throws Exception {
        problem(mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer auditor")).andReturn(),
                403, "AUTH_FORBIDDEN");
        problem(mvc.perform(post("/api/v1/admin/limits/1/approve").header("Authorization", "Bearer maker")).andReturn(),
                403, "AUTH_FORBIDDEN");
        mvc.perform(post("/api/v1/admin/limits/1/approve").header("Authorization", "Bearer checker"))
                .andExpect(status().isOk());
    }

    static void problem(MvcResult result, int status, String code) {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(result.getResponse().getHeader("Location")).isNull();
        assertThat(result.getResponse().getHeader("X-Correlation-ID")).isNotBlank();
        assertThat(new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"code\":\"" + code + "\"", result.getResponse().getHeader("X-Correlation-ID"))
                .doesNotContain("<html", "csrf-a", "customer-a", "JSESSIONID");
        if (status == 401) assertThat(result.getResponse().getHeader("WWW-Authenticate"))
                .isEqualTo("Bearer realm=\"digital-banking\"");
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    static void assertHeaders(MvcResult result) {
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(result.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(result.getResponse().getHeader("Content-Security-Policy")).isEqualTo("frame-ancestors 'none'");
    }

    @TestConfiguration
    static class Harness {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
        @Bean SessionAuthenticator sessionAuthenticator() {
            return token -> Optional.ofNullable(switch (token) {
                case "customer-a" -> session("1", "11", "csrf-a", "ROLE_CUSTOMER", "ACCOUNT_READ_SELF", "TRANSFER_CREATE");
                case "customer-a2" -> session("1", "11", "csrf-a2", "ROLE_CUSTOMER", "ACCOUNT_READ_SELF", "TRANSFER_CREATE");
                case "customer-b" -> session("2", "11", "csrf-b", "ROLE_CUSTOMER", "ACCOUNT_READ_SELF", "TRANSFER_CREATE");
                case "other-customer" -> session("3", "12", "csrf-c", "ROLE_CUSTOMER", "ACCOUNT_READ_SELF", "TRANSFER_CREATE");
                case "auditor" -> session("4", null, "csrf-d", "ROLE_AUDITOR", "AUDIT_READ", "AUDIT_EXPORT");
                case "maker" -> session("5", null, "csrf-e", "ROLE_OPERATIONS", "LIMIT_MAKER");
                case "checker" -> session("6", null, "csrf-f", "ROLE_OPERATIONS", "LIMIT_MAKER", "CHECKER");
                case "method-owner" -> session("7", null, "csrf-g", "SYNTHETIC_METHOD");
                default -> null;
            });
        }
        private static SessionAuthenticator.Session session(String user, String customer, String csrf, String... authorities) {
            return new SessionAuthenticator.Session(user, customer, Set.of(authorities), HmacService.sha256Hex(csrf));
        }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-12T00:00:00Z"));
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }

    @RestController
    static class Fixture {
        @GetMapping({"/api/v1/accounts", "/api/v1/audit/export"})
        Map<String, Boolean> get() { return Map.of("synthetic", true); }
        @PostMapping({"/api/v1/transfers", "/api/v1/admin/limits/{id}/approve", "/api/v1/auth/step-up/verify", "/api/v1/auth/otp/verify"})
        Map<String, Boolean> mutation() { return Map.of("synthetic", true); }
        @RequestMapping(path = "/api/v1/fixture/body", method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
        Map<String, Integer> echo(@RequestBody(required = false) byte[] bytes) {
            return Map.of("bytes", bytes == null ? 0 : bytes.length);
        }
        @PostMapping("/api/v1/auth/login")
        Map<String, Boolean> loginFixture() { return Map.of("synthetic", true); }
        @GetMapping("/api/v1/fixture/method") @PreAuthorize("hasAuthority('SYNTHETIC_METHOD')")
        Map<String, Boolean> securedMethod() { return Map.of("synthetic", true); }
    }
}
