package com.bank.simulator.shared.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

public class ClientIpResolver {

    private final Set<String> trustedProxyAddresses;

    public ClientIpResolver(Set<String> trustedProxyAddresses) {
        this.trustedProxyAddresses = Set.copyOf(trustedProxyAddresses);
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!trustedProxyAddresses.contains(remoteAddress)) {
            return remoteAddress;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddress;
        }
        return forwarded.split(",", 2)[0].trim();
    }
}
