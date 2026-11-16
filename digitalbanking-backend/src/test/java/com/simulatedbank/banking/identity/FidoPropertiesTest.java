package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.identity.adapter.out.YubicoFidoAssertionVerifier;
import com.simulatedbank.banking.identity.application.FidoProperties;
import com.simulatedbank.banking.identity.application.port.FidoAssertionVerifier;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class FidoPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(FidoConfiguration.class);

    @Test
    void disabledByDefaultNeedsNoRpOrOriginsAndCreatesNoVerifier() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(FidoAssertionVerifier.class);
            FidoProperties properties = context.getBean(FidoProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.rpId()).isNull();
            assertThat(properties.allowedOrigins()).isEmpty();
            assertThat(properties.challengeTtl()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void disabledFeatureDoesNotValidateUnusedConfiguration() {
        runner.withPropertyValues("app.identity.fido.enabled=false", "app.identity.fido.rp-id=https://invalid",
                "app.identity.fido.allowed-origins=http://unsafe.example/*", "app.identity.fido.challenge-ttl=-1s")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(FidoAssertionVerifier.class));
    }

    @Test
    void enabledWithoutExplicitConfigurationFailsStartup() {
        runner.withPropertyValues("app.identity.fido.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledHttpsConfigurationCreatesExactlyOneRealVerifier() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FidoAssertionVerifier.class);
            assertThat(context.getBean(FidoAssertionVerifier.class)).isInstanceOf(YubicoFidoAssertionVerifier.class);
            assertThat(context.getBean(FidoProperties.class).allowedOrigins()).containsExactly("https://bank.example");
        });
    }

    @Test
    void explicitMultipleOriginsAndPortsAreSupported() {
        enabled().withPropertyValues("app.identity.fido.allowed-origins=https://bank.example,https://login.bank.example:8443")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FidoProperties.class).allowedOrigins())
                            .containsExactlyInAnyOrder("https://bank.example", "https://login.bank.example:8443");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "https://bank.example", "bank.example/path", "*.bank.example", "BANK.EXAMPLE",
            "bank.example:443", "bank..example", "-bank.example", "bank-.example", "bank.example.", "127.0.0.1"})
    void enabledRejectsInvalidRpIds(String rpId) {
        enabled().withPropertyValues("app.identity.fido.rp-id=" + rpId)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "*", "https://*.bank.example", "https://bank.example:*", "https://bank.example/",
            "https://bank.example/path", "https://bank.example?query", "https://bank.example#fragment",
            "https://user@bank.example", "https://bank.example:", "https://bank.example:0",
            "https://bank.example:65536", "http://bank.example", "http://localhost.evil", "null",
            "https://bank.example.evil", "https://unrelated.example", "ftp://bank.example", "HTTPS://bank.example"})
    void enabledRejectsMissingWildcardInsecureOrNonOriginValues(String origin) {
        enabled().withPropertyValues("app.identity.fido.allowed-origins=" + origin)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s"})
    void enabledRequiresPositiveChallengeTtl(String ttl) {
        enabled().withPropertyValues("app.identity.fido.challenge-ttl=" + ttl)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void localhostHttpFailsWithoutExplicitDevelopmentProfile() {
        localhost().run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionCannotEnableLocalhostHttpByCombiningDevelopmentProfile() {
        localhost().withPropertyValues("spring.profiles.active=production,local")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"production", "dev", "default"})
    void localhostHttpFailsInNonApprovedProfiles(String profile) {
        localhost().withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test"})
    void localhostHttpRequiresActiveLocalOrTestProfile(String profile) {
        localhost().withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(FidoAssertionVerifier.class));
    }

    @Test
    void testProfileStillCannotEnableHttpOnNonLocalhostHost() {
        enabled().withPropertyValues("spring.profiles.active=test", "app.identity.fido.allowed-origins=http://bank.example")
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("app.identity.fido.enabled=true", "app.identity.fido.rp-id=bank.example",
                "app.identity.fido.allowed-origins=https://bank.example");
    }

    private ApplicationContextRunner localhost() {
        return runner.withPropertyValues("app.identity.fido.enabled=true", "app.identity.fido.rp-id=localhost",
                "app.identity.fido.allowed-origins=http://localhost:3000");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FidoProperties.class)
    @Import(YubicoFidoAssertionVerifier.class)
    static class FidoConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
