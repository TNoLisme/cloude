package com.simulatedbank.banking.identity.application;

import java.time.Instant;
import java.util.Optional;

/** Port vòng đời `session` (04 §4.2.3). DB chỉ lưu SHA-256 hash của token/CSRF, không lưu raw. */
public interface SessionRepository {

    record CreateSessionCommand(String sessionTokenHash, String csrfTokenHash, long userId, Instant idleExpiresAt,
            Instant absoluteExpiresAt, Long deviceId) {
    }

    record ActiveSession(long sessionId, long userId, String csrfTokenHash, Instant idleExpiresAt,
            Instant absoluteExpiresAt) {
    }

    long createSession(CreateSessionCommand command);

    /**
     * Thu hồi session theo token hash: `status='LOGGED_OUT'`, `logged_out_at` (07 §8.1.6).
     * True khi có chuyển trạng thái ACTIVE → LOGGED_OUT; false khi không tìm thấy hoặc đã logout (idempotent).
     */
    boolean revokeByTokenHash(String sessionTokenHash);

    /**
     * Xác thực + gia hạn idle trong MỘT statement (design/04 §4.3: idle_expires_at =
     * LEAST(now()+idle, absolute)); chỉ trả session khi ACTIVE, chưa hết hạn idle/absolute.
     */
    Optional<ActiveSession> validateAndRefresh(String sessionTokenHash, long idleSeconds);

    /**
     * BE-ID-IMP-04 (API-AUTH-07, 07 §8.1.7 "cấp lại csrfToken"): xoay CSRF token của phiên —
     * DB chỉ lưu SHA-256 hash nên "cấp lại" là cấp token raw MỚI và cập nhật hash trong MỘT
     * statement điều kiện (DEC-ID-004). Fail-closed: chỉ UPDATE khi `status='ACTIVE'` và
     * chưa hết hạn idle/absolute; 0 dòng → rỗng (caller trả `401 AUTH_SESSION_EXPIRED`,
     * không hồi sinh phiên). `ActiveSession.csrfTokenHash` là hash MỚI, không bao giờ lộ raw.
     */
    Optional<ActiveSession> rotateCsrfToken(String sessionTokenHash, String newCsrfTokenHash);
}
