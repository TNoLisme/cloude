package com.simulatedbank.banking.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.common.config.AppProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
class CryptoOperationsTest {
    @LocalServerPort int port;
    @Autowired AppProperties properties;
    @Autowired Environment environment;
    @Autowired PiiCryptoService crypto;
    @Autowired HmacService hmac;
    @Autowired JcsCanonicalizer jcs;

    @Test
    void realOperationalEndpointsDoNotExposeKeysAndBeansWorkTogether() throws Exception {
        String encryptedValue = crypto.decrypt(crypto.encrypt("synthetic"));
        assertThat(encryptedValue).isEqualTo("synthetic");
        assertThat(hmac.hmacSha256Hex("00123")).hasSize(64);
        assertThat(jcs.canonicalize("{\"z\":1,\"a\":null}")).asString().isEqualTo("{\"a\":null,\"z\":1}");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,prometheus,metrics");
        try (var client = HttpClient.newHttpClient()) {
            for (String route : new String[] {"/actuator/health", "/actuator/info", "/actuator/env",
                    "/actuator/configprops", "/actuator/metrics", "/actuator/prometheus"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + route))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(
                        route.equals("/actuator/health") || route.equals("/actuator/info") ? 200 : 403);
                assertThat(response.body()).doesNotContain(properties.crypto().aesKeyBase64(),
                        properties.crypto().hmacKeyBase64(), "aesKeyBase64", "hmacKeyBase64", "previousKeys");
            }
        }
    }
}
