package com.bank.simulator.identity.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenGeneratorTest {

    @Test
    void generatesUniqueOpaqueAtLeast256BitTokens() {
        RefreshTokenGenerator generator = new RefreshTokenGenerator(new java.security.SecureRandom());
        String first = generator.generate();
        String second = generator.generate();
        assertThat(first).hasSizeGreaterThanOrEqualTo(43);
        assertThat(first).isNotEqualTo(second);
    }
}
