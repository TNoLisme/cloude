package com.simulatedbank.banking.common.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.common.config.RequestContextConfig;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import jakarta.servlet.ServletContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "banking.modules.enabled=false"
})
@ActiveProfiles("test")
@Import(ContextServletTest.Fixture.class)
class ContextServletTest {
    @LocalServerPort int port;
    @Autowired ServletContext servletContext;
    @Autowired RequestContextConfig config;
    @Autowired Environment environment;
    @Autowired com.simulatedbank.banking.common.error.ProblemWriter writer;

    @Test
    void actualServletRegistrationRunsOnceBeforeProductionSecurityAndIgnoresForwardHeaders() throws Exception {
        assertThat(servletContext.getFilterRegistrations().values().stream()
                .filter(registration -> registration.getClassName().equals(CorrelationAndRequestIdFilter.class.getName())))
                .hasSize(1);
        assertThat(config.correlationAndRequestIdFilter(writer).getOrder()).isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
        assertThat(servletContext.getFilterRegistration("correlationAndRequestIdFilter").getUrlPatternMappings()).contains("/*");
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("none");
        assertThat(servletContext.getFilterRegistrations()).doesNotContainKey("forwardedHeaderFilter");
        try (var client = HttpClient.newHttpClient()) {
            for (String route : new String[]{
                    "/actuator/health", "/api/v1/auth/fido/verify", "/api/v1/private"}) {
                var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + route))
                        .header("X-Correlation-ID", "live:trace")
                        .header("Forwarded", "for=198.51.100.123;proto=https")
                        .header("X-Forwarded-For", "198.51.100.123");
                var request = route.equals("/api/v1/auth/fido/verify")
                        ? builder.POST(HttpRequest.BodyPublishers.noBody()).build() : builder.GET().build();
                var result = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(result.headers().allValues("X-Correlation-ID")).containsExactly("live:trace");
                assertThat(result.statusCode()).isEqualTo(switch (route) {
                    case "/actuator/health" -> 200;
                    case "/api/v1/auth/fido/verify" -> 409;
                    default -> 401;
                });
            }
        }
    }

    @RestController
    static class Fixture {
        @PostMapping("/api/v1/auth/fido/verify")
        void businessError() {
            throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }
}
