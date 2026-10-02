package com.bank.simulator.identity.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties(OtpMailboxProperties.class)
public class OtpMailboxConfiguration {

    private final OtpMailboxProperties properties;
    private final Environment environment;

    public OtpMailboxConfiguration(OtpMailboxProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        if (!properties.mailboxEnabled()) return;
        if (!environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local", "demo"))) {
            throw new IllegalStateException("OTP mailbox is allowed only in local or demo profile");
        }
        String address = environment.getProperty("server.address", "");
        if (!address.isBlank() && !address.equals("127.0.0.1") && !address.equals("localhost") && !address.equals("::1")) {
            throw new IllegalStateException("OTP mailbox requires localhost server.address");
        }
        if (properties.mailboxGuardToken() == null || properties.mailboxGuardToken().isBlank()) {
            throw new IllegalStateException("OTP mailbox guard token is required when mailbox is enabled");
        }
    }
}
