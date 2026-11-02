package com.bank.simulator.identity.domain;

import java.util.Set;
import java.util.UUID;

public record AuthenticatedActor(UUID userId, Set<String> roles) {

    public AuthenticatedActor {
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
