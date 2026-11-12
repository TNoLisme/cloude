package com.simulatedbank.banking.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.SystemEnvironmentPropertySource;

@ExtendWith(OutputCaptureExtension.class)
class AppPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    private final ApplicationContextRunner configDataRunner = runner
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void defaultsAreUsableWithoutYamlIncludingNestedGroups() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AppProperties properties = context.getBean(AppProperties.class);
            assertThat(properties.timezone()).isEqualTo("Asia/Ho_Chi_Minh");
            assertThat(properties.security().lockout().maxFailedAttempts()).isEqualTo(5);
            assertThat(properties.security().lockout().lockDurationMinutes()).isEqualTo(10);
            assertThat(properties.security().rateLimit().transferPerCustomerPerMinute()).isEqualTo(30);
            assertThat(properties.security().session().idleTimeoutMinutes()).isEqualTo(15);
            assertThat(properties.security().session().absoluteTimeoutHours()).isEqualTo(8);
            assertThat(AppProperties.Security.Session.class.getRecordComponents())
                    .extracting(component -> component.getName())
                    .containsExactly("idleTimeoutMinutes", "absoluteTimeoutHours");
            assertThat(properties.security().cookie().name()).isEqualTo("DB_SESSION");
            assertThat(properties.security().cookie().path()).isEqualTo("/");
            assertThat(properties.security().otp()).isNotNull();
            assertThat(properties.security().stepUp()).isNotNull();
            assertThat(properties.crypto().previousKeys()).isEmpty();
            assertThat(properties.transfer()).isNotNull();
            assertThat(properties.fraud()).isNotNull();
            assertThat(properties.jobs()).isNotNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test", "demo"})
    void yamlAndRecordDefaultsAgreeAcrossProfiles(String profile) {
        runner.run(defaultContext -> configDataRunner
                .withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AppProperties defaults = defaultContext.getBean(AppProperties.class);
                    AppProperties actual = context.getBean(AppProperties.class);
                    assertThat(actual.timezone()).isEqualTo(defaults.timezone());
                    assertThat(actual.security()).isEqualTo(defaults.security());
                    assertThat(actual.transfer()).isEqualTo(defaults.transfer());
                    assertThat(actual.fraud()).isEqualTo(defaults.fraud());
                    assertThat(actual.jobs()).isEqualTo(defaults.jobs());
                    if (!profile.equals("demo")) {
                        assertThat(actual.crypto().aesKeyBase64()).isNotBlank();
                        assertThat(actual.crypto().hmacKeyBase64()).isNotBlank();
                    }
                }));
    }

    @Test
    void environmentOverridesYamlAndProfileDefaults() {
        configDataRunner.withPropertyValues("spring.profiles.active=local")
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("testEnvironment", Map.of(
                                "APP_TIMEZONE", "America/New_York",
                                "APP_LOCKOUT_MAX_FAILED_ATTEMPTS", "7",
                                "APP_LOCKOUT_DURATION_MINUTES", "12",
                                "APP_TRANSFER_RATE_LIMIT_PER_CUSTOMER_PER_MINUTE", "40"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AppProperties properties = context.getBean(AppProperties.class);
                    assertThat(context.getBean(ZoneId.class)).isEqualTo(ZoneId.of("America/New_York"));
                    assertThat(properties.security().lockout().maxFailedAttempts()).isEqualTo(7);
                    assertThat(properties.security().lockout().lockDurationMinutes()).isEqualTo(12);
                    assertThat(properties.security().rateLimit().transferPerCustomerPerMinute()).isEqualTo(40);
                    assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
                });
    }

    @Test
    void explicitPropertiesOverrideYaml() {
        configDataRunner.withPropertyValues(
                "spring.profiles.active=test",
                "app.timezone=UTC",
                "app.security.lockout.max-failed-attempts=8",
                "app.security.lockout.lock-duration-minutes=20",
                "app.security.rate-limit.transfer-per-customer-per-minute=60")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AppProperties properties = context.getBean(AppProperties.class);
                    assertThat(context.getBean(ZoneId.class)).isEqualTo(ZoneId.of("UTC"));
                    assertThat(properties.security().lockout().maxFailedAttempts()).isEqualTo(8);
                    assertThat(properties.security().lockout().lockDurationMinutes()).isEqualTo(20);
                    assertThat(properties.security().rateLimit().transferPerCustomerPerMinute()).isEqualTo(60);
                });
    }

    @ParameterizedTest
    @CsvSource({
        "app.timezone, Invalid/SyntheticSensitiveZone",
        "app.security.lockout.max-failed-attempts, 0",
        "app.security.lockout.max-failed-attempts, -1",
        "app.security.lockout.max-failed-attempts, invalid",
        "app.security.lockout.lock-duration-minutes, 0",
        "app.security.lockout.lock-duration-minutes, -1",
        "app.security.lockout.lock-duration-minutes, invalid",
        "app.security.rate-limit.transfer-per-customer-per-minute, 0",
        "app.security.rate-limit.transfer-per-customer-per-minute, -1",
        "app.security.rate-limit.transfer-per-customer-per-minute, invalid"
    })
    void invalidConfigurationFailsStartupWithoutSecrets(String property, String value, CapturedOutput output) {
        runner.withPropertyValues(property + "=" + value,
                "app.crypto.aes-key-base64=synthetic-aes-secret",
                "app.crypto.hmac-key-base64=synthetic-hmac-secret",
                "app.crypto.previous-keys[0]=synthetic-previous-secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    String diagnosticProperty = property;
                    if (value.equals("0") || value.equals("-1")) {
                        diagnosticProperty = switch (property) {
                            case "app.security.lockout.max-failed-attempts" -> "security.lockout.maxFailedAttempts";
                            case "app.security.lockout.lock-duration-minutes" -> "security.lockout.lockDurationMinutes";
                            default -> "security.rateLimit.transferPerCustomerPerMinute";
                        };
                    }
                    assertThat(context.getStartupFailure()).hasStackTraceContaining(diagnosticProperty)
                            .hasStackTraceContaining(property.equals("app.timezone")
                                    ? "must be a valid ZoneId" : "Failed to bind properties");
                    assertThat(org.assertj.core.util.Throwables.getStackTrace(context.getStartupFailure()))
                            .doesNotContain("synthetic-aes-secret", "synthetic-hmac-secret",
                                    "synthetic-previous-secret", "Invalid/SyntheticSensitiveZone");
                });
        assertThat(output.getAll()).doesNotContain("synthetic-aes-secret", "synthetic-hmac-secret",
                "synthetic-previous-secret", "Invalid/SyntheticSensitiveZone");
    }

    @Test
    void blankTimezoneFailsWithSafeDiagnostic() {
        runner.withPropertyValues("app.timezone=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("app.timezone must be a valid ZoneId");
        });
    }

    @Test
    void configurationToStringRedactsAllKeyMaterial() {
        runner.withPropertyValues(
                "app.crypto.aes-key-base64=synthetic-aes-secret",
                "app.crypto.hmac-key-base64=synthetic-hmac-secret",
                "app.crypto.previous-keys[0]=synthetic-previous-secret")
                .run(context -> {
                    AppProperties properties = context.getBean(AppProperties.class);
                    assertThat(properties.toString()).contains("Crypto[redacted]")
                            .doesNotContain("synthetic-aes-secret", "synthetic-hmac-secret", "synthetic-previous-secret");
                    assertThat(properties.crypto().toString()).isEqualTo("Crypto[redacted]");
                });
    }

    @Test
    void utcClockAndBusinessDateDoNotDependOnHostTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            runner.run(context -> {
                assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
                ZoneId businessZone = context.getBean(ZoneId.class);
                assertBusinessDates(businessZone, "2026-11-12T16:59:59Z", "2026-11-12", "2026-11-12");
                assertBusinessDates(businessZone, "2026-11-12T17:00:00Z", "2026-11-12", "2026-11-13");
            });
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void consumerCanInjectFixedClockAndConfiguredZone() {
        runner.withPropertyValues("app.timezone=America/New_York")
                .withBean("fixedClock", Clock.class,
                        () -> Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC),
                        definition -> definition.setPrimary(true))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Clock clock = context.getBean(Clock.class);
                    assertThat(clock.instant()).isEqualTo(Instant.parse("2026-11-12T00:00:00Z"));
                    assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.of(2026, 11, 12));
                    assertThat(LocalDate.now(clock.withZone(context.getBean(ZoneId.class))))
                            .isEqualTo(LocalDate.of(2026, 11, 11));
                });
    }

    private static void assertBusinessDates(ZoneId businessZone, String instant, String utcDate, String businessDate) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.parse(utcDate));
        assertThat(LocalDate.now(clock.withZone(businessZone))).isEqualTo(LocalDate.parse(businessDate));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    @Import(TimeConfig.class)
    static class PropertiesConfiguration {
    }
}
