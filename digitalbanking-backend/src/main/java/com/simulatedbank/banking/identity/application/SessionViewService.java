package com.simulatedbank.banking.identity.application;

import com.simulatedbank.banking.common.config.SessionAuthenticator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * UC `GET /api/v1/auth/session` (API-AUTH-07, 07 §8.1.7): trả actor context hiện tại cho SPA
 * sau refresh, kèm `csrfToken` cấp lại.
 *
 * <p><b>Actor propagation (BE-ID-IMP-04):</b> toàn bộ actor (userId, roles, privileges) chỉ
 * được lấy từ snapshot phiên do `SessionAuthenticationFilter` tạo qua SPI thật
 * (`IdentitySessionAuthenticator` → `AuthorityReader`); KHÔNG đọc bất kỳ identifier,
 * role hay scope nào từ query/body/header của client. Token raw phiên chỉ dùng để trỏ tới
 * đúng dòng session đã được filter xác thực.</p>
 *
 * <p><b>CSRF rotation (DEC-ID-004):</b> DB chỉ lưu SHA-256 hash nên "cấp lại csrfToken" là
 * cấp token raw MỚI + cập nhật hash trong MỘT statement điều kiện fail-closed (không hồi
 * sinh phiên hết hạn/thu hồi).</p>
 *
 * <p>Fail-closed: user không ACTIVE, session không còn ACTIVE/hết hạn, hay lỗi DB (503)
 * đều KHÔNG trả dữ liệu actor.</p>
 */
@Service
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class SessionViewService {

    /** Snapshot trả về adapter/in; `rawCsrfToken` chỉ tồn tại một lần, không log/persist. */
    public record SessionView(long userId, String username, String userType, List<String> roles,
            Set<String> privileges, String customerId, Instant idleExpiresAt, Instant absoluteExpiresAt,
            String rawCsrfToken) {
    }

    private final SessionRepository sessions;
    private final UserAccountRepository users;

    public SessionViewService(SessionRepository sessions, UserAccountRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    public SessionView view(SessionAuthenticator.Session snapshot, String rawSessionToken) {
        long actorUserId = Long.parseLong(snapshot.userId());
        try {
            // Fail-closed: owner không còn ACTIVE → không trả dữ liệu actor.
            var user = users.findById(actorUserId)
                    .filter(active -> "ACTIVE".equals(active.status()))
                    .orElseThrow(SessionViewService::sessionExpired);
            // Actor chỉ từ snapshot; roles/privileges đã được SPI nạp từ RBAC seed theo request.
            List<String> roles = snapshot.authorities().stream().filter(a -> a.startsWith("ROLE_"))
                    .map(a -> a.substring("ROLE_".length())).sorted().toList();
            Set<String> privileges = snapshot.authorities().stream()
                    .filter(a -> !a.startsWith("ROLE_")).collect(java.util.stream.Collectors.toUnmodifiableSet());
            String rawCsrfToken = freshToken();
            var rotated = sessions.rotateCsrfToken(sha256(rawSessionToken), sha256(rawCsrfToken))
                    .orElseThrow(SessionViewService::sessionExpired);
            return new SessionView(actorUserId, user.username(), user.userType(), roles, privileges,
                    snapshot.customerId(), rotated.idleExpiresAt(), rotated.absoluteExpiresAt(),
                    rawCsrfToken);
        } catch (org.springframework.dao.DataAccessException
                | org.springframework.transaction.CannotCreateTransactionException storageFailure) {
            // Fail-closed: lỗi DB không cấp dữ liệu actor, map 503 như IMP-03 (07 §8.1.7).
            throw new com.simulatedbank.banking.common.error.BankingBusinessException(
                    com.simulatedbank.banking.common.error.ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static com.simulatedbank.banking.common.error.BankingBusinessException sessionExpired() {
        return new com.simulatedbank.banking.common.error.BankingBusinessException(
                com.simulatedbank.banking.common.error.ErrorCode.AUTH_SESSION_EXPIRED);
    }

    private static String sha256(String value) {
        return com.simulatedbank.banking.common.crypto.HmacService.sha256Hex(value);
    }

    /** 32 bytes SecureRandom Base64Url — cùng định dạng token login (design/04 §4.3). */
    private static String freshToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
