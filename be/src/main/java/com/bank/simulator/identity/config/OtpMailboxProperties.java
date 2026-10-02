package com.bank.simulator.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.otp")
public record OtpMailboxProperties(boolean mailboxEnabled, String mailboxGuardToken) {
}
