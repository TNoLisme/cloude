package com.bank.simulator.shared.error;

import com.bank.simulator.shared.api.FieldError;
import com.bank.simulator.shared.api.Problem;
import com.bank.simulator.shared.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Problem> handleApiException(ApiException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.getMessage(), exception.fieldErrors(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<Problem> handleValidationException(Exception exception, HttpServletRequest request) {
        List<FieldError> errors = exception instanceof MethodArgumentNotValidException validation
                ? validation.getBindingResult().getFieldErrors().stream()
                        .sorted(Comparator.comparing(org.springframework.validation.FieldError::getField))
                        .map(error -> new FieldError(error.getField(), "INVALID", "Invalid value"))
                        .toList()
                : List.of();
        return response(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_ERROR,
                "Request is invalid.", errors, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> handleUnexpectedException(Exception exception, HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        log.error("Unhandled request failure correlationId={} errorType={}", correlationId,
                exception.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, ProblemCode.INTERNAL_ERROR,
                "An unexpected error occurred.", List.of(), request);
    }

    private ResponseEntity<Problem> response(HttpStatus status, String code, String detail,
                                             List<FieldError> fieldErrors, HttpServletRequest request) {
        Problem problem = new Problem(URI.create("about:blank"), status.getReasonPhrase(), status.value(), detail,
                request.getRequestURI(), code, correlationId(request), fieldErrors.isEmpty() ? null : fieldErrors);
        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType("application/problem+json"))
                .body(problem);
    }

    private UUID correlationId(HttpServletRequest request) {
        UUID correlationId = CorrelationIdFilter.from(request);
        return correlationId == null ? UUID.randomUUID() : correlationId;
    }
}
