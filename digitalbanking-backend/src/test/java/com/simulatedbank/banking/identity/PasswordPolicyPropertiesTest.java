package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.identity.application.PasswordPolicyProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PasswordPolicyPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PolicyConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {
            "app.security.password.history-size=0",
            "app.security.password.history-size=2",
            "app.security.password.min-length=7",
            "app.security.password.max-length=129",
            "app.security.password.min-length=129"
    })
    void invalidConfigurationCannotWeakenHistoryOrProduceUnusablePasswords(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PasswordPolicyProperties.class)
    static class PolicyConfiguration {
    }
}
