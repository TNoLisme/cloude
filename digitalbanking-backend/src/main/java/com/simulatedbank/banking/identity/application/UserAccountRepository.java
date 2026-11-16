package com.simulatedbank.banking.identity.application;

import java.time.Instant;
import java.util.Optional;

/** Port đọc/ghi state lockout của `app_user` (04 §4.2.1). Không bao giờ trả password_hash ra ngoài interface này. */
public interface UserAccountRepository {

    /**
     * Snapshot tối thiểu phục vụ login. `passwordHash` chỉ tồn tại trong bộ nhớ xử lý, không log/serialize.
     */
    record AppUserSnapshot(long userId, String username, String userType, String passwordHash, String status,
            int failedAttemptCount, Instant lockedUntil) {
        @Override
        public String toString() {
            return "AppUserSnapshot[userId=" + userId + ", status=" + status + "]";
        }
    }

    /** Kết quả tăng bộ đếm nguyên tử (design/02 §9.1: atomic UPDATE … RETURNING chống lost update). */
    record FailedAttemptOutcome(int newCount, boolean lockedNow, Instant lockedUntil) {
    }

    /** Bản ghi `auth_attempt` (04 §4.2.6); không chứa credential/OTP. */
    record AuthAttemptRecord(long userId, String outcome, String reasonCode, String correlationId,
            String clientInfoMaskedJson, String attemptType) {
        public AuthAttemptRecord {
            switch (attemptType == null ? "" : attemptType) {
                case "LOGIN", "PASSCODE", "FIDO", "OTP", "STEP_UP" -> {
                }
                default -> throw new IllegalArgumentException("Unsupported authentication attempt type");
            }
        }
    }

    Optional<AppUserSnapshot> findByUsername(String normalizedUsername);

    Optional<AppUserSnapshot> findById(long userId);

    /**
     * Tăng `failed_attempt_count` +1 và khóa tài khoản khi đạt ngưỡng — trong MỘT statement
     * (row lock của UPDATE). Chỉ áp dụng khi `status='ACTIVE'` và lock cũ đã hết hạn;
     * trả rỗng khi 0 dòng được cập nhật (state đổi giữa chừng → caller re-evaluate).
     */
    Optional<FailedAttemptOutcome> recordFailedAttempt(long userId, int maxFailedAttempts, int lockDurationMinutes);

    /** Mở khóa khi `locked_until <= now()` (design/04 §4.1.3: reset counter + status ACTIVE). True nếu unlock thành công. */
    boolean unlockIfLockExpired(long userId);

    /** Reset primary-password lockout counters without recording a completed login or creating a session. */
    boolean confirmPrimaryAuthentication(long userId);

    /**
     * Path thành công (T14): reset counter/lock, ghi `last_login_at` — chỉ khi user vẫn ACTIVE
     * và không đang bị khóa. False → caller re-evaluate state (fail-closed, không cấp session).
     */
    boolean resetCountersAndMarkLoginSuccess(long userId);

    void recordAuthAttempt(AuthAttemptRecord record);
}
