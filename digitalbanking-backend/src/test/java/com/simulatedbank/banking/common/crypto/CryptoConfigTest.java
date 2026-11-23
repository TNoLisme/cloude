package com.simulatedbank.banking.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.config.CryptoConfig;
import java.util.Base64;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@ExtendWith(OutputCaptureExtension.class)
class CryptoConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @ParameterizedTest
    @ValueSource(strings = {"local", "test", "demo", "prod", "cloud", "local,demo"})
    void externalSeparateKeysStartInEveryProfile(String profile, CapturedOutput output) {
        runner.withPropertyValues("spring.profiles.active=" + profile,
                "app.crypto.aes-key-base64=" + key(0, 32),
                "app.crypto.hmac-key-base64=" + key(32, 32)).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PiiCryptoService.class).hasSingleBean(HmacService.class);
                    assertThat(context.getBean(PiiCryptoService.class).decrypt(
                            context.getBean(PiiCryptoService.class).encrypt("synthetic"))).isEqualTo("synthetic");
                    assertThat(context.getBean(AppProperties.class).toString()).doesNotContain(key(0, 32), key(32, 32));
                    assertThat(context.getBean(PiiCryptoService.class).toString()).doesNotContain(key(0, 32));
                    assertThat(context.getBean(HmacService.class).toString()).doesNotContain(key(32, 32));
                });
        assertThat(output.getAll()).doesNotContain(key(0, 32), key(32, 32));
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test"})
    void syntheticProfileKeysAreAllowedOnlyForDevelopment(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"demo", "prod", "cloud", "local,demo", "test,prod"})
    void missingOrPublishedDevelopmentKeysFailOutsideDevelopment(String profile, CapturedOutput output) {
        runner.withPropertyValues("spring.profiles.active=" + profile,
                "app.crypto.aes-key-base64=", "app.crypto.hmac-key-base64=")
                .run(context -> assertThat(context).hasFailed());
        String aes = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
        String hmac = "MTEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
        runner.withPropertyValues("spring.profiles.active=" + profile,
                "app.crypto.aes-key-base64=" + aes, "app.crypto.hmac-key-base64=" + hmac)
                .run(context -> assertThat(context).hasFailed());
        assertThat(output.getAll()).doesNotContain(aes, hmac);
    }

    @Test
    void bothKeySlotsRejectInvalidLengthsEncodingAndMissingValuesWithoutLeaking(CapturedOutput output) {
        for (String slot : new String[] {"aes", "hmac"}) {
            for (String invalid : new String[] {"", "synthetic-secret-INVALID!", key(0, 31), key(0, 33)}) {
                runner.withPropertyValues("spring.profiles.active=demo",
                        "app.crypto.aes-key-base64=" + key(0, 32),
                        "app.crypto.hmac-key-base64=" + key(32, 32),
                        "app.crypto." + slot + "-key-base64=" + invalid).run(context -> {
                            assertThat(context).hasFailed();
                            String stack = org.assertj.core.util.Throwables.getStackTrace(context.getStartupFailure());
                            assertThat(stack).contains("Crypto keys must be Base64-encoded 32-byte values")
                                    .doesNotContain("synthetic-secret-INVALID!", key(0, 31), key(0, 33));
                        });
            }
        }
        assertThat(output.getAll()).doesNotContain("synthetic-secret-INVALID!", key(0, 31), key(0, 33));
    }

    @Test
    void equalKeysPlaceholderAndUnsupportedRotationFailFast() {
        for (String[] properties : new String[][] {
                {"app.crypto.aes-key-base64=" + key(0, 32), "app.crypto.hmac-key-base64=" + key(0, 32)},
                {"app.crypto.aes-key-base64=" + Base64.getEncoder().encodeToString(new byte[32]),
                        "app.crypto.hmac-key-base64=" + key(32, 32)},
                {"app.crypto.aes-key-base64=" + key(0, 32), "app.crypto.hmac-key-base64=" + key(32, 32),
                        "app.crypto.previous-keys[0]=synthetic-previous-secret"}}) {
            runner.withPropertyValues("spring.profiles.active=demo").withPropertyValues(properties)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(org.assertj.core.util.Throwables.getStackTrace(context.getStartupFailure()))
                                .doesNotContain("synthetic-previous-secret", key(0, 32), key(32, 32));
                    });
        }
    }

    private static String key(int offset, int length) {
        byte[] value = new byte[length];
        IntStream.range(0, length).forEach(i -> value[i] = (byte) (offset + i));
        return Base64.getEncoder().encodeToString(value);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    @Import(CryptoConfig.class)
    static class Config {
    }
}
