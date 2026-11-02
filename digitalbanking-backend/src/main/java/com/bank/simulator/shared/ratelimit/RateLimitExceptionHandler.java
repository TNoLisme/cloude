package com.bank.simulator.shared.ratelimit;

import com.bank.simulator.shared.api.Problem;
import com.bank.simulator.shared.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.UUID;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class RateLimitExceptionHandler {

    @ExceptionHandler(RateLimitInterceptor.RateLimitedException.class)
    public ResponseEntity<Problem> handle(RateLimitInterceptor.RateLimitedException exception, HttpServletRequest request) {
        UUID correlationId = CorrelationIdFilter.from(request);
        if (correlationId == null) {
            correlationId = UUID.randomUUID();
        }
        Problem problem = new Problem(URI.create("about:blank"), HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                HttpStatus.TOO_MANY_REQUESTS.value(), exception.getMessage(), request.getRequestURI(),
                exception.code(), correlationId, null);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(exception.retryAfterSeconds()))
                .contentType(MediaType.parseMediaType("application/problem+json"))
                .body(problem);
    }
}
