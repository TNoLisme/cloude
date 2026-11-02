package com.bank.simulator.shared.pagination;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {
    private final CursorCodec codec = new CursorCodec(new ObjectMapper());

    @Test
    void roundTripsFractionalTimestampAndUuid() {
        var position = new CursorPosition(1, Instant.parse("2026-10-03T12:34:56.123456Z"),
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

        assertThat(codec.decode(codec.encode(position))).isEqualTo(position);
    }

    @Test
    void rejectsMalformedAndOversizedCursor() {
        assertThatThrownBy(() -> codec.decode("not-a-cursor"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("a".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
