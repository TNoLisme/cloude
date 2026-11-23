package com.simulatedbank.banking.identity.application;

import com.simulatedbank.banking.identity.api.StepUpChallengePort.VerifiedChallenge;
import java.time.Instant;
import java.util.Optional;

/**
 * Port persistence cho `banking.auth_challenge` (04 §4.2.5; BE-ID-IMP-05/07). Mutations dùng
 * conditional UPDATE … RETURNING; consume giữ row lock trước khi kiểm expiry bằng thời gian
 * thực của DB, trong cùng transaction, chống lost update/double-consume (T-STEP-01).
 *
 * <p>Semantics transaction: method KHÔNG mở/commit transaction riêng — chúng tham gia
 * transaction hiện hành (REQUIRED) hoặc transaction caller (JOIN/T1 cho consume). Ranh giới
 * OWN/JOIN do `OtpChallengeService` kiểm soát. Verify tính hash ngoài lock, rồi khóa dòng
 * và cập nhật có điều kiện theo generation và thời điểm hiện tại.</p>
 */
public interface ChallengeRepository {

    /** Command insert (status khởi tạo `CREATED`, `attempt_count=0` — 04 §4.2.5 default). */
    record NewChallenge(long userId, String purpose, String method, String channel, String otpHash,
            String requestHash, String idempotencyKey, Instant expiresAt, int maxAttempts,
            String correlationId, Long deviceId) {
    }

    /**
     * Snapshot row. `otpHash` đi kèm theo precedent `AppUserSnapshot.passwordHash` — chỉ dùng
     * nội bộ identity service để verify; KHÔNG bao giờ log/serialize ra API/audit.
     */
    record ChallengeSnapshot(long challengeId, String challengeRef, long userId, String purpose,
            String method, String channel, String status, int attemptCount, int maxAttempts,
            Instant expiresAt, Instant createdAt, Instant verifiedAt, Instant consumedAt,
            String requestHash, String idempotencyKey, String otpHash, Long deviceId) {
        @Override
        public String toString() {
            return "ChallengeSnapshot[redacted]";
        }
    }

    /** Kết quả bump attempt sai: attempt mới + status sau UPDATE (LOCKED khi đạt ngưỡng). */
    record FailedAttemptOutcome(int attemptCount, String status) {
    }

    /** Kết quả consume thành công (challenge_id, consumed_at từ RETURNING). */
    record ConsumeResult(long challengeId, Instant consumedAt) {
    }

    /** Predicate consume — điều kiện đã duyệt trong design/04 §4.4 + design/02 §5.2 bước 3. */
    record ConsumePredicate(String challengeRef, long userId, String purpose, String requestHash,
            String idempotencyKey) {
    }

    record LockedResend(ChallengeSnapshot challenge, int resendCount, Instant lastDispatchAt, Instant now) {
    }

    Optional<LockedResend> lockForResend(String challengeRef);

    ChallengeSnapshot rotateOtp(long challengeId, String otpHash, Instant now, Instant expiresAt);

    void cancelUnacknowledged(long challengeId, String expectedOtpHash);

    /** INSERT … RETURNING; vi phạm partial unique active step-up → caller xử lý reissue. */
    ChallengeSnapshot insert(NewChallenge command);

    /** Đọc thường (không lock) — dùng pre-check verify và chẩn đoán rejection. */
    Optional<ChallengeSnapshot> findByRefAndUser(String challengeRef, long userId);

    /** Public login challenge lookup; the random reference is not an authenticated credential. */
    Optional<ChallengeSnapshot> findByRef(String challengeRef);

    /** Challenge step-up còn active theo (idempotency_key, request_hash) — partial unique. */
    Optional<ChallengeSnapshot> findActiveByIdempotencyKey(String idempotencyKey, String requestHash);

    void expireTransferBindingIfDue(long userId, String key, String hash);

    Optional<VerifiedChallenge> findVerifiedTransferBinding(
            long userId, String key, String hash);

    /**
     * Expiry enforcement lazy: `status → EXPIRED` cho CREATED/SENT/VERIFIED-chưa-consume khi
     * `expires_at <= now()`. True khi có chuyển trạng thái; false khi đã terminal/chưa đến hạn.
     */
    boolean expireIfDue(long challengeId);

    /**
     * Tăng attempt sai MỘT statement: `attempt_count+1`, đạt ngưỡng → `status='LOCKED'`.
     * Chỉ áp dụng cho status issued-active và chưa hết hạn; 0 dòng → empty (race fail-closed).
     * CHECK `attempt_count <= max_attempts` không bao giờ bị vượt.
     */
    Optional<FailedAttemptOutcome> recordFailedVerifyAttempt(long challengeId, int maxAttempts, String expectedOtpHash);

    /**
     * `SENT → VERIFIED + verified_at`, MỘT statement điều kiện (status, attempt chưa đổi,
     * attempt+1 ≤ max, chưa hết hạn). Đúng OTP cũng tăng attempt_count (design/02 §5.1 bước 6).
     * False → race, caller đọc lại và map fail-closed.
     */
    boolean markVerified(long challengeId, int expectedAttemptCount, String expectedOtpHash);

    /** Accepted delivery only: hash-fenced CREATED → SENT; no transition after expiry. */
    boolean markSent(long challengeId, String expectedOtpHash);

    /** Bounded SKIP LOCKED expiry sweep; never changes consumed or terminal challenges. */
    int expireDue(int batchSize);

    /**
     * Consume atomic — row lock rồi conditional UPDATE trong cùng JOIN/T1 transaction:
     * khớp ref + user + purpose + binding nguyên vẹn + `status='VERIFIED'` +
     * `consumed_at IS NULL` + expiry theo thời gian thực sau lock. Hai committed consume
     * đồng thời: đúng một thắng. Rollback caller hoàn lại consumed_at.
     */
    Optional<ConsumeResult> consume(ConsumePredicate predicate);
}
