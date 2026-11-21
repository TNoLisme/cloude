package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.identity.adapter.out.SecureRandomOtpCodeGenerator;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.application.SessionCustomerBinding;
import com.simulatedbank.banking.identity.application.AuthAuditPort;
import com.simulatedbank.banking.identity.application.ChallengeRepository;
import com.simulatedbank.banking.identity.application.LoginSessionService;
import com.simulatedbank.banking.identity.application.OtpChallengeService;
import com.simulatedbank.banking.identity.application.OtpDispatchService;
import com.simulatedbank.banking.identity.domain.ChallengeState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * BE-ID-IMP-05 unit test: state machine mapping + validation + audit class (ID-05-U01..U20).
 * Repo/port mock đúng hợp đồng `identity.application`; Argon2id thật (precedent
 * `PasswordLoginServiceTest`); OTP generator fake cố định để so khớp verifier.
 */
class OtpChallengeServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    private static final String OTP = "123456";
    private static final String REF = "11111111-2222-4333-8444-555555555555";
    private static final String HASH_64 = "aa".repeat(32);

    private ChallengeRepository challenges;
    private AuthAuditPort audit;
    private Argon2PasswordHasher hasher;
    private OtpDispatchService dispatch;
    private OtpChallengeService service;

    /** OTP generator fake: luôn trả OTP cố định để unit test so khớp verifier thật. */
    private static final class FixedOtpGenerator implements com.simulatedbank.banking.identity.application.OtpCodeGenerator {
        @Override
        public char[] generate() {
            return OTP.toCharArray();
        }
    }

    private static AppProperties appProperties() {
        var security = new AppProperties.Security(
                new AppProperties.Security.Cookie("DB_SESSION", false, "Strict", "/", 28800L),
                List.of("http://localhost:5173"),
                new AppProperties.Security.Session(15, 8),
                new AppProperties.Security.Lockout(5, 10),
                new AppProperties.Security.Otp(300, 120, 5, 60, 3),
                new AppProperties.Security.RateLimit(10, 10, 10, 20, 30, 5, 120),
                new AppProperties.Security.Http(65536, 10000),
                new AppProperties.Security.StepUp(new java.math.BigDecimal("10000000"),
                        new java.math.BigDecimal("20000000")));
        return new AppProperties("Asia/Ho_Chi_Minh", null, security, null, null, null, null);
    }

    private static ChallengeRepository.ChallengeSnapshot snapshot(String status, int attemptCount,
            String requestHash, String idempotencyKey, Instant expiresAt) {
        return new ChallengeRepository.ChallengeSnapshot(7L, REF, 101L,
                StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP.name(),
                "OTP", "SMS", status, attemptCount, 5, expiresAt, NOW.minusSeconds(30),
                null, null, requestHash, idempotencyKey, hasherStatic().hash(OTP.toCharArray()), null);
    }

    private static Argon2PasswordHasher hasherStatic() {
        return new Argon2PasswordHasher();
    }

    @BeforeEach
    void setUp() {
        challenges = mock(ChallengeRepository.class);
        audit = mock(AuthAuditPort.class);
        hasher = new Argon2PasswordHasher();
        dispatch = mock(OtpDispatchService.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new OtpChallengeService(challenges, audit, hasher, new FixedOtpGenerator(),
                Clock.fixed(NOW, ZoneId.of("UTC")), appProperties(), txManager, dispatch,
                mock(LoginSessionService.class),
                new com.simulatedbank.banking.identity.application.AuthMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                org.mockito.Mockito.mock(SessionCustomerBinding.class));
    }

    // ------------------------------------------------------------------ issue

    private StepUpChallengePort.ChallengeRequest stepUpRequest() {
        return new StepUpChallengePort.ChallengeRequest(
                StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP, 101L, HASH_64, "idem-key-1", "corr-1");
    }

    // U01: issue hợp lệ → INSERT đúng binding/TTL/max_attempts, status khởi tạo CREATED, audit C2.
    @Test
    void u01_issuePersistsChallengeWithBindingAndAudit() {
        when(challenges.insert(any())).thenReturn(
                snapshot("CREATED", 0, HASH_64, "idem-key-1", NOW.plusSeconds(300)));

        StepUpChallengePort.ChallengeView view = service.requestChallenge(stepUpRequest());

        ArgumentCaptor<ChallengeRepository.NewChallenge> insert = ArgumentCaptor
                .forClass(ChallengeRepository.NewChallenge.class);
        verify(challenges).insert(insert.capture());
        assertThat(insert.getValue().userId()).isEqualTo(101L);
        assertThat(insert.getValue().purpose()).isEqualTo("TRANSFER_STEP_UP");
        assertThat(insert.getValue().method()).isEqualTo("OTP");
        assertThat(insert.getValue().channel()).isEqualTo("SMS");
        assertThat(insert.getValue().otpHash()).startsWith("$argon2id$");
        assertThat(hasher.matches(insert.getValue().otpHash(), OTP.toCharArray())).isTrue();
        assertThat(insert.getValue().requestHash()).isEqualTo(HASH_64);
        assertThat(insert.getValue().idempotencyKey()).isEqualTo("idem-key-1");
        assertThat(insert.getValue().expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(insert.getValue().maxAttempts()).isEqualTo(5);
        assertThat(insert.getValue().correlationId()).isEqualTo("corr-1");

        assertThat(view.challengeRef()).isEqualTo(REF);
        assertThat(view.purpose()).isEqualTo(StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP);
        assertThat(view.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(view.maxAttempts()).isEqualTo(5);
        assertThat(view.remainingAttempts()).isEqualTo(5);
        // Không rò OTP/hash qua view.
        assertThat(view.toString()).doesNotContain(OTP).doesNotContain("$argon2");

        ArgumentCaptor<AuthAuditPort.AuthAuditEvent> auditEvent = ArgumentCaptor
                .forClass(AuthAuditPort.AuthAuditEvent.class);
        verify(audit).recordInTransaction(auditEvent.capture(), org.mockito.ArgumentMatchers.eq("corr-1"));
        assertThat(auditEvent.getValue().action())
                .isEqualTo(AuthAuditPort.AuthAction.AUTH_CHALLENGE_CREATED);
        assertThat(auditEvent.getValue().auditClass()).isEqualTo(AuthAuditPort.AuditClass.C2);
        assertThat(auditEvent.getValue().entityId()).isEqualTo("AUTH_CHALLENGE:" + REF);
        assertThat(auditEvent.getValue().toString()).doesNotContain(OTP).doesNotContain("$argon2");
    }

    // U02: TRANSFER_STEP_UP thiếu binding → 400 VALIDATION_ERROR, không INSERT.
    @Test
    void u02_stepUpIssueWithoutBindingRejected() {
        var missing = new StepUpChallengePort.ChallengeRequest(
                StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP, 101L, null, null, "corr-1");

        assertThatThrownBy(() -> service.requestChallenge(missing))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(challenges, never()).insert(any());
        verify(audit, never()).recordInTransaction(any(), anyString());
    }

    // U03: LOGIN_NEW_DEVICE không chấp nhận binding (tránh challenge binding sai propósito).
    @Test
    void u03_loginIssueWithBindingRejected() {
        var withBinding = new StepUpChallengePort.ChallengeRequest(
                StepUpChallengePort.ChallengePurpose.LOGIN_NEW_DEVICE, 101L, HASH_64, "key", "corr-1");

        assertThatThrownBy(() -> service.requestChallenge(withBinding))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(challenges, never()).insert(any());
    }

    // U04: duplicate (key, hash) — reissue idempotent trả lại challenge active hiện có.
    @Test
    void u04_duplicateActiveStepUpReissueReturnsExistingChallenge() {
        when(challenges.insert(any())).thenThrow(new DuplicateKeyException("uq_auth_challenge_active_stepup"));
        var existing = snapshot("SENT", 1, HASH_64, "idem-key-1", NOW.plusSeconds(200));
        when(challenges.findActiveByIdempotencyKey("idem-key-1", HASH_64)).thenReturn(Optional.of(existing));

        StepUpChallengePort.ChallengeView view = service.requestChallenge(stepUpRequest());

        assertThat(view.challengeRef()).isEqualTo(REF);
        assertThat(view.remainingAttempts()).isEqualTo(4);
    }

    // U05: duplicate nhưng không còn active → rethrow (caller nhận lỗi hệ thống, không ảo tưởng thành công).
    @Test
    void u05_duplicateWithoutActiveChallengeRethrows() {
        when(challenges.insert(any())).thenThrow(new DuplicateKeyException("uq_auth_challenge_active_stepup"));
        when(challenges.findActiveByIdempotencyKey(anyString(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestChallenge(stepUpRequest()))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // U06: DB chết khi issue → 503 retryable fail-closed.
    @Test
    void u06_dbFailureOnIssueMapsToServiceUnavailable() {
        when(challenges.insert(any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> service.requestChallenge(stepUpRequest()))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    // ------------------------------------------------------------------ verify

    private StepUpChallengePort.VerifyCommand verifyCommand(String otp) {
        return new StepUpChallengePort.VerifyCommand(REF, 101L,
                StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP,
                otp == null ? null : otp.toCharArray(), "corr-1");
    }

    private void lockedRow(String status, int attempts) {
        when(challenges.findByRefAndUser(REF, 101L)).thenReturn(
                Optional.of(snapshot(status, attempts, HASH_64, "idem-key-1", NOW.plusSeconds(200))));
    }

    // U07: challenge không tồn tại/không thuộc user → 404 chung (anti-enumeration), không đụng state.
    @Test
    void u07_verifyUnknownOrForeignChallengeReturns404() {
        when(challenges.findByRefAndUser(REF, 101L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyChallenge(verifyCommand(OTP)))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_CHALLENGE_NOT_FOUND));
        verify(challenges, never()).recordFailedVerifyAttempt(anyLong(), anyInt(), any());
        verify(challenges, never()).markVerified(anyLong(), anyInt(), any());
    }

    // U08: sai purpose → 409 STEP_UP_CHALLENGE_MISMATCH.
    @Test
    void u08_verifyWrongPurposeIsMismatch() {
        lockedRow("CREATED", 0);
        var wrongPurpose = new StepUpChallengePort.VerifyCommand(REF, 101L,
                StepUpChallengePort.ChallengePurpose.LOGIN_NEW_DEVICE, OTP.toCharArray(), "corr-1");

        assertThatThrownBy(() -> service.verifyChallenge(wrongPurpose))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
    }

    // U09/U10/U11: LOCKED → 423; EXPIRED → 410; VERIFIED → 409 invalid transition.
    @ParameterizedTest
    @CsvSource({"LOCKED, AUTH_CHALLENGE_LOCKED", "EXPIRED, AUTH_OTP_EXPIRED",
            "VERIFIED, INVALID_STATE_TRANSITION", "CANCELLED, INVALID_STATE_TRANSITION"})
    void u09_verifyTerminalStatesMapToCatalogCodes(String status, String expectedCode) {
        lockedRow(status, 3);

        assertThatThrownBy(() -> service.verifyChallenge(verifyCommand(OTP)))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code().name()).isEqualTo(expectedCode));
        verify(challenges, never()).recordFailedVerifyAttempt(anyLong(), anyInt(), any());
    }

    // U12: CREATED nhưng đã quá TTL → expire transition + 410 AUTH_OTP_EXPIRED.
    @Test
    void u12_verifyExpiredByTimeExpiresAndReturns410() {
        lockedRow("CREATED", 0);
        // expires_at trước NOW (snapshot tạo với expires_at param)
        when(challenges.findByRefAndUser(REF, 101L)).thenReturn(
                Optional.of(snapshot("CREATED", 0, HASH_64, "idem-key-1", NOW.minusSeconds(1))));

        assertThatThrownBy(() -> service.verifyChallenge(verifyCommand(OTP)))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_OTP_EXPIRED));
        verify(challenges).expireIfDue(7L);
    }

    // U13: OTP sai → attempt tăng, chưa đạt ngưỡng → 401 AUTH_OTP_INVALID + C3 AUTH_STEP_UP_FAILED.
    @Test
    void u13_wrongOtpBumpsAttemptAndReturns401() {
        lockedRow("SENT", 2);
        when(challenges.recordFailedVerifyAttempt(eq(7L), eq(5), any()))
                .thenReturn(Optional.of(new ChallengeRepository.FailedAttemptOutcome(3, "SENT")));

        assertThatThrownBy(() -> service.verifyChallenge(verifyCommand("000000")))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_OTP_INVALID));

        ArgumentCaptor<AuthAuditPort.AuthAuditEvent> auditEvent = ArgumentCaptor
                .forClass(AuthAuditPort.AuthAuditEvent.class);
        verify(audit).recordStandalone(auditEvent.capture(), org.mockito.ArgumentMatchers.eq("corr-1"));
        assertThat(auditEvent.getValue().action()).isEqualTo(AuthAuditPort.AuthAction.AUTH_STEP_UP_FAILED);
        assertThat(auditEvent.getValue().auditClass()).isEqualTo(AuthAuditPort.AuditClass.C3);
        // Audit không chứa OTP/verifier.
        assertThat(auditEvent.getValue().toString()).doesNotContain("000000").doesNotContain("$argon2");
    }

    // U14: OTP sai đạt ngưỡng → status LOCKED, 423 AUTH_CHALLENGE_LOCKED + C3 AUTH_CHALLENGE_LOCKED.
    @Test
    void u14_wrongOtpAtMaxAttemptsLocksChallenge() {
        lockedRow("SENT", 4);
        when(challenges.recordFailedVerifyAttempt(eq(7L), eq(5), any()))
                .thenReturn(Optional.of(new ChallengeRepository.FailedAttemptOutcome(5, "LOCKED")));

        assertThatThrownBy(() -> service.verifyChallenge(verifyCommand("000000")))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_CHALLENGE_LOCKED));

        ArgumentCaptor<AuthAuditPort.AuthAuditEvent> auditEvent = ArgumentCaptor
                .forClass(AuthAuditPort.AuthAuditEvent.class);
        verify(audit).recordStandalone(auditEvent.capture(), org.mockito.ArgumentMatchers.eq("corr-1"));
        assertThat(auditEvent.getValue().action()).isEqualTo(AuthAuditPort.AuthAction.AUTH_CHALLENGE_LOCKED);
    }

    // U15: OTP đúng → VERIFIED + C2 AUTH_STEP_UP_VERIFIED cùng TX.
    @Test
    void u15_correctOtpVerifiesWithC2Audit() {
        lockedRow("SENT", 1);
        when(challenges.markVerified(eq(7L), eq(1), any())).thenReturn(true);

        StepUpChallengePort.VerifiedChallenge result = service.verifyChallenge(verifyCommand(OTP));

        assertThat(result.challengeRef()).isEqualTo(REF);
        assertThat(result.purpose()).isEqualTo(StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP);
        assertThat(result.verifiedAt()).isEqualTo(NOW);
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(200));

        ArgumentCaptor<AuthAuditPort.AuthAuditEvent> auditEvent = ArgumentCaptor
                .forClass(AuthAuditPort.AuthAuditEvent.class);
        verify(audit).recordInTransaction(auditEvent.capture(), org.mockito.ArgumentMatchers.eq("corr-1"));
        assertThat(auditEvent.getValue().action()).isEqualTo(AuthAuditPort.AuthAction.AUTH_STEP_UP_VERIFIED);
        assertThat(auditEvent.getValue().auditClass()).isEqualTo(AuthAuditPort.AuditClass.C2);
        assertThat(auditEvent.getValue().toString()).doesNotContain(OTP);
    }

    // U16: markVerified thua race → đọc lại, LOCKED → 423 fail-closed (không tự ghi VERIFIED).
    @Test
    void u16_markVerifiedRaceFallsBackToCurrentState() {
        lockedRow("SENT", 1);
        when(challenges.markVerified(eq(7L), eq(1), any())).thenReturn(false);
        when(challenges.findByRefAndUser(REF, 101L)).thenReturn(
                Optional.of(snapshot("LOCKED", 5, HASH_64, "idem-key-1", NOW.plusSeconds(200))));

        assertThatThrownBy(() -> service.verifyChallenge(verifyCommand(OTP)))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_CHALLENGE_LOCKED));
    }

    // U17: bump thua race → đọc lại EXPIRED → 410 (+ expire persist trong path này).
    @Test
    void u17_bumpRaceOnExpiredRowReturns410() {
        when(challenges.findByRefAndUser(REF, 101L)).thenReturn(
                Optional.of(snapshot("SENT", 4, HASH_64, "idem-key-1", NOW.plusSeconds(200))),
                Optional.of(snapshot("EXPIRED", 5, HASH_64, "idem-key-1", NOW.minusSeconds(5))));
        when(challenges.recordFailedVerifyAttempt(eq(7L), eq(5), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyChallenge(verifyCommand("000000")))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_OTP_EXPIRED));
        verify(challenges).expireIfDue(7L);
    }

    @ParameterizedTest
    @CsvSource({"TRANSFER_STEP_UP,OTP", "SENSITIVE_ACTION,OTP"})
    void authenticatedStepUpVerificationAllowsOnlyStepUpPurposes(String purpose, String method) {
        var row = verificationSnapshot(purpose, method);
        when(challenges.findByRefAndUser(REF, 101L)).thenReturn(Optional.of(row));
        when(challenges.markVerified(eq(7L), eq(0), any())).thenReturn(true);
        var command = new StepUpChallengePort.VerifyCommand(REF, 101L, null,
                OTP.toCharArray(), "corr-1");

        var result = service.verifyChallenge(command);

        assertThat(result.purpose().name()).isEqualTo(purpose);
        assertThat(command.otp()).containsOnly('\0');
    }

    @ParameterizedTest
    @CsvSource({"LOGIN_NEW_DEVICE,OTP", "TRANSFER_STEP_UP,FIDO", "SENSITIVE_ACTION,PASSCODE"})
    void authenticatedStepUpVerificationRejectsPurposeOrMethodSubstitution(String purpose, String method) {
        when(challenges.findByRefAndUser(REF, 101L))
                .thenReturn(Optional.of(verificationSnapshot(purpose, method)));
        var command = new StepUpChallengePort.VerifyCommand(REF, 101L, null,
                OTP.toCharArray(), "corr-1");

        assertThatThrownBy(() -> service.verifyChallenge(command))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));

        verify(challenges, never()).markVerified(anyLong(), anyInt(), any());
        verify(challenges, never()).recordFailedVerifyAttempt(anyLong(), anyInt(), any());
        assertThat(command.otp()).containsOnly('\0');
    }

    private static ChallengeRepository.ChallengeSnapshot verificationSnapshot(String purpose, String method) {
        var row = snapshot("SENT", 0, HASH_64, "idem-key-1", NOW.plusSeconds(200));
        return new ChallengeRepository.ChallengeSnapshot(row.challengeId(), row.challengeRef(), row.userId(),
                purpose, method, row.channel(), row.status(), row.attemptCount(), row.maxAttempts(),
                row.expiresAt(), row.createdAt(), row.verifiedAt(), row.consumedAt(),
                row.requestHash(), row.idempotencyKey(), row.otpHash(), null);
    }

    // ------------------------------------------------------------------ consume

    private StepUpChallengePort.ConsumeCommand consumeCommand() {
        return new StepUpChallengePort.ConsumeCommand(REF, 101L,
                StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP, HASH_64, "idem-key-1");
    }

    // U18: consume thành công → ConsumedChallenge đúng ref; consume join TX caller (mock chỉ chứng minh predicate).
    @Test
    void u18_consumeSuccessReturnsConsumedChallenge() {
        when(challenges.consume(any())).thenReturn(
                Optional.of(new ChallengeRepository.ConsumeResult(7L, NOW)));

        StepUpChallengePort.ConsumedChallenge result = service.consumeVerified(consumeCommand());

        assertThat(result.challengeId()).isEqualTo(7L);
        assertThat(result.challengeRef()).isEqualTo(REF);
        assertThat(result.purpose()).isEqualTo(StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP);
        assertThat(result.consumedAt()).isEqualTo(NOW);
        verify(challenges).consume(argThat(p -> p.challengeRef().equals(REF)
                && p.userId() == 101L
                && p.purpose().equals("TRANSFER_STEP_UP")
                && p.requestHash().equals(HASH_64)
                && p.idempotencyKey().equals("idem-key-1")));
        // Consume không phát audit event riêng (bằng chứng qua audit T1 nghiệp vụ).
        verify(audit, never()).recordInTransaction(any(), anyString());
    }

    // U19/U20/U21/U22: 0 dòng → chẩn đoán theo trạng thái row (07 §6.6 / design/02 §5.2).
    @Test
    void u19_consumeNotVerifiedReturns428() {
        when(challenges.consume(any())).thenReturn(Optional.empty());
        when(challenges.findByRefAndUser(REF, 101L)).thenReturn(
                Optional.of(snapshot("SENT", 0, HASH_64, "idem-key-1", NOW.plusSeconds(200))));

        assertThatThrownBy(() -> service.consumeVerified(consumeCommand()))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.STEP_UP_REQUIRED));
    }

    @Test
    void u20_consumeAlreadyConsumedReturns409InvalidTransition() {
        when(challenges.consume(any())).thenReturn(Optional.empty());
        when(challenges.findByRefAndUser(REF, 101L)).thenReturn(
                Optional.of(snapshot("VERIFIED", 1, HASH_64, "idem-key-1", NOW.plusSeconds(200))));

        assertThatThrownBy(() -> service.consumeVerified(consumeCommand()))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void u21_consumeBindingMismatchReturns409() {
        when(challenges.consume(any())).thenReturn(Optional.empty());
        // Row bind key khác command → mismatch trước khi xét state.
        when(challenges.findByRefAndUser(REF, 101L)).thenReturn(
                Optional.of(snapshot("VERIFIED", 1, HASH_64, "other-key", NOW.plusSeconds(200))));

        assertThatThrownBy(() -> service.consumeVerified(consumeCommand()))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
    }

    @Test
    void u22_consumeExpiredAndLockedMapToCatalogCodes() {
        // EXPIRED theo expires_at (status vẫn VERIFIED chưa consume): expire transition + 410.
        when(challenges.consume(any())).thenReturn(Optional.empty());
        when(challenges.findByRefAndUser(REF, 101L)).thenReturn(
                Optional.of(snapshot("VERIFIED", 1, HASH_64, "idem-key-1", NOW.minusSeconds(1))));

        assertThatThrownBy(() -> service.consumeVerified(consumeCommand()))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_CHALLENGE_EXPIRED));
        verify(challenges).expireIfDue(7L);
    }

    // U23: DB chết khi consume → 503; T1 caller rollback.
    @Test
    void u23_consumeDbFailureMapsToServiceUnavailable() {
        when(challenges.consume(any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> service.consumeVerified(consumeCommand()))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void unsentChallengeCannotVerifyEvenWithCorrectOtp() {
        lockedRow("CREATED", 0);
        var command = verifyCommand(OTP);
        assertThatThrownBy(() -> service.verifyChallenge(command))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.OTP_DISPATCH_FAILED));
        verify(challenges, never()).markVerified(anyLong(), anyInt(), any());
        verify(challenges, never()).recordFailedVerifyAttempt(anyLong(), anyInt(), any());
        assertThat(command.otp()).containsOnly('\0');
    }

    @Test
    void pendingDuplicateDoesNotDispatchAnotherGeneratedCode() {
        when(challenges.insert(any())).thenThrow(new DuplicateKeyException("active"));
        when(challenges.findActiveByIdempotencyKey("idem-key-1", HASH_64))
                .thenReturn(Optional.of(snapshot("CREATED", 0, HASH_64, "idem-key-1", NOW.plusSeconds(200))));
        assertThatThrownBy(() -> service.requestChallenge(stepUpRequest()))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.OTP_DISPATCH_FAILED));
        verify(dispatch, never()).deliver(any(), any(), anyString());
        verify(audit, never()).recordInTransaction(any(), anyString());
    }

    @Test
    void duplicateBindingCannotExposeAnotherUsersChallenge() {
        when(challenges.insert(any())).thenThrow(new DuplicateKeyException("active"));
        var foreignRequest = new StepUpChallengePort.ChallengeRequest(
                StepUpChallengePort.ChallengePurpose.TRANSFER_STEP_UP, 202L, HASH_64, "idem-key-1", "corr-1");
        when(challenges.findActiveByIdempotencyKey("idem-key-1", HASH_64))
                .thenReturn(Optional.of(snapshot("SENT", 0, HASH_64, "idem-key-1", NOW.plusSeconds(200))));
        assertThatThrownBy(() -> service.requestChallenge(foreignRequest))
                .isInstanceOfSatisfying(BankingBusinessException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
        verify(dispatch, never()).deliver(any(), any(), anyString());
    }

    @Test
    void productionGeneratorProducesSixAsciiDigitsInIndependentWipeableBuffers() {
        var generator = new SecureRandomOtpCodeGenerator();
        char[] first = generator.generate();
        char[] second = generator.generate();
        assertThat(first).hasSize(6);
        assertThat(second).hasSize(6);
        for (char digit : first) {
            assertThat(digit).isBetween('0', '9');
        }
        assertThat(first).isNotSameAs(second);
        java.util.Arrays.fill(first, '\0');
        for (char digit : second) {
            assertThat(digit).isBetween('0', '9');
        }
    }

    // ------------------------------------------------------------------ domain state machine

    @Test
    void u24_challengeStateTransitionsMatchContract() {
        // CREATED → SENT/VERIFIED/LOCKED/EXPIRED/CANCELLED; SENT → VERIFIED/LOCKED/EXPIRED/CANCELLED;
        // VERIFIED → EXPIRED; terminal không đi tiếp; không có CONSUMED (OD-ID-06).
        assertThat(ChallengeState.CREATED.canTransitionTo(ChallengeState.SENT)).isTrue();
        assertThat(ChallengeState.CREATED.canTransitionTo(ChallengeState.VERIFIED)).isTrue();
        assertThat(ChallengeState.SENT.canTransitionTo(ChallengeState.VERIFIED)).isTrue();
        assertThat(ChallengeState.VERIFIED.canTransitionTo(ChallengeState.EXPIRED)).isTrue();
        // Không có state CONSUMED (OD-ID-06): consume là cột consumed_at, không phải status.
        assertThatThrownBy(() -> ChallengeState.valueOf("CONSUMED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ChallengeState.LOCKED.isTerminal()).isTrue();
        assertThat(ChallengeState.EXPIRED.isTerminal()).isTrue();
        assertThat(ChallengeState.SENT.isTerminal()).isFalse();
        assertThat(ChallengeState.LOCKED.canTransitionTo(ChallengeState.CREATED)).isFalse();
        assertThat(ChallengeState.parse("created")).contains(ChallengeState.CREATED);
        assertThat(ChallengeState.parse("CONSUMED")).isEmpty();
        assertThat(ChallengeState.parse(null)).isEmpty();
    }
}
