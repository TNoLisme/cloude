package com.simulatedbank.banking.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.error.ProblemWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpSecurityComponentsTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Properties.class);

    @Test
    void rateLimiterMakesConcurrentQuotaAtomicAndReclaimsExpiredCapacity() throws Exception {
        runner.run(context -> {
            var clock = new HttpSecurityTest.MutableClock();
            var limiter = new RequestRateLimiter(clock, context.getBean(AppProperties.class));
            try (var pool = Executors.newFixedThreadPool(8)) {
                var results = pool.invokeAll(IntStream.range(0, 100)
                        .<java.util.concurrent.Callable<Long>>mapToObj(i -> () ->
                                limiter.acquire("transfer", "11", 30, Duration.ofMinutes(1))).toList());
                assertThat(results.stream().filter(result -> {
                    try { return result.get() == 0; } catch (Exception e) { throw new AssertionError(e); }
                }).count()).isEqualTo(30);
            }
            clock.advance(Duration.ofMillis(1999));
            assertThat(limiter.acquire("transfer", "11", 30, Duration.ofMinutes(1))).isEqualTo(1);
            clock.advance(Duration.ofMillis(1));
            assertThat(limiter.acquire("transfer", "11", 30, Duration.ofMinutes(1))).isZero();
        });
        runner.withPropertyValues("app.security.http.max-rate-limit-buckets=1").run(context -> {
            var clock = new HttpSecurityTest.MutableClock();
            var limiter = new RequestRateLimiter(clock, context.getBean(AppProperties.class));
            assertThat(limiter.acquire("login-username", "synthetic-a", 10, Duration.ofMinutes(1))).isZero();
            assertThatThrownBy(() -> limiter.acquire("login-username", "synthetic-b", 10, Duration.ofMinutes(1)))
                    .isInstanceOf(RequestRateLimiter.CapacityExceededException.class)
                    .hasMessage("Rate limiter capacity exceeded");
            clock.advance(Duration.ofMinutes(1));
            assertThat(limiter.acquire("login-username", "synthetic-b", 10, Duration.ofMinutes(1))).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.com", "null", "https://example.com/path", "https://user@example.com", "invalid"})
    void corsRejectsInvalidConfigurationWithoutEchoingIt(String origin) {
        runner.withPropertyValues("app.security.cors-allowed-origins=" + origin).run(context -> {
            var config = new SecurityFilterChainConfig();
            assertThatThrownBy(() -> config.corsConfigurationSource(context.getBean(AppProperties.class)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("CORS requires explicit HTTP origins").hasNoCause();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.security.http.max-request-body-bytes=0", "app.security.http.max-request-body-bytes=2147483647",
            "app.security.http.max-rate-limit-buckets=0", "app.security.rate-limit.login-per-ip-per-minute=0",
            "app.security.rate-limit.login-per-username-per-minute=0", "app.security.rate-limit.read-per-user-per-minute=0",
            "app.security.rate-limit.step-up-per-user-per-5-minutes=0", "app.security.rate-limit.otp-verify-per-user-per-minute=0",
            "app.security.rate-limit.audit-export-per-user-per-hour=0"})
    void invalidEnforcedLimitsFailStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bodyLimitChecksUnknownAndMisreportedLengthWithoutPassingExcessiveDataDownstream() throws Exception {
        var writer = new ProblemWriter(java.time.Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC),
                new ObjectMapper().findAndRegisterModules());
        var filter = new RequestBodyLimitFilter(8, writer);
        for (long declaredLength : new long[]{-1, 1}) {
            var request = new MockHttpServletRequest("POST", "/api/v1/fixture/body") {
                @Override public long getContentLengthLong() { return declaredLength; }
            };
            request.setContent(new byte[9]);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> { throw new AssertionError("Excess body reached owner"); });
            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(response.getContentAsString()).contains("REQUEST_TOO_LARGE");
        }
        var request = new MockHttpServletRequest("POST", "/api/v1/fixture/body");
        request.setContent("12345678".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            assertThat(req.getInputStream().readAllBytes()).hasSize(8);
        });
    }

    @Test
    void sessionSnapshotIsImmutableAndRedactsSecurityState() {
        var values = new java.util.HashSet<>(Set.of("ACCOUNT_READ_SELF"));
        var session = new SessionAuthenticator.Session("1", "11", values, "0".repeat(64));
        values.clear();
        assertThat(session.authorities()).containsExactly("ACCOUNT_READ_SELF");
        assertThat(session.toString()).isEqualTo("Session[redacted]");
        assertThatThrownBy(() -> session.authorities().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new SessionAuthenticator.Session("1", "11", Set.of(), "raw-secret"))
                .hasMessage("Invalid authenticated session snapshot");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class Properties {
    }
}
