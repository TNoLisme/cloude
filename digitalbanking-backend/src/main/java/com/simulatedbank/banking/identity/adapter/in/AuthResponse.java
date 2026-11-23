package com.simulatedbank.banking.identity.adapter.in;

import java.time.Instant;
import java.util.List;

/**
 * AuthResponse (07 §7.2.3). Customer identity is resolved server-side (DEC-INT-008).
 * `csrfToken` is returned on authenticated responses, never diagnostic output.
 */
public record AuthResponse(UserSummary user, SessionInfo session, String correlationId) {

    public record UserSummary(String userId, String userType, String displayName, List<String> roles,
            List<String> privileges, String customerId) {
    }

    public record SessionInfo(Instant idleExpiresAt, Instant absoluteExpiresAt, String csrfToken,
            boolean deviceTrusted) {
        @Override
        public String toString() {
            return "SessionInfo[redacted]";
        }
    }
}
