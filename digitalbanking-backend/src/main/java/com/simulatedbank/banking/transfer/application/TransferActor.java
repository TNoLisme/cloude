package com.simulatedbank.banking.transfer.application;

import java.util.Set;

/** Server-built actor snapshot; never accepted from a request body or query. */
public record TransferActor(long userId, Long customerId, Set<String> authorities,
                            String correlationId, String requestId) {
    public TransferActor {
        if (userId <= 0 || (customerId != null && customerId <= 0)) {
            throw new IllegalArgumentException("Invalid actor identity");
        }
        authorities = Set.copyOf(authorities);
        if (correlationId == null || correlationId.isBlank() || requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Actor requires request context");
        }
    }

    public boolean hasAuthority(String authority) {
        return authorities.contains(authority);
    }

    @Override
    public String toString() {
        return "TransferActor[userId=" + userId + ", customerId=" + customerId + "]";
    }
}