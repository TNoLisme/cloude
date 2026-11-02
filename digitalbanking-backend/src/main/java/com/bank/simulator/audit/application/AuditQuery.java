package com.bank.simulator.audit.application;

import com.bank.simulator.shared.error.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

public record AuditQuery(int limit, String cursor, String eventType, UUID actorId, Instant from, Instant to) {
    public AuditQuery {
        if (limit < 1 || limit > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Limit must be between 1 and 100.");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "From must be before to.");
        }
        if (eventType != null && eventType.length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Event type is invalid.");
        }
    }
}
