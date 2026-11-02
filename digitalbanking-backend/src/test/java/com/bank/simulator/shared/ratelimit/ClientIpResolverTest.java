package com.bank.simulator.shared.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void usesRemoteAddressWhenNoTrustedProxyConfigured() {
        ClientIpResolver resolver = new ClientIpResolver(java.util.Set.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.4");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.4");
    }

    @Test
    void usesForwardedAddressOnlyForTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver(java.util.Set.of("10.0.0.4"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.4");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.4");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }
}
