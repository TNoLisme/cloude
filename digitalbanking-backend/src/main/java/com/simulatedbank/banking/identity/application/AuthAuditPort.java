package com.simulatedbank.banking.identity.application;

import org.springframework.lang.Nullable;

/**
 * Port audit xác thực theo `design/01. audit-traceability.md` §5.2/§5.3:
 * C2 (`recordInTransaction`, cùng TX nghiệp vụ, fail-closed) và C3 (`recordStandalone`, best-effort).
 *
 * <p>Tên action khớp catalog: `AUTH_LOGIN_SUCCESS`, `AUTH_LOGIN_FAILED`, `AUTH_ACCOUNT_LOCKED`,
 * `AUTH_SESSION_LOGOUT`. Hiện chưa có module audit thật (BE-ID-IMP-13): adapter mặc định ghi
 * security log + metric; hợp đồng này giữ nguyên semantics cho integration sau.</p>
 */
public interface AuthAuditPort {

    enum AuthAction {
        AUTH_LOGIN_SUCCESS,
        AUTH_LOGIN_FAILED,
        AUTH_ACCOUNT_LOCKED,
        AUTH_SESSION_LOGOUT,
        // BE-ID-IMP-05 — challenge lifecycle theo design/01 §5.3 catalog (tên khớp catalog):
        AUTH_CHALLENGE_CREATED,
        AUTH_STEP_UP_VERIFIED,
        AUTH_STEP_UP_FAILED,
        AUTH_CHALLENGE_LOCKED
        // AUTH_CHALLENGE_RESENT (C2) thuộc luồng resend BE-ID-IMP-06 — thêm khi có flow.
    }

    enum AuditClass {
        /** C2 — MANDATORY, cùng TX; fail → rollback TX nghiệp vụ. */
        C2,
        /** C3 — best-effort, transaction riêng; fail không đổi response. */
        C3
    }

    /** Sự kiện audit auth; mọi field phải an toàn khi log (không password/hash/token). */
    record AuthAuditEvent(AuthAction action, AuditClass auditClass, @Nullable Long actorUserId,
            @Nullable String anonymousRef, @Nullable String entityId, @Nullable String reasonCode,
            @Nullable String detail) {

        public static AuthAuditEvent c2(AuthAction action, long actorUserId, @Nullable String reasonCode,
                @Nullable String detail) {
            return new AuthAuditEvent(action, AuditClass.C2, actorUserId, null,
                    "APP_USER:" + actorUserId, reasonCode, detail);
        }

        public static AuthAuditEvent c3User(AuthAction action, long actorUserId, @Nullable String reasonCode,
                @Nullable String detail) {
            return new AuthAuditEvent(action, AuditClass.C3, actorUserId, null,
                    "APP_USER:" + actorUserId, reasonCode, detail);
        }

        /** Unknown user: design/01 §A.3 — actor anonymous, entity `anon:<sha256(username)>`. */
        public static AuthAuditEvent c3Anonymous(AuthAction action, String anonymousRef,
                @Nullable String reasonCode, @Nullable String detail) {
            return new AuthAuditEvent(action, AuditClass.C3, null, anonymousRef, null, reasonCode, detail);
        }

        /** Challenge C2 (design/01 §5.3: AUTH_CHALLENGE_CREATED/AUTH_STEP_UP_VERIFIED — cùng TX, fail-closed). */
        public static AuthAuditEvent c2Challenge(AuthAction action, long actorUserId, String challengeRef,
                @Nullable String reasonCode, @Nullable String detail) {
            return new AuthAuditEvent(action, AuditClass.C2, actorUserId, null,
                    "AUTH_CHALLENGE:" + challengeRef, reasonCode, detail);
        }

        /** Challenge C3 (design/01 §5.3: AUTH_STEP_UP_FAILED/AUTH_CHALLENGE_LOCKED — standalone). */
        public static AuthAuditEvent c3Challenge(AuthAction action, long actorUserId, String challengeRef,
                @Nullable String reasonCode, @Nullable String detail) {
            return new AuthAuditEvent(action, AuditClass.C3, actorUserId, null,
                    "AUTH_CHALLENGE:" + challengeRef, reasonCode, detail);
        }
    }

    /** C2: tham gia TX hiện tại (MANDATORY); lỗi phải lan truyền để rollback nghiệp vụ. */
    void recordInTransaction(AuthAuditEvent event, String correlationId);

    /** C3: standalone best-effort; lỗi bị nuốt + log lỗi (không đổi response nghiệp vụ). */
    void recordStandalone(AuthAuditEvent event, String correlationId);
}
