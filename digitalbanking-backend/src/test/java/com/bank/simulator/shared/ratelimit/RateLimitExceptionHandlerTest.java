package com.bank.simulator.shared.ratelimit;

import com.bank.simulator.shared.api.Problem;
import com.bank.simulator.shared.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitExceptionHandlerTest {

    @Test
    void mapsRateLimitToProblemAndRetryAfter() {
        UUID correlationId = UUID.randomUUID();
        RateLimitExceptionHandler handler = new RateLimitExceptionHandler();
        HttpServletRequest request = new jakarta.servlet.http.HttpServletRequestWrapper(
                new org.springframework.mock.web.MockHttpServletRequest()) {
            @Override public Object getAttribute(String name) {
                if (CorrelationIdFilter.REQUEST_ATTRIBUTE.equals(name)) return correlationId;
                return super.getAttribute(name);
            }
            @Override public String getRequestURI() { return "/api/v1/auth/login"; }
        };

        ResponseEntity<Problem> response = handler.handle(
                new RateLimitInterceptor.RateLimitedException(7), request);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("7");
        assertThat(response.getBody().code()).isEqualTo("RATE_LIMITED");
        assertThat(response.getBody().correlationId()).isEqualTo(correlationId);
    }
}
