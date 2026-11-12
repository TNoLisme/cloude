package com.simulatedbank.banking.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.yaml.snakeyaml.Yaml;

class ErrorContractTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T07:00:00Z"), ZoneOffset.UTC);
    private final ProblemWriter writer = new ProblemWriter(clock, mapper);

    @Test
    @SuppressWarnings("unchecked")
    void entireCatalogMatchesSemanticSourceAndOpenApi() throws Exception {
        String source = Files.readString(Path.of("../digitalbanking-docs/docs-common/07. API contracts.md"));
        String catalog = source.substring(source.indexOf("#### 4.3.1."), source.indexOf("#### 4.3.7."));
        var matcher = Pattern.compile("\\| `([A-Z_]+)` \\| (\\d+) \\| ([^|]+) \\|").matcher(catalog);
        List<String> codes = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            ErrorCode code = ErrorCode.valueOf(name);
            codes.add(name);
            assertThat(code.httpStatus()).as(name).isEqualTo(Integer.parseInt(matcher.group(2)));
            assertThat(code.retryable()).as(name).isEqualTo(matcher.group(3).trim().startsWith("Có"));
            assertThat(code.type().isAbsolute()).isTrue();
            assertThat(code.title()).isNotBlank();
            Map<String, Object> extension = code == ErrorCode.STEP_UP_REQUIRED
                    ? Map.of("challenge", challenge()) : Map.of();
            JsonNode json = mapper.valueToTree(writer.create(code, code.detail(), request(), List.of(), extension));
            assertThat(json.get("status").asInt()).isEqualTo(code.httpStatus());
            assertThat(json.get("retryable").asBoolean()).isEqualTo(code.retryable());
            assertThat(json.get("code").asText()).isEqualTo(name);
        }
        assertThat(codes).hasSize(46).containsExactlyElementsOf(
                java.util.Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
        Map<String, Object> spec = new Yaml().load(Files.readString(
                Path.of("../digitalbanking-docs/openapi/digital-banking-v1.yaml")));
        Map<String, Map<String, Object>> schemas = (Map<String, Map<String, Object>>)
                ((Map<String, Object>) spec.get("components")).get("schemas");
        assertThat((List<String>) schemas.get("ErrorCode").get("enum")).containsExactlyElementsOf(codes);
        for (String internal : List.of("NOTIFICATION_FAILED", "FRAUD_ENGINE_UNAVAILABLE", "RECONCILIATION_REQUIRED",
                "RESOURCE_LOCKED", "CONFLICT", "STEP_UP_INVALID", "MAKER_CHECKER_VIOLATION")) {
            assertThatThrownBy(() -> ErrorCode.valueOf(internal)).isInstanceOf(IllegalArgumentException.class);
        }
        JsonNode json = mapper.valueToTree(writer.create(ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.detail(), request(), List.of(), Map.of()));
        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrderElementsOf((List<String>) schemas.get("Problem").get("required"));
        assertThat(json.get("timestamp").asText()).isEqualTo("2026-11-12T07:00:00Z");
        assertThat(json.has("requestId")).isFalse();
        assertThat(json.has("extensions")).isFalse();
    }

    @Test
    void validationAndContextualExtensionsAreFlattenedWithoutChangingCore() throws Exception {
        var error = new ProblemResponse.FieldError("Idempotency-Key", "REQUIRED", "Thiếu khóa yêu cầu.");
        JsonNode validation = mapper.valueToTree(writer.create(ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.detail(), request(), List.of(error), Map.of()));
        assertThat(validation.path("errors").get(0).path("field").asText()).isEqualTo("Idempotency-Key");
        JsonNode stepUp = mapper.valueToTree(writer.create(ErrorCode.STEP_UP_REQUIRED,
                ErrorCode.STEP_UP_REQUIRED.detail(), request(), List.of(), Map.of("challenge", challenge())));
        assertThat(stepUp.path("challenge").path("purpose").asText()).isEqualTo("TRANSFER_STEP_UP");
        assertThat(stepUp.has("extensions")).isFalse();
        Map<String, Object> outcome = Map.of("reference", "TXN20261112ABCDEFGH", "transferStatus", "FAILED",
                "failureCode", "AUDIT_WRITE_FAILED");
        JsonNode audit = mapper.valueToTree(writer.create(ErrorCode.AUDIT_WRITE_FAILED,
                ErrorCode.AUDIT_WRITE_FAILED.detail(), request(), List.of(), outcome));
        assertThat(audit.path("transferStatus").asText()).isEqualTo("FAILED");
        assertThat(audit.path("reference").asText()).isEqualTo("TXN20261112ABCDEFGH");
        JsonNode locked = mapper.valueToTree(writer.create(ErrorCode.AUTH_ACCOUNT_LOCKED,
                ErrorCode.AUTH_ACCOUNT_LOCKED.detail(), request(), List.of(), Map.of("lockedUntil", clock.instant())));
        assertThat(locked.path("lockedUntil").asText()).isEqualTo("2026-11-12T07:00:00Z");
        for (String core : List.of("type", "title", "status", "detail", "instance", "code", "correlationId",
                "timestamp", "retryable", "errors", "requestId", "arbitrary")) {
            assertThatThrownBy(() -> new BankingBusinessException(ErrorCode.INTERNAL_ERROR, "An toàn.",
                    Map.of(core, "override"))).isInstanceOf(IllegalArgumentException.class);
        }
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of("challenge", challenge()),
                Map.<String, Object>of("lockedUntil", clock.instant()),
                Map.<String, Object>of("transferStatus", "PENDING"),
                Map.<String, Object>of("reference", "raw-sensitive-value"),
                Map.<String, Object>of("reference", "TXN20261112ABCDEFGH", "transferStatus", "COMPLETED"),
                Map.<String, Object>of("reference", "TXN20261112ABCDEFGH", "failureCode", "SQL raw"))) {
            assertThatThrownBy(() -> new BankingBusinessException(ErrorCode.INTERNAL_ERROR, "An toàn.", invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new BankingBusinessException(ErrorCode.STEP_UP_REQUIRED))
                .isInstanceOf(IllegalArgumentException.class);
        var invalidAttempts = new HashMap<>(challenge());
        invalidAttempts.put("maxAttempts", 0);
        var invalidMethods = new HashMap<>(challenge());
        invalidMethods.put("methods", List.of("UNKNOWN"));
        for (Object invalidChallenge : List.of("invalid", Map.of("challengeId", "missing-fields"),
                invalidAttempts, invalidMethods)) {
            assertThatThrownBy(() -> new BankingBusinessException(ErrorCode.STEP_UP_REQUIRED, "An toàn.",
                    Map.of("challenge", invalidChallenge))).isInstanceOf(IllegalArgumentException.class);
        }
        var mutable = new HashMap<String, Object>(outcome);
        var exception = new BankingBusinessException(ErrorCode.AUDIT_WRITE_FAILED, "An toàn.", mutable);
        mutable.put("status", 200);
        assertThat(exception.extensions()).doesNotContainKey("status");
    }

    @Test
    void servletWriterWorksWithoutMvcAndDoesNotRewriteCommittedResponse() throws Exception {
        var request = request();
        request.setQueryString("secret=synthetic-never-echo");
        request.setAttribute("correlationId", "invalid\r\nvalue");
        var problem = writer.create(ErrorCode.AUTH_SESSION_EXPIRED, ErrorCode.AUTH_SESSION_EXPIRED.detail(),
                request, List.of(), Map.of());
        var response = new MockHttpServletResponse();
        response.getWriter().write("old-buffer");
        writer.write(response, problem);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"digital-banking\"");
        assertThat(response.getHeader("X-Correlation-ID")).matches("[0-9a-f-]{36}");
        assertThat(response.getContentAsString()).doesNotContain("synthetic-never-echo", "invalid", "old-buffer");
        assertThat(mapper.readTree(response.getContentAsString()).path("instance").asText()).isEqualTo("/api/v1/transfers");
        response.flushBuffer();
        String committed = response.getContentAsString();
        writer.write(response, writer.create(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.detail(),
                request, List.of(), Map.of()));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo(committed);
    }

    @Test
    void constraintValidationNeverEchoesCustomMessagesAndReturnViolationsAreInternal() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var handler = new GlobalExceptionHandler(writer);
            var input = new ValidationFixture();
            var response = handler.handleConstraintValidation(new ConstraintViolationException(
                    validator.validate(input)), request());
            JsonNode body = mapper.valueToTree(response.getBody());
            assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("secret");
            assertThat(body.toString()).doesNotContain("synthetic-secret", "raw-provider");
            var returnViolations = validator.forExecutables().validateReturnValue(input,
                    ValidationFixture.class.getMethod("invalidReturn"), "");
            var returnResponse = handler.handleConstraintValidation(
                    new ConstraintViolationException(returnViolations), request());
            assertThat(returnResponse.getStatusCode().value()).isEqualTo(500);
            assertThat(returnResponse.getBody().code()).isEqualTo("INTERNAL_ERROR");
            assertThat(returnResponse.getBody().errors()).isNull();
        }
    }

    static class ValidationFixture {
        @NotBlank(message = "synthetic-secret raw-provider") String secret = "";
        @NotBlank(message = "synthetic-secret raw-provider") public String invalidReturn() { return ""; }
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/v1/transfers");
        request.setAttribute("correlationId", "contract-trace");
        return request;
    }

    static Map<String, Object> challenge() {
        return Map.of("challengeId", "123e4567-e89b-42d3-a456-426614174000", "purpose", "TRANSFER_STEP_UP",
                "methods", List.of("OTP"), "expiresAt", "2026-11-12T07:05:00Z", "maxAttempts", 5,
                "remainingAttempts", 5);
    }
}
