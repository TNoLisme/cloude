package com.simulatedbank.banking.identity.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * UC logout (API-AUTH-06): vô hiệu hóa session hiện tại theo token đã xác thực
 * (`status='LOGGED_OUT'`, `logged_out_at`; 07 §8.1.6). Idempotent ở mức nghiệp vụ:
 * session đã logout/không tồn tại vẫn xử lý an toàn, không lộ thông tin.
 * Audit `AUTH_SESSION_LOGOUT` C3 standalone, sau khi revoke commit (design/01 §5.3).
 */
@Service
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class LogoutService {

    private final SessionRepository sessions;
    private final AuthAuditPort audit;
    private final AuthMetrics metrics;

    public LogoutService(SessionRepository sessions, AuthAuditPort audit, AuthMetrics metrics) {
        this.sessions = sessions;
        this.audit = audit;
        this.metrics = metrics;
    }

    /**
     * @param actorUserId chủ session từ security context (filter đã xác thực).
     * @return true khi session ACTIVE vừa được chuyển sang LOGGED_OUT; false khi idempotent
     *         (đã logout hoặc không còn ACTIVE).
     */
    public boolean logout(String sessionToken, long actorUserId, String correlationId) {
        try {
            String tokenHash = com.simulatedbank.banking.common.crypto.HmacService.sha256Hex(sessionToken);
            boolean revoked = sessions.revokeByTokenHash(tokenHash);
            audit.recordStandalone(AuthAuditPort.AuthAuditEvent
                    .c3User(AuthAuditPort.AuthAction.AUTH_SESSION_LOGOUT, actorUserId, null,
                            revoked ? "SESSION_REVOKED" : "ALREADY_INACTIVE"), correlationId);
            // BE-ID-IMP-13: metric phiên kết thúc — outcome bounded, không token/userId.
            metrics.sessionTerminated(revoked ? "REVOKED" : "IDEMPOTENT");
            return revoked;
        } catch (org.springframework.orm.jpa.JpaSystemException
                | org.springframework.transaction.CannotCreateTransactionException
                | org.springframework.dao.DataAccessResourceFailureException resourceFailure) {
            // DB không khả dụng → 503 retryable (07 §8.1.6), fail-closed.
            throw new com.simulatedbank.banking.common.error.BankingBusinessException(
                    com.simulatedbank.banking.common.error.ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
