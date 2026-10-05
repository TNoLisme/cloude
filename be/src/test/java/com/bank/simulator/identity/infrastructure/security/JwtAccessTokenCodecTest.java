package com.bank.simulator.identity.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAccessTokenCodecTest {

    private static final String SECRET = "01234567890123456789012345678901";

    @Test
    void signsAndVerifiesSubjectAndRoles() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC);
        JwtAccessTokenCodec codec = new JwtAccessTokenCodec(SECRET, java.time.Duration.ofSeconds(900), clock);
        UUID userId = UUID.randomUUID();

        var claims = codec.verify(codec.issue(userId, List.of("CUSTOMER", "AUDITOR")));

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("roles", List.class)).containsExactly("CUSTOMER", "AUDITOR");
        assertThat(claims.getExpiration().toInstant()).isEqualTo(clock.instant().plusSeconds(900));
    }

    @Test
    void rejectsWeakSigningSecret() {
        assertThatThrownBy(() -> new JwtAccessTokenCodec("short", java.time.Duration.ofSeconds(900), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTamperedToken() {
        Clock clock = Clock.systemUTC();
        JwtAccessTokenCodec codec = new JwtAccessTokenCodec(SECRET, java.time.Duration.ofSeconds(900), clock);
        String token = codec.issue(UUID.randomUUID(), List.of("CUSTOMER"));
        String signature = token.substring(token.lastIndexOf('.') + 1);
        int signatureStart = token.lastIndexOf('.') + 1;
        int tamperIndex = signatureStart + Math.max(1, signature.length() / 2);
        char original = token.charAt(tamperIndex);
        char replacement = original == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, tamperIndex) + replacement + token.substring(tamperIndex + 1);
        assertThatThrownBy(() -> codec.verify(tampered)).isInstanceOf(RuntimeException.class);
    }
}
