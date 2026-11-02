package com.bank.simulator.identity.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfTokenServiceTest {

    private final CsrfTokenService service = new CsrfTokenService("12345678901234567890123456789012");

    @Test
    void acceptsIssuedToken() {
        String token = service.issue();
        assertThat(service.isValid(token)).isTrue();
    }

    @Test
    void rejectsTamperedToken() {
        String token = service.issue();
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");
        assertThat(service.isValid(tampered)).isFalse();
    }

    @Test
    void currentlyAllowsReplayOfSameStatelessToken() {
        String token = service.issue();
        assertThat(service.isValid(token)).isTrue();
        assertThat(service.isValid(token)).isTrue();
    }
}
