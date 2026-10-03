package com.bank.simulator.risk.application;

import com.bank.simulator.shared.error.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

public record RiskFlagQuery(int limit, String cursor, String ruleId, UUID transferId, Instant from, Instant to) {
    public RiskFlagQuery {
        if (limit < 1 || limit > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Limit is invalid.");
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "From must be before to.");
        }
    }
}
