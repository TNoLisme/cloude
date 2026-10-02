package com.bank.simulator.identity.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtpMailboxConfigurationTest {

    @Test
    void enabledMailboxRejectsPublicBindAddress() {
        StandardEnvironment environment = environment("local", "0.0.0.0");
        OtpMailboxConfiguration configuration = new OtpMailboxConfiguration(
                new OtpMailboxProperties(true, "guard"), environment);
        assertThatThrownBy(configuration::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("localhost");
    }

    @Test
    void enabledMailboxRejectsNonLocalProfiles() {
        StandardEnvironment environment = environment("prod", "127.0.0.1");
        OtpMailboxConfiguration configuration = new OtpMailboxConfiguration(
                new OtpMailboxProperties(true, "guard"), environment);
        assertThatThrownBy(configuration::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local or demo");
    }

    @Test
    void enabledMailboxRequiresGuardToken() {
        OtpMailboxConfiguration configuration = new OtpMailboxConfiguration(
                new OtpMailboxProperties(true, ""), environment("demo", "localhost"));
        assertThatThrownBy(configuration::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("guard token");
    }

    @Test
    void disabledMailboxDoesNotRequireLoopbackBinding() {
        OtpMailboxConfiguration configuration = new OtpMailboxConfiguration(
                new OtpMailboxProperties(false, ""), environment("prod", "0.0.0.0"));
        configuration.validate();
    }

    private StandardEnvironment environment(String profile, String address) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(profile);
        org.springframework.core.env.MapPropertySource source = new org.springframework.core.env.MapPropertySource(
                "test", Map.of("server.address", address));
        environment.getPropertySources().addFirst(source);
        return environment;
    }
}
