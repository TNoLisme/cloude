package com.simulatedbank.banking.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.error.ProblemWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicOtpRateLimitTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(HttpSecurityComponentsTest.Properties.class)
            .withPropertyValues("app.security.rate-limit.otp-verify-per-user-per-minute=2");

    @Test
    void publicOtpProofsAreLimitedBeforeOwnerExecutionWithoutReadingOrEchoingCredentials() {
        runner.run(context -> {
            Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
            var filter = new RequestRateLimitFilter(new RequestRateLimiter(clock, context.getBean(AppProperties.class), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                    context.getBean(AppProperties.class), new ProblemWriter(clock, new ObjectMapper().findAndRegisterModules()));
            AtomicInteger accepted = new AtomicInteger();
            assertThat(send(filter, "203.0.113.1", null, accepted).getStatus()).isEqualTo(200);
            assertThat(send(filter, "203.0.113.1", null, accepted).getStatus()).isEqualTo(200);
            var denied = send(filter, "203.0.113.1", null, accepted);
            assertThat(denied.getStatus()).isEqualTo(429);
            assertThat(denied.getHeader("Retry-After")).isNotBlank();
            assertThat(denied.getContentAsString()).contains("RATE_LIMITED").doesNotContain("123456", "proof-reference");
            assertThat(accepted.get()).isEqualTo(2);
            assertThat(send(filter, "203.0.113.2", null, accepted).getStatus()).isEqualTo(200);
            assertThat(accepted.get()).isEqualTo(3);
        });
    }

    @Test
    void authenticatedQuotaRemainsScopedToUserRatherThanAnExhaustedAnonymousIp() {
        runner.run(context -> {
            Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
            var filter = new RequestRateLimitFilter(new RequestRateLimiter(clock, context.getBean(AppProperties.class), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                    context.getBean(AppProperties.class), new ProblemWriter(clock, new ObjectMapper().findAndRegisterModules()));
            AtomicInteger accepted = new AtomicInteger();
            send(filter, "203.0.113.1", null, accepted);
            send(filter, "203.0.113.1", null, accepted);
            assertThat(send(filter, "203.0.113.1", null, accepted).getStatus()).isEqualTo(429);
            var actor = new SessionAuthenticator.Session("1", null, Set.of(), "a".repeat(64));
            assertThat(send(filter, "203.0.113.1", actor, accepted).getStatus()).isEqualTo(200);
            assertThat(send(filter, "203.0.113.1", actor, accepted).getStatus()).isEqualTo(200);
            assertThat(send(filter, "203.0.113.1", actor, accepted).getStatus()).isEqualTo(429);
            assertThat(accepted.get()).isEqualTo(4);
        });
    }

    private MockHttpServletResponse send(RequestRateLimitFilter filter, String ip,
            SessionAuthenticator.Session session, AtomicInteger accepted) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/otp/verify");
        request.setRemoteAddr(ip);
        request.setContent("{\"challengeId\":\"proof-reference\",\"otp\":\"123456\"}".getBytes(StandardCharsets.UTF_8));
        if (session != null) {
            request.setAttribute(SessionAuthenticationFilter.SESSION, session);
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> accepted.incrementAndGet());
        return response;
    }
}
