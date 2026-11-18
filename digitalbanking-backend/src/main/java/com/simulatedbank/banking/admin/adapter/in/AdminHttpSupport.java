package com.simulatedbank.banking.admin.adapter.in;

import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** Shared, fail-closed parsing and response shaping for the Admin inbound adapters. */
final class AdminHttpSupport {

    private static final String ID_PATTERN = "[1-9][0-9]{0,18}";

    private AdminHttpSupport() {
    }

    /** Rejects a JSON {@code null} body before dereferencing the DTO. */
    static <T> T requiredBody(T body) {
        if (body == null) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return body;
    }

    /** Schema {@code ID}: decimal string, no leading zero, max BIGINT. */
    static long id(String value) {
        if (value == null || !value.matches(ID_PATTERN)) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return Long.parseLong(value);
    }

    static Long optionalId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return id(value.strip());
    }

    static ApprovalDecision decision(String value) {
        if (value == null) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            return ApprovalDecision.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    static RoleChangeOperation operation(String value) {
        if (value == null) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            return RoleChangeOperation.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    static BigDecimal money(String value) {
        if (value == null) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException invalid) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (RuntimeException invalid) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    static String correlation(HttpServletRequest request) {
        var current = RequestContextHolder.current();
        if (current.isPresent()) {
            return current.get().correlationId();
        }
        Object attribute = request.getAttribute("correlationId");
        if (attribute instanceof String value && RequestContext.isValidCorrelationId(value)) {
            return value;
        }
        return UUID.randomUUID().toString();
    }

    static String requestId(HttpServletRequest request) {
        var current = RequestContextHolder.current();
        if (current.isPresent()) {
            return current.get().requestId();
        }
        Object attribute = request.getAttribute("requestId");
        return attribute instanceof String value ? value : null;
    }

    static ResponseEntity<Map<String, Object>> response(int status, Map<String, Object> body, boolean replayed,
            Long approvalId) {
        var builder = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (replayed) {
            builder.header("Idempotent-Replayed", "true");
        }
        if (approvalId != null) {
            builder.location(URI.create("/api/v1/admin/approvals/" + approvalId));
        }
        return builder.body(body);
    }
}
