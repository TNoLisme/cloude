package com.simulatedbank.banking.identity.application;

import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.domain.ChallengeState;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BE-ID-IMP-05 — OTP/challenge state machine (`auth_challenge`): issue / verify / consume /
 * expire / lock / binding. Implement {@link StepUpChallengePort} theo design/05 §5.2.
 *
 * <p>State machine (04 §4.2.5; domain {@link ChallengeState}): `CREATED → SENT → VERIFIED`;
 * sai vượt ngưỡng → `LOCKED`; hết hạn → `EXPIRED`; consume = cột `consumed_at` (KHÔNG có state
 * CONSUMED — OD-ID-06). Mọi mutation là conditional UPDATE/SELECT…FOR UPDATE trong
 * {@link JdbcChallengeRepository}; service chỉ map kết quả, không tự ghi đè state.</p>
 *
 * <p>Transaction boundaries (design/05 §3.2/§5.2): issue + verify là OWN (TransactionTemplate
 * ngắn, BE-CON-16/27); consumeVerified là <b>JOIN/T1</b> — {@code @Transactional(MANDATORY)},
 * fail fast khi caller chưa mở transaction; rollback caller hoàn lại `consumed_at`
 * (design/02 §5.1/§5.2). OTP delivery runs outside transactions after the OWN commit.</p>
 */
@Service
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class OtpChallengeService implements StepUpChallengePort {

    /** `request_hash` CHAR(64) — SHA-256 hex do caller (transfer/admin) sinh. */
    private static final Pattern REQUEST_HASH = Pattern.compile("[0-9a-f]{64}");
    /** `idempotency_key` VARCHAR(255), độ dài 1–255 (07 §5 idempotency key contract). */
    private static final int BINDING_KEY_MAX = 255;

    static final String METHOD_OTP = "OTP";
    /** MVP SMS; trusted destination routing is the configured Notification adapter's responsibility. */
    static final String CHANNEL_DEFAULT = "SMS";

    private final ChallengeRepository challenges;
    private final AuthAuditPort audit;
    private final PasswordHasher hasher;
    private final OtpCodeGenerator otpGenerator;
    private final Clock clock;
    private final int otpTtlSeconds;
    private final int maxAttempts;
    private final TransactionTemplate ownTransaction;
    private final OtpDispatchService dispatch;
    private final LoginSessionService loginSessions;
    private final AuthMetrics metrics;

    private final SessionCustomerBinding customers;

    public OtpChallengeService(ChallengeRepository challenges, AuthAuditPort audit, PasswordHasher hasher,
            OtpCodeGenerator otpGenerator, Clock clock, AppProperties properties,
            PlatformTransactionManager transactionManager, OtpDispatchService dispatch,
            LoginSessionService loginSessions, AuthMetrics metrics, SessionCustomerBinding customers) {
        this.challenges = challenges;
        this.audit = audit;
        this.hasher = hasher;
        this.otpGenerator = otpGenerator;
        this.clock = clock;
        this.otpTtlSeconds = properties.security().otp().ttlSeconds();
        this.maxAttempts = properties.security().otp().maxVerifyAttempts();
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.dispatch = dispatch;
        this.loginSessions = loginSessions;
        this.metrics = metrics;
        this.customers = customers;
    }

    // ------------------------------------------------------------------ issue (OWN/T0)

    @Override
    public ChallengeView requestChallenge(ChallengeRequest request) {
        return issueChallenge(request, null);
    }

    @Override
    public ChallengeView requestTransferChallenge(TransferBinding binding, String correlationId) {
        requireTransferCustomer(binding);
        return requestChallenge(new ChallengeRequest(ChallengePurpose.TRANSFER_STEP_UP, binding.userId(),
                binding.requestHash(), binding.bindingKey(), correlationId));
    }

    @Override
    public Optional<VerifiedChallenge> findVerifiedBindingIfPresent(TransferBinding binding) {
        requireTransferCustomer(binding);
        return challenges.findVerifiedTransferBinding(binding.userId(), binding.bindingKey(), binding.requestHash());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ConsumedChallenge consumeTransferChallenge(TransferBinding binding, String challengeRef) {
        requireTransferCustomer(binding);
        return consumeVerified(new ConsumeCommand(challengeRef, binding.userId(), ChallengePurpose.TRANSFER_STEP_UP,
                binding.requestHash(), binding.bindingKey()));
    }

    private void requireTransferCustomer(TransferBinding binding) {
        if (binding == null || binding.userId() <= 0 || binding.customerId() <= 0
                || binding.requestHash() == null || !REQUEST_HASH.matcher(binding.requestHash()).matches()
                || binding.bindingKey() == null || !binding.bindingKey().matches("[A-Za-z0-9._:~\\-]{1,255}")
                || !Long.toString(binding.customerId()).equals(customers.resolve(binding.userId(), "CUSTOMER"))) {
            throw mismatch();
        }
    }

    /** Password-authenticated login attempt; device metadata is bound before delivery. */
    public ChallengeView requestLoginChallenge(long userId, Long deviceId, String correlationId) {
        return issueChallenge(new ChallengeRequest(ChallengePurpose.LOGIN_NEW_DEVICE, userId,
                null, null, correlationId), deviceId);
    }

    private ChallengeView issueChallenge(ChallengeRequest request, Long deviceId) {
        validateRequest(request);
        char[] otp = otpGenerator.generate();
        try {
            String otpHash = hasher.hash(otp);
            return issueInOwnTransaction(request, otpHash, otp, deviceId);
        } catch (JpaSystemException | org.springframework.transaction.CannotCreateTransactionException
                | org.springframework.dao.DataAccessResourceFailureException resourceFailure) {
            throw unavailable();
        } finally {
            java.util.Arrays.fill(otp, '\0');
        }
    }

    private ChallengeView issueInOwnTransaction(ChallengeRequest request, String otpHash, char[] otp,
            Long deviceId) {
        ChallengeRepository.ChallengeSnapshot snapshot;
        try {
            snapshot = ownTransaction.execute(status -> {
                if (request.purpose() == ChallengePurpose.TRANSFER_STEP_UP) {
                    challenges.expireTransferBindingIfDue(request.userId(), request.bindingKey(), request.requestHash());
                }
                var created = challenges.insert(new ChallengeRepository.NewChallenge(
                        request.userId(), request.purpose().name(), METHOD_OTP, CHANNEL_DEFAULT,
                        otpHash, request.requestHash(), request.bindingKey(),
                        clock.instant().plusSeconds(otpTtlSeconds), maxAttempts, request.correlationId(), deviceId));
                // Mandatory audit belongs to the INSERT transaction, never after its commit.
                audit.recordInTransaction(AuthAuditPort.AuthAuditEvent
                        .c2Challenge(AuthAuditPort.AuthAction.AUTH_CHALLENGE_CREATED, request.userId(),
                                created.challengeRef(), null, null)
                        .withChallenge(created, "CREATED", 0), request.correlationId());
                return created;
            });
        } catch (DuplicateKeyException duplicate) {
            // The failed INSERT transaction has rolled back. Never reuse this request's
            // newly generated OTP for an existing challenge's different verifier.
            var existing = challenges.findActiveByIdempotencyKey(request.bindingKey(), request.requestHash())
                    .orElseThrow(() -> duplicate);
            if (existing.userId() != request.userId()
                    || !existing.purpose().equals(request.purpose().name())) {
                throw mismatch();
            }
            if (!existing.expiresAt().isAfter(clock.instant())) {
                challenges.expireIfDue(existing.challengeId());
                throw new BankingBusinessException(ErrorCode.AUTH_CHALLENGE_EXPIRED);
            }
            if ("CREATED".equals(existing.status())) {
                // Pending/failed/ambiguous delivery: no durable secret exists for replay.
                // Do not send another code or report successful delivery.
                throw new BankingBusinessException(ErrorCode.OTP_DISPATCH_FAILED);
            }
            return toView(existing);
        }
        if (snapshot == null) {
            throw new IllegalStateException("Challenge issuance returned no snapshot");
        }
        // OWN transaction has committed. Dispatcher suspends any caller transaction.
        // The only plaintext handoff is this borrowed array, wiped by requestChallenge.
        dispatch.deliver(snapshot, otp, request.correlationId());
        return toView(snapshot);
    }

    private void validateRequest(ChallengeRequest request) {
        if (request == null || request.purpose() == null) {
            throw validation("purpose là bắt buộc.");
        }
        if (request.userId() <= 0) {
            throw validation("userId không hợp lệ.");
        }
        if (request.correlationId() == null || request.correlationId().isBlank()
                || request.correlationId().length() > 64) {
            throw validation("correlationId không hợp lệ.");
        }
        boolean bindingRequired = request.purpose() == ChallengePurpose.TRANSFER_STEP_UP
                || request.purpose() == ChallengePurpose.SENSITIVE_ACTION;
        if (bindingRequired) {
            if (request.requestHash() == null || !REQUEST_HASH.matcher(request.requestHash()).matches()) {
                throw validation("requestHash (SHA-256 hex 64) là bắt buộc cho purpose "
                        + request.purpose() + ".");
            }
            if (request.bindingKey() == null || request.bindingKey().isBlank()
                    || request.bindingKey().length() > BINDING_KEY_MAX) {
                throw validation("bindingKey (≤255) là bắt buộc cho purpose " + request.purpose() + ".");
            }
        } else if (request.requestHash() != null || request.bindingKey() != null) {
            // LOGIN_NEW_DEVICE không có binding (04 §4.2.5: chỉ TRANSFER_STEP_UP bắt buộc) —
            // từ chối thay vì âm thầm bỏ qua để không tạo challenge sai binding.
            throw validation("LOGIN_NEW_DEVICE không chấp nhận binding.");
        }
    }

    // ------------------------------------------------------------------ verify (OWN/T0)

    @Override
    public VerifiedChallenge verifyChallenge(VerifyCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.otp() == null || command.otp().length == 0) {
            throw validation("otp là bắt buộc.");
        }
        try {
            var verified = verifyWithoutTransaction(command);
            metrics.mfaVerify("STEP_UP", true, "NONE");
            return verified;
        } catch (BankingBusinessException rejection) {
            // BE-ID-IMP-13: đếm outcome MFA; label chỉ gồm purpose/result/mã lỗi catalog.
            metrics.mfaVerify("STEP_UP", false, rejection.code().name());
            throw rejection;
        } catch (JpaSystemException | org.springframework.transaction.CannotCreateTransactionException
                | org.springframework.dao.DataAccessResourceFailureException resourceFailure) {
            throw unavailable();
        } finally {
            java.util.Arrays.fill(command.otp(), '\0');
        }
    }

    /** API-AUTH-04: pre-auth challenge lookup never trusts a client-supplied user/device. */
    public LoginResult verifyLoginChallenge(String challengeRef, char[] otp, ClientContext client) {
        if (otp == null || otp.length == 0) {
            throw validation("otp là bắt buộc.");
        }
        long userId = 0;
        try {
            var snapshot = challenges.findByRef(challengeRef).orElseThrow(OtpChallengeService::notFound);
            userId = snapshot.userId();
            if (!ChallengePurpose.LOGIN_NEW_DEVICE.name().equals(snapshot.purpose())) {
                throw invalidTransition("Challenge không dùng cho login.");
            }
            var command = new VerifyCommand(challengeRef, userId, ChallengePurpose.LOGIN_NEW_DEVICE,
                    otp, client.correlationId());
            verifyCredential(snapshot, command);
            LoginResult result = ownTransaction.execute(status -> {
                markVerifiedOrReject(snapshot);
                // Consumption, session, OTP success and C2 audits share this exact transaction.
                return loginSessions.complete(snapshot.userId(), snapshot.deviceId(), snapshot.challengeRef(), client);
            });
            if (result == null) {
                throw new IllegalStateException("Login verification returned no result");
            }
            metrics.mfaVerify("LOGIN", true, "NONE");
            return result;
        } catch (BankingBusinessException rejection) {
            // BE-ID-IMP-13: đếm cả rejection verify login MFA (OTP sai/khóa/hết hạn/đã dùng).
            metrics.mfaVerify("LOGIN", false, rejection.code().name());
            if (userId > 0) {
                loginSessions.recordFailure(userId, rejection.code().name(), client);
            }
            throw rejection;
        } catch (JpaSystemException | org.springframework.transaction.CannotCreateTransactionException
                | org.springframework.dao.DataAccessResourceFailureException resourceFailure) {
            throw unavailable();
        } finally {
            java.util.Arrays.fill(otp, '\0');
        }
    }

    /**
     * Verify flow (đã hiệu chỉnh so với mô tả lock-then-verify của design/02 §5.1): pre-check
     * bằng read thường, mọi mutation là conditional UPDATE nguyên tử (`markVerified`/
     * `recordFailedVerifyAttempt`/`expireIfDue`). Lý do: (1) rejection (OTP sai/hết hạn) phải
     * COMMIT attempt counter — ném exception trong cùng TX sẽ rollback bộ đếm và phá invariant
     * max attempts (đã phát hiện trong test integration); (2) tránh giữ row lock FOR UPDATE
     * xuyên qua Argon2id ~100ms. Atomicity/race được bảo đảm bởi chính conditional UPDATE
     * (design/02 §9.1; task §7 pattern "conditional database update"); lock row vẫn implicit
     * trong từng UPDATE. Chi tiết: DEC-ID-005.
     */
    private VerifiedChallenge verifyWithoutTransaction(VerifyCommand command) {
        var snapshot = challenges.findByRefAndUser(command.challengeRef(), command.userId())
                .orElseThrow(OtpChallengeService::notFound);
        verifyCredential(snapshot, command);
        return completeVerification(snapshot, command.correlationId());
    }

    private void verifyCredential(ChallengeRepository.ChallengeSnapshot snapshot, VerifyCommand command) {
        ChallengeState state = ChallengeState.parse(snapshot.status())
                .orElseThrow(() -> new IllegalStateException("Unknown challenge status"));
        if (command.expectedPurpose() == null
                ? !ChallengePurpose.TRANSFER_STEP_UP.name().equals(snapshot.purpose())
                        && !ChallengePurpose.SENSITIVE_ACTION.name().equals(snapshot.purpose())
                : !command.expectedPurpose().name().equals(snapshot.purpose())) {
            throw mismatch();
        }
        if (!METHOD_OTP.equals(snapshot.method())) {
            throw mismatch();
        }
        if (state == ChallengeState.LOCKED) {
            throw locked();
        }
        if (state == ChallengeState.EXPIRED) {
            throw otpExpired();
        }
        if (state == ChallengeState.VERIFIED || state == ChallengeState.CANCELLED) {
            // Re-verify challenge đã verified/cancelled là transition cấm (single-use, §5 invariant).
            throw invalidTransition("Challenge không còn ở trạng thái có thể verify.");
        }
        if (!snapshot.expiresAt().isAfter(clock.instant())) {
            challenges.expireIfDue(snapshot.challengeId());
            throw otpExpired();
        }
        if (snapshot.attemptCount() >= snapshot.maxAttempts()) {
            // Phòng thủ: trạng thái active nhưng hết lượt (không bao giờ xảy ra nếu LOCKED đúng).
            throw locked();
        }
        if (state != ChallengeState.SENT) {
            // Provider failure or acceptance not durably acknowledged must fail closed.
            throw new BankingBusinessException(ErrorCode.OTP_DISPATCH_FAILED);
        }
        boolean matches = matchesSafely(snapshot, command);
        if (!matches) {
            registerFailedAttempt(snapshot, command);
        }
    }

    /** T08-style safe failure: stored hash hỏng → coi như OTP sai, không crash, không lộ. */
    private boolean matchesSafely(ChallengeRepository.ChallengeSnapshot snapshot, VerifyCommand command) {
        try {
            return hasher.matches(snapshot.otpHash(), command.otp());
        } catch (PasswordHashFormatException malformed) {
            return false;
        }
    }

    private void registerFailedAttempt(ChallengeRepository.ChallengeSnapshot snapshot, VerifyCommand command) {
        var bump = challenges.recordFailedVerifyAttempt(snapshot.challengeId(), snapshot.maxAttempts());
        if (bump.isEmpty()) {
            // Race: hết hạn/khóa/consume xen kẽ giữa lock và bump → đọc lại, map fail-closed.
            var current = challenges.findByRefAndUser(snapshot.challengeRef(), command.userId())
                    .orElseThrow(OtpChallengeService::notFound);
            ChallengeState state = ChallengeState.parse(current.status()).orElseThrow();
            if (state == ChallengeState.EXPIRED || current.expiresAt().isBefore(clock.instant())) {
                challenges.expireIfDue(current.challengeId());
                throw otpExpired();
            }
            if (state == ChallengeState.LOCKED) {
                throw locked();
            }
            if (state == ChallengeState.VERIFIED || state == ChallengeState.CANCELLED) {
                throw invalidTransition("Challenge không còn ở trạng thái có thể verify.");
            }
            throw otpInvalid();
        }
        if ("LOCKED".equals(bump.get().status())) {
            // design/01 §5.3: state lock commit trước; AUTH_CHALLENGE_LOCKED là C3 standalone.
            audit.recordStandalone(AuthAuditPort.AuthAuditEvent
                    .c3Challenge(AuthAuditPort.AuthAction.AUTH_CHALLENGE_LOCKED, command.userId(),
                            snapshot.challengeRef(), "MAX_ATTEMPTS", null)
                    .withChallenge(snapshot, "LOCKED", bump.get().attemptCount()), command.correlationId());
            throw locked();
        }
        audit.recordStandalone(AuthAuditPort.AuthAuditEvent
                .c3Challenge(AuthAuditPort.AuthAction.AUTH_STEP_UP_FAILED, command.userId(),
                        snapshot.challengeRef(), "OTP_MISMATCH", null)
                .withChallenge(snapshot, bump.get().status(), bump.get().attemptCount()), command.correlationId());
        throw otpInvalid();
    }

    private VerifiedChallenge completeVerification(ChallengeRepository.ChallengeSnapshot snapshot,
            String correlationId) {
        // Đúng OTP cũng tăng attempt_count (design/02 §5.1 bước 6) trong cùng statement atomic.
        // markVerified + audit C2 trong MỘT OWN TX (design/01 §5.3): audit fail → verify rollback.
        VerifiedChallenge result = ownTransaction.execute(status -> {
            markVerifiedOrReject(snapshot);
            audit.recordInTransaction(AuthAuditPort.AuthAuditEvent
                    .c2Challenge(AuthAuditPort.AuthAction.AUTH_STEP_UP_VERIFIED, snapshot.userId(),
                            snapshot.challengeRef(), null, null)
                    .withChallenge(snapshot, "VERIFIED", snapshot.attemptCount() + 1), correlationId);
            return new VerifiedChallenge(snapshot.challengeRef(),
                    ChallengePurpose.valueOf(snapshot.purpose()), clock.instant(), snapshot.expiresAt());
        });
        if (result == null) {
            throw new IllegalStateException("Verify returned no result");
        }
        return result;
    }

    private void markVerifiedOrReject(ChallengeRepository.ChallengeSnapshot snapshot) {
        if (challenges.markVerified(snapshot.challengeId(), snapshot.attemptCount())) {
            return;
        }
        var current = challenges.findByRefAndUser(snapshot.challengeRef(), snapshot.userId())
                .orElseThrow(OtpChallengeService::notFound);
        ChallengeState state = ChallengeState.parse(current.status()).orElseThrow();
        if (state == ChallengeState.EXPIRED || !current.expiresAt().isAfter(clock.instant())) {
            throw otpExpired();
        }
        if (state == ChallengeState.LOCKED) {
            throw locked();
        }
        throw invalidTransition("Challenge không còn ở trạng thái có thể verify.");
    }

    // ------------------------------------------------------------------ consume (JOIN/T1)

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ConsumedChallenge consumeVerified(ConsumeCommand command) {
        if (command == null) {
            throw validation("command là bắt buộc.");
        }
        try {
            if (!validConsumeBinding(command)) {
                // Diagnose ownership first: even malformed proof must not enumerate another user.
                throw diagnoseConsumeRejection(command);
            }
            var consumed = challenges.consume(new ChallengeRepository.ConsumePredicate(
                    command.challengeRef(), command.userId(), command.purpose().name(),
                    command.requestHash(), command.bindingKey()));
            if (consumed.isPresent()) {
                // Consume không phát audit event riêng: bằng chứng consume là audit T1 nghiệp vụ
                // (TRANSFER_CREATED kèm step_up_challenge_id — design/01 §5.3, design/02 §5.2).
                return new ConsumedChallenge(consumed.get().challengeId(), command.challengeRef(),
                        command.purpose(), consumed.get().consumedAt());
            }
            throw diagnoseConsumeRejection(command);
        } catch (JpaSystemException | org.springframework.transaction.CannotCreateTransactionException
                | org.springframework.dao.DataAccessResourceFailureException resourceFailure) {
            throw unavailable();
        }
    }

    /** Chẩn đoán rejection khi conditional UPDATE trả 0 dòng — đúng catalog 07 §6.6. */
    private BankingBusinessException diagnoseConsumeRejection(ConsumeCommand command) {
        var snapshot = challenges.findByRefAndUser(command.challengeRef(), command.userId())
                .orElseThrow(OtpChallengeService::notFound);
        if (command.purpose() == null || !command.purpose().name().equals(snapshot.purpose())) {
            return mismatch();
        }
        if (!bindingMatches(snapshot, command)) {
            return mismatch();
        }
        if (snapshot.consumedAt() != null) {
            return invalidTransition("Challenge đã được consume.");
        }
        if (challenges.expireIfDue(snapshot.challengeId()) || !snapshot.expiresAt().isAfter(clock.instant())) {
            return new BankingBusinessException(ErrorCode.AUTH_CHALLENGE_EXPIRED);
        }
        ChallengeState state = ChallengeState.parse(snapshot.status()).orElseThrow();
        return switch (state) {
            case LOCKED -> locked();
            // 07 §6.6: retry transfer không có challenge VERIFIED → 428 STEP_UP_REQUIRED; Problem
            // phải kèm challenge công khai (ProblemResponse.checkChallenge) để client verify tiếp.
            case CREATED, SENT -> new BankingBusinessException(ErrorCode.STEP_UP_REQUIRED,
                    ErrorCode.STEP_UP_REQUIRED.detail(), Map.of("challenge", publicChallenge(snapshot)));
            case EXPIRED -> new BankingBusinessException(ErrorCode.AUTH_CHALLENGE_EXPIRED);
            case VERIFIED, CANCELLED -> invalidTransition("Trạng thái challenge không cho phép consume.");
        };
    }

    /** Challenge công khai cho extension 428 (07 §6.3) — không chứa OTP/hash/binding. */
    private static Map<String, Object> publicChallenge(ChallengeRepository.ChallengeSnapshot snapshot) {
        int remaining = Math.max(0, Math.min(snapshot.maxAttempts(),
                snapshot.maxAttempts() - snapshot.attemptCount()));
        return Map.ofEntries(
                Map.entry("challengeId", java.util.UUID.fromString(snapshot.challengeRef())),
                Map.entry("purpose", snapshot.purpose()),
                Map.entry("methods", List.of(snapshot.method())),
                Map.entry("expiresAt", snapshot.expiresAt()),
                Map.entry("maxAttempts", snapshot.maxAttempts()),
                Map.entry("remainingAttempts", remaining),
                Map.entry("channel", snapshot.channel()));
    }

    private static boolean validConsumeBinding(ConsumeCommand command) {
        if (command.purpose() == null) {
            return false;
        }
        if (command.purpose() == ChallengePurpose.LOGIN_NEW_DEVICE) {
            return command.requestHash() == null && command.bindingKey() == null;
        }
        return command.requestHash() != null && REQUEST_HASH.matcher(command.requestHash()).matches()
                && command.bindingKey() != null && !command.bindingKey().isBlank()
                && command.bindingKey().length() <= BINDING_KEY_MAX && command.bindingKey().indexOf('\0') < 0;
    }

    private static boolean bindingMatches(ChallengeRepository.ChallengeSnapshot snapshot, ConsumeCommand command) {
        return validConsumeBinding(command) && Objects.equals(snapshot.requestHash(), command.requestHash())
                && Objects.equals(snapshot.idempotencyKey(), command.bindingKey());
    }

    // ------------------------------------------------------------------ helpers

    private ChallengeView toView(ChallengeRepository.ChallengeSnapshot snapshot) {
        ChallengePurpose purpose = ChallengePurpose.valueOf(snapshot.purpose());
        return new ChallengeView(snapshot.challengeRef(), purpose, snapshot.method(), snapshot.channel(),
                snapshot.expiresAt(), snapshot.maxAttempts(),
                snapshot.maxAttempts() - snapshot.attemptCount());
    }

    private static BankingBusinessException validation(String detail) {
        return new BankingBusinessException(ErrorCode.VALIDATION_ERROR, detail);
    }

    private static BankingBusinessException notFound() {
        return new BankingBusinessException(ErrorCode.AUTH_CHALLENGE_NOT_FOUND);
    }

    private static BankingBusinessException mismatch() {
        return new BankingBusinessException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
    }

    private static BankingBusinessException locked() {
        return new BankingBusinessException(ErrorCode.AUTH_CHALLENGE_LOCKED);
    }

    private static BankingBusinessException otpExpired() {
        return new BankingBusinessException(ErrorCode.AUTH_OTP_EXPIRED);
    }

    private static BankingBusinessException otpInvalid() {
        // Retryable đã do ErrorCode.AUTH_OTP_INVALID.retryable() quy định; extension retryable
        // không nằm trong whitelist `ProblemResponse.checkedExtensions`.
        return new BankingBusinessException(ErrorCode.AUTH_OTP_INVALID);
    }

    private static BankingBusinessException invalidTransition(String detail) {
        return new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION, detail);
    }

    private static BankingBusinessException unavailable() {
        return new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
