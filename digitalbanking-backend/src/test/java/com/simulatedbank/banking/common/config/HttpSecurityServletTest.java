package com.simulatedbank.banking.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletContext;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.filter.CorsFilter;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
@ActiveProfiles("test")
@Import(HttpSecurityTest.Fixture.class)
class HttpSecurityServletTest {
    @LocalServerPort int port;
    @Autowired ServletContext servletContext;
    @Autowired SecurityFilterChain chain;
    @Autowired org.springframework.beans.factory.ObjectProvider<SessionAuthenticator> authenticators;

    @Test
    void noIdentityAdapterFailsClosedAndSecurityFiltersAreNotDoubleRegistered() throws Exception {
        assertThat(authenticators.getIfAvailable()).isNull();
        assertThat(chain.getFilters().stream().filter(filter -> filter instanceof CorsFilter)).hasSize(1);
        assertThat(servletContext.getFilterRegistrations().values().stream()
                .filter(registration -> registration.getClassName().equals(CorsFilter.class.getName()))).isEmpty();
        try (var client = HttpClient.newHttpClient()) {
            for (String credential : new String[]{"", "Bearer synthetic-valid-looking-token"}) {
                var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/accounts"))
                        .header("X-Correlation-ID", "native:security");
                if (!credential.isEmpty()) builder.header("Authorization", credential);
                var response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(response.headers().firstValue("X-Correlation-ID")).contains("native:security");
                assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
                assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer realm=\"digital-banking\"");
                assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
                assertThat(response.body()).contains("AUTH_SESSION_EXPIRED").doesNotContain("synthetic-valid-looking-token");
            }
        }
    }

    @Test
    void realChunkedBodiesRespect65536ByteBoundaryWithoutContentLength() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (int length : new int[]{65536, 65537}) {
                var body = HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[length]));
                assertThat(body.contentLength()).isEqualTo(-1);
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                        .header("X-Correlation-ID", "chunked:boundary")
                        .POST(body).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(length == 65536 ? 200 : 413);
                assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
                assertThat(response.headers().firstValue("X-Correlation-ID")).contains("chunked:boundary");
                if (length > 65536) assertThat(response.body()).contains("REQUEST_TOO_LARGE");
            }
        }
    }
}
