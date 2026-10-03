package com.bank.simulator.shared.pagination;

import java.time.Instant;
import java.util.UUID;

public record CursorPosition(int version, Instant createdAt, UUID id) {
    public CursorPosition {
        if (version != 1 || createdAt == null || id == null) {
            throw new IllegalArgumentException("Cursor position is invalid");
        }
    }
}
