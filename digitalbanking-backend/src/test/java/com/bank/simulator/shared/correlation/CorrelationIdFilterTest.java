package com.bank.simulator.shared.correlation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void acceptsValidCorrelationIdAndClearsMdc() throws Exception {
        UUID correlationId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, correlationId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo(correlationId.toString());
        assertThat(request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)).isNull();
        assertThat(org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void replacesInvalidCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isNotEqualTo("invalid");
        assertThat(UUID.fromString(response.getHeader(CorrelationIdFilter.HEADER_NAME))).isNotNull();
    }
}
