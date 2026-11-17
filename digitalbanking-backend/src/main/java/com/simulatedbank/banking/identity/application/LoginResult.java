package com.simulatedbank.banking.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Kết quả login thành công. `rawSessionToken`/`rawCsrfToken` chỉ tồn tại ở đây để controller
 * set cookie/trả `AuthResponse` một lần duy nhất (design/04 §4.3); không log, không persist raw.
 */
public record LoginResult(long userId, String username, String userType, List<String> roles,
        Set<String> privileges, String rawSessionToken, String rawCsrfToken, Instant idleExpiresAt,
        Instant absoluteExpiresAt) {

    @Override
    public String toString() {
        return "LoginResult[redacted]";
    }
}
