package com.simulatedbank.banking.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.context.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Shared MVC/filter/security formatter. IMP-03 supplies a validated request attribute correlationId. */
@Component
public class ProblemWriter {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemWriter.class);
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ProblemWriter(Clock clock, ObjectMapper objectMapper) {
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public ProblemResponse create(ErrorCode code, String safeDetail, HttpServletRequest request,
            List<ProblemResponse.FieldError> errors, Map<String, Object> safeExtensions) {
        Object attribute = request.getAttribute("correlationId");
        String correlationId = attribute instanceof String value && RequestContext.isValidCorrelationId(value)
                ? value : UUID.randomUUID().toString();
        request.setAttribute("correlationId", correlationId);
        request.setAttribute(com.simulatedbank.banking.common.context.HttpRequestLog.ERROR_ATTRIBUTE, code.name());
        return new ProblemResponse(code.type(), code.title(), code.httpStatus(), safeDetail,
                request.getRequestURI(), code.name(), correlationId, clock.instant(), code.retryable(),
                errors, safeExtensions);
    }

    public ResponseEntity<ProblemResponse> response(ProblemResponse problem) {
        log(problem);
        return ResponseEntity.status(problem.status()).headers(headers(problem)).body(problem);
    }

    public void write(HttpServletResponse response, ProblemResponse problem) throws IOException {
        write(response, problem, null);
    }

    /** Rate limiter supplies the actual remaining window; other errors keep existing defaults. */
    public void write(HttpServletResponse response, ProblemResponse problem, Long retryAfterSeconds) throws IOException {
        if (retryAfterSeconds != null && (retryAfterSeconds <= 0 || !problem.code().equals("RATE_LIMITED"))) {
            throw new IllegalArgumentException("Retry override requires a positive rate-limit window");
        }
        if (response.isCommitted()) {
            return;
        }
        byte[] body = objectMapper.writeValueAsBytes(problem);
        response.resetBuffer();
        response.setStatus(problem.status());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        HttpHeaders headers = headers(problem);
        if (retryAfterSeconds != null) {
            headers.set(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString());
        }
        headers.forEach((name, values) -> response.setHeader(name, values.getFirst()));
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        log(problem);
    }

    private HttpHeaders headers(ProblemResponse problem) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.set("X-Correlation-ID", problem.correlationId());
        if (problem.status() == 401) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"digital-banking\"");
        }
        ErrorCode code = ErrorCode.valueOf(problem.code());
        if (code == ErrorCode.IDEMPOTENCY_IN_PROGRESS || code == ErrorCode.RATE_LIMITED
                || code == ErrorCode.SERVICE_UNAVAILABLE) {
            // 2 seconds is within design/02 §7.10's proposed 1–5 second range.
            headers.set(HttpHeaders.RETRY_AFTER, "2");
        }
        return headers;
    }

    private void log(ProblemResponse problem) {
        // No throwable, raw path, validation message/value or owner extension enters the log.
        LOG.warn("HTTP failure code={} status={} correlationId={}",
                problem.code(), problem.status(), problem.correlationId());
    }
}
