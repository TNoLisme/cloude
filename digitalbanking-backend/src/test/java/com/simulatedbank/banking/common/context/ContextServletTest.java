package com.simulatedbank.banking.common.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.common.config.RequestContextConfig;
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
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
@ActiveProfiles("test")
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
            for (String route : new String[]{"/actuator/health", "/api/v1/private"}) {
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + route))
                        .header("X-Correlation-ID", "live:trace")
                        .header("Forwarded", "for=198.51.100.123;proto=https")
                        .header("X-Forwarded-For", "198.51.100.123").GET().build();
                var result = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(result.headers().allValues("X-Correlation-ID")).containsExactly("live:trace");
                assertThat(result.statusCode()).isEqualTo(route.equals("/actuator/health") ? 200 : 401);
            }
        }
    }
}
