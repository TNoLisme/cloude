package com.simulatedbank.banking.common.config;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Implemented by identity: validate token, active user/session, expiry and permission snapshot. */
@FunctionalInterface
public interface SessionAuthenticator {
    Optional<Session> authenticate(String opaqueToken);

    /** Internal security snapshot, never an API DTO. No raw session/CSRF token is retained. */
    record Session(String userId, String customerId, Set<String> authorities, String csrfTokenHash) {
        public Session {
            Objects.requireNonNull(userId, "userId is required");
            authorities = Set.copyOf(authorities);
            if (!userId.matches("[1-9][0-9]{0,18}")
                    || (customerId != null && !customerId.matches("[1-9][0-9]{0,18}"))
                    || csrfTokenHash == null || !csrfTokenHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid authenticated session snapshot");
            }
        }

        @Override
        public String toString() {
            return "Session[redacted]";
        }
    }
}
