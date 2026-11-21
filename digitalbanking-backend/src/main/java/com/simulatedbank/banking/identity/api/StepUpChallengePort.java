package com.simulatedbank.banking.identity.api;

import java.time.Instant;
import java.util.Optional;

/**
 * Hợp đồng cross-module cho challenge step-up/OTP (BE-ID-IMP-05).
 * Nguồn: `docs-common/design/05. Modules contract.md` §5.2 — `identity.api.StepUpChallengePort`;
 * `07. API contracts.md` §6 (step-up); `design/02. consistency-concurrency.md` §5.1–§5.2 (T0/T1);
 * `04. Database design & analysis.md` §4.2.5 (`auth_challenge`).
 *
 * <p>Consumer: Transfer (T0 `requestChallenge`, T1 `consumeVerified`), Admin (maker/checker
 * SENSITIVE_ACTION), identity adapter in (verify). Transfer/Admin KHÔNG được đụng bảng
 * `auth_challenge` trực tiếp — mọi thao tác qua port này.</p>
 *
 * <p>Semantics transaction (design/05 §3.2):</p>
 * <ul>
 *   <li>{@link #requestChallenge} — <b>OWN/T0</b>: transaction ngắn riêng; KHÔNG tạo
 *       transfer/idempotency_record/ledger. Identity dispatch sau commit qua OTP gateway
 *       và chỉ trả challenge sau khi ACK/SENT đã được xác nhận.</li>
 *   <li>{@link #verifyChallenge} — <b>OWN/T0</b>: lock row challenge, kiểm user/purpose/
 *       expiry/attempt, cập nhật atomic; KHÔNG giữ lock chờ user nhập OTP.</li>
 *   <li>{@link #consumeVerified} — <b>JOIN/T1</b>: phải được gọi trong transaction nghiệp vụ
 *       hiện có (MANDATORY — fail fast khi không có TX, design/05 §3.2); consume tối đa một
 *       lần bằng conditional UPDATE; rollback của caller hoàn lại `consumed_at`
 *       (challenge về VERIFIED, design/02 §5.1/§5.2).</li>
 * </ul>
 *
 * <p>Binding (điểm chống reuse — 07 §6.2, 04 §4.2.5): challenge step-up gắn `request_hash`
 * (SHA-256 JCS của payload chuẩn hóa) + `idempotency_key`; challenge SENSITIVE_ACTION gắn
 * hash decision payload + key binding (approvalId); challenge LOGIN_NEW_DEVICE không binding.
 * Consume sai binding → `409 STEP_UP_CHALLENGE_MISMATCH`; sai user → `404
 * AUTH_CHALLENGE_NOT_FOUND` (anti-enumeration).</p>
 */
public interface StepUpChallengePort {

    /** Purpose theo CHECK constraint `ck_auth_challenge_purpose` (04 §4.2.5); không có giá trị khác. */
    enum ChallengePurpose {
        LOGIN_NEW_DEVICE, TRANSFER_STEP_UP, SENSITIVE_ACTION
    }

    /**
     * Yêu cầu tạo challenge (T0).
     *
     * @param purpose       purpose bắt buộc
     * @param userId        user sở hữu challenge (server suy luận từ actor, không tin client)
     * @param requestHash   SHA-256 hex 64 ký tự của payload chuẩn hóa — bắt buộc với
     *                      TRANSFER_STEP_UP/SENSITIVE_ACTION, phải {@code null} với LOGIN_NEW_DEVICE
     * @param bindingKey    key binding (idempotency_key cho transfer, approvalId cho sensitive) —
     *                      quy tắc như requestHash
     * @param correlationId correlation ID hiện hành (NOT NULL trong DB)
     */
    record ChallengeRequest(ChallengePurpose purpose, long userId, String requestHash,
            String bindingKey, String correlationId) {
        @Override public String toString() { return "ChallengeRequest[redacted]"; }
    }

    /**
     * Challenge đã tạo. KHÔNG chứa OTP/hash — thông tin hiển thị an toàn cho 428/202
     * (07 §6.3). `maskedDestination` (số điện thoại/email mask) thuộc BE-ID-IMP-06 khi
     * delivery resolve kênh qua notification.
     */
    record ChallengeView(String challengeRef, ChallengePurpose purpose, String method, String channel,
            Instant expiresAt, int maxAttempts, int remainingAttempts) {
    }

    /**
     * Lệnh verify (API-AUTH-04/05, 07 §6.4). `otp` là plaintext do client nhập; caller phải
     * để service wipe (char[]), không log. `expectedPurpose` khác null phải khớp purpose
     * của challenge; null chỉ chọn TRANSFER_STEP_UP/SENSITIVE_ACTION cho endpoint step-up
     * đã xác thực, không cho phép LOGIN_NEW_DEVICE. Sai purpose/method → `409
     * STEP_UP_CHALLENGE_MISMATCH`; implementation OTP chỉ xác thực challenge method OTP.
     */
    record VerifyCommand(String challengeRef, long userId, ChallengePurpose expectedPurpose,
            char[] otp, String correlationId) {
        @Override public String toString() { return "VerifyCommand[redacted]"; }
    }

    record VerifiedChallenge(String challengeRef, ChallengePurpose purpose, Instant verifiedAt,
            Instant expiresAt) {
    }

    /**
     * Lệnh consume (T1 của transfer / approval transaction của admin). `requestHash`/`bindingKey`
     * phải truyền đúng giá trị đã bind lúc issue (null khi challenge không có binding);
     * lệch → `409 STEP_UP_CHALLENGE_MISMATCH`, đã consume → `409 INVALID_STATE_TRANSITION`,
     * hết hạn → `410 AUTH_CHALLENGE_EXPIRED`, chưa verify → `428 STEP_UP_REQUIRED`
     * (07 §6.6; design/02 §5.2).
     */
    record ConsumeCommand(String challengeRef, long userId, ChallengePurpose purpose,
            String requestHash, String bindingKey) {
        @Override public String toString() { return "ConsumeCommand[redacted]"; }
    }

    record ConsumedChallenge(long challengeId, String challengeRef, ChallengePurpose purpose,
            Instant consumedAt) {
    }

    /** OWN/T0 — tạo challenge theo policy (TTL, max attempts từ `app.security.otp`). */
    ChallengeView requestChallenge(ChallengeRequest request);

    /** Read a verified, unconsumed Transfer proof from primary. Revalidate it when consuming. */
    Optional<VerifiedChallenge> findVerifiedBindingIfPresent(TransferBinding binding);

    /** Trusted Transfer actor and exact request binding. */
    record TransferBinding(long userId, long customerId, String requestHash, String bindingKey) {
        @Override public String toString() { return "TransferBinding[redacted]"; }
    }

    /** Issue for a server-resolved user/customer binding. */
    ChallengeView requestTransferChallenge(TransferBinding binding, String correlationId);

    /** JOIN/T1 and revalidate the customer binding and the locked challenge. */
    ConsumedChallenge consumeTransferChallenge(TransferBinding binding, String challengeRef);

    /** OWN/T0 — verify credential; rejection ném `BankingBusinessException` theo catalog `07`. */
    VerifiedChallenge verifyChallenge(VerifyCommand command);

    /**
     * JOIN/T1 — consume atomic trong transaction caller (MANDATORY). Trả về challenge đã
     * consume; mọi rejection là business outcome theo `07` và buộc caller rollback T1
     * (design/02 §5.2 bước 3).
     */
    ConsumedChallenge consumeVerified(ConsumeCommand command);
}
