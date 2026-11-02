package com.bank.simulator.identity.web;

import com.bank.simulator.identity.infrastructure.security.CsrfTokenService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class CsrfHeaderFilterTest {

    private final CsrfTokenService tokenService = new CsrfTokenService("12345678901234567890123456789012");
    private final CsrfHeaderFilter filter = new CsrfHeaderFilter(tokenService);

    @Test
    void allowsValidCsrfTokenOnRefreshRequest() throws Exception {
        MockHttpServletRequest request = post("/api/v1/auth/refresh");
        request.addHeader("X-CSRF-Token", tokenService.issue());
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsMissingOrInvalidCsrfToken() throws Exception {
        MockHttpServletRequest request = post("/api/v1/auth/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void currentlyAllowsSameTokenReplayOnRefresh() throws Exception {
        String token = tokenService.issue();
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = post("/api/v1/auth/refresh");
            request.addHeader("X-CSRF-Token", token);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }
    }

    private MockHttpServletRequest post(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContextPath("/api/v1");
        return request;
    }
}
