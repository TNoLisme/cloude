package com.simulatedbank.banking.common.error;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemResponse(URI type, String title, int status, String detail,
        String instance, String code, String correlationId, Instant timestamp,
        boolean retryable, List<FieldError> errors, @JsonIgnore Map<String, Object> extensions) {

    public ProblemResponse {
        ErrorCode errorCode = ErrorCode.valueOf(code);
        extensions = checkedExtensions(errorCode, extensions);
        errors = errors == null || errors.isEmpty() ? null : List.copyOf(errors);
        if (errors != null && errorCode != ErrorCode.VALIDATION_ERROR) {
            throw new IllegalArgumentException("Field errors require a validation error");
        }
    }

    @JsonAnyGetter
    public Map<String, Object> extensions() { return extensions; }

    public record FieldError(String field, String code, String message) {}

    static Map<String, Object> checkedExtensions(ErrorCode code, Map<String, Object> extensions) {
        Map<String, Object> copy = Map.copyOf(extensions);
        Set<String> allowed = Set.of("challenge", "reference", "transferStatus", "failureCode", "lockedUntil");
        if (!allowed.containsAll(copy.keySet())) {
            throw new IllegalArgumentException("Unsupported problem extension");
        }
        if (copy.containsKey("challenge") != (code == ErrorCode.STEP_UP_REQUIRED)) {
            throw new IllegalArgumentException("Challenge requires STEP_UP_REQUIRED and vice versa");
        }
        if (copy.containsKey("challenge")) {
            checkChallenge(copy.get("challenge"));
        }
        if (copy.containsKey("lockedUntil") && code != ErrorCode.AUTH_ACCOUNT_LOCKED) {
            throw new IllegalArgumentException("Lock expiry requires AUTH_ACCOUNT_LOCKED");
        }
        if (copy.containsKey("lockedUntil") && !(copy.get("lockedUntil") instanceof Instant)) {
            throw new IllegalArgumentException("Lock expiry must be an Instant");
        }
        if (copy.containsKey("reference") && (!(copy.get("reference") instanceof String reference)
                || !reference.matches("TXN[0-9]{8}[A-Z0-9]{8,20}"))) {
            throw new IllegalArgumentException("Invalid transfer reference");
        }
        if ((copy.containsKey("transferStatus") || copy.containsKey("failureCode"))
                && !copy.containsKey("reference")) {
            throw new IllegalArgumentException("Transfer outcome requires a reference");
        }
        if (copy.containsKey("transferStatus")
                && !Set.of("PENDING", "FAILED").contains(copy.get("transferStatus"))) {
            throw new IllegalArgumentException("Invalid error transfer status");
        }
        if (copy.containsKey("failureCode") && !Set.of("INSUFFICIENT_BALANCE", "LIMIT_EXCEEDED",
                "ACCOUNT_INACTIVE", "ACCOUNT_NOT_FOUND", "FRAUD_BLOCKED", "OTP_DISPATCH_FAILED",
                "AUDIT_WRITE_FAILED", "INVALID_STATE_TRANSITION", "PROCESSING_ERROR")
                .contains(copy.get("failureCode"))) {
            throw new IllegalArgumentException("Invalid transfer failure code");
        }
        return copy;
    }

    private static void checkChallenge(Object value) {
        if (!(value instanceof Map<?, ?> challenge)
                || !challenge.keySet().containsAll(Set.of("challengeId", "purpose", "methods", "expiresAt",
                        "maxAttempts", "remainingAttempts"))
                || !Set.of("challengeId", "purpose", "methods", "expiresAt", "maxAttempts", "remainingAttempts",
                        "channel", "maskedDestination", "fidoRequestOptions").containsAll(challenge.keySet())) {
            throw new IllegalArgumentException("Invalid public challenge schema");
        }
        try {
            UUID.fromString(challenge.get("challengeId").toString());
            Instant.parse(challenge.get("expiresAt").toString());
            if (!Set.of("LOGIN_NEW_DEVICE", "TRANSFER_STEP_UP", "SENSITIVE_ACTION").contains(challenge.get("purpose"))
                    || !(challenge.get("methods") instanceof List<?> methods) || methods.isEmpty()
                    || !Set.of("OTP", "FIDO", "PASSCODE").containsAll(methods)
                    || !(challenge.get("maxAttempts") instanceof Integer max) || max < 1
                    || !(challenge.get("remainingAttempts") instanceof Integer remaining) || remaining < 0
                    || remaining > max) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            // Never include rejected extension values or the raw parsing exception in diagnostics.
            throw new IllegalArgumentException("Invalid public challenge values");
        }
    }
}
