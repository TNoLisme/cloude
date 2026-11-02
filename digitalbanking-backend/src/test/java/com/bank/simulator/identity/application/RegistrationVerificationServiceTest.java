package com.bank.simulator.identity.application;

import com.bank.simulator.identity.infrastructure.otp.OtpChallengeRepository;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeService;
import com.bank.simulator.identity.infrastructure.security.RecoveryTokenCodec;
import com.bank.simulator.identity.infrastructure.security.RegistrationTokenRepository;
import com.bank.simulator.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RegistrationVerificationServiceTest {
    private final RegistrationTokenRepository proofs = mock(RegistrationTokenRepository.class);
    private final RecoveryTokenCodec codec = mock(RecoveryTokenCodec.class);
    private final OtpChallengeService otp = mock(OtpChallengeService.class);
    private final OtpChallengeRepository challenges = mock(OtpChallengeRepository.class);
    private final OnboardingService onboarding = mock(OnboardingService.class);
    private final Instant now = Instant.parse("2026-10-08T12:00:00Z");
    private final RegistrationVerificationService service = new RegistrationVerificationService(proofs, codec, otp, challenges, onboarding, Clock.fixed(now, ZoneOffset.UTC));

    @Test void invalidOtpDoesNotIssueProofOrCreateAccount() {
        when(otp.consumeLatest("0912345678", "SMS", "REGISTRATION", "000001")).thenReturn(OtpChallengeService.ConsumeResult.INVALID);
        assertThat(service.verify("0912345678", "000001").registrationToken()).isNull();
        verify(proofs).lockPhone("0912345678");
        verify(proofs, never()).create(any(), any(), any(), any(), any());
        verifyNoInteractions(codec, onboarding);
    }
    @Test void validOtpCreatesOnlyHashAndFiveMinuteProof() {
        when(otp.consumeLatest("0912345678", "SMS", "REGISTRATION", "000001")).thenReturn(OtpChallengeService.ConsumeResult.VALID);
        when(codec.generate()).thenReturn("raw-proof"); when(codec.hash("raw-proof")).thenReturn("hash");
        assertThat(service.verify("0912345678", "000001")).isEqualTo(new RegistrationVerificationService.VerifyResult("raw-proof", 300));
        verify(proofs).invalidate("0912345678", now);
        verify(proofs).create(any(), eq("0912345678"), eq("hash"), eq(now), eq(now.plusSeconds(300)));
        verifyNoInteractions(onboarding);
    }
    @Test void resendRetiresOldProofAndChallengeBeforeIssuing() {
        service.sendOtp("0912345678");
        var order = inOrder(proofs, challenges, onboarding);
        order.verify(proofs).lockPhone("0912345678"); order.verify(proofs).invalidate("0912345678", now);
        order.verify(challenges).invalidateActive("0912345678", "SMS", "REGISTRATION", now);
        order.verify(onboarding).sendRegistrationOtp("0912345678");
    }
    @Test void invalidProofNeverCreatesCustomer() {
        when(codec.hash("proof")).thenReturn("hash");
        assertThatThrownBy(() -> service.register("0912345678", "test@example.test", "long-password", "Person", null, "proof"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("REGISTRATION_TOKEN_INVALID"));
        verify(proofs).consume("0912345678", "hash", now); verifyNoInteractions(onboarding);
    }
    @Test void malformedProofNeverTouchesPersistence() {
        for (String value : new String[] { null, "", " ", "x".repeat(129) }) {
            assertThatThrownBy(() -> service.register("0912345678", "test@example.test", "long-password", "Person", null, value)).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(proofs, codec, onboarding);
    }
    @Test void verifiedRegistrationConsumesProofBeforeCreatingAccount() {
        when(codec.hash("proof")).thenReturn("hash"); when(proofs.consume("0912345678", "hash", now)).thenReturn(true);
        service.register("0912345678", "test@example.test", "long-password", "Person", null, "proof");
        var order = inOrder(proofs, onboarding); order.verify(proofs).lockPhone("0912345678"); order.verify(proofs).consume("0912345678", "hash", now);
        order.verify(onboarding).createVerifiedRegistration("0912345678", "test@example.test", "long-password", "Person", null);
        verifyNoInteractions(otp);
    }
}
