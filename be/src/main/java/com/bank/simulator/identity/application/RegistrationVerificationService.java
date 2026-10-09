package com.bank.simulator.identity.application;

import com.bank.simulator.identity.infrastructure.otp.OtpChallengeService;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeRepository;
import com.bank.simulator.identity.infrastructure.security.RecoveryTokenCodec;
import com.bank.simulator.identity.infrastructure.security.RegistrationTokenRepository;
import com.bank.simulator.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
public class RegistrationVerificationService {
    private final RegistrationTokenRepository proofs;
    private final RecoveryTokenCodec codec;
    private final OtpChallengeService otp;
    private final OtpChallengeRepository challenges;
    private final OnboardingService onboarding;
    private final Clock clock;
    public RegistrationVerificationService(RegistrationTokenRepository proofs, RecoveryTokenCodec codec,
            OtpChallengeService otp, OtpChallengeRepository challenges, OnboardingService onboarding, Clock clock) {
        this.proofs = proofs; this.codec = codec; this.otp = otp; this.challenges = challenges; this.onboarding = onboarding; this.clock = clock;
    }

    // A dispatch failure must still retire the old proof/OTP; this method creates no customer.
    @Transactional(noRollbackFor = RuntimeException.class)
    public OtpChallengeService.OtpIssueResult sendOtp(String phone) {
        proofs.lockPhone(phone);
        proofs.invalidate(phone, clock.instant());
        challenges.invalidateActive(phone, "SMS", "REGISTRATION", clock.instant());
        return onboarding.sendRegistrationOtp(phone);
    }

    @Transactional
    public VerifyResult verify(String phone, String code) {
        proofs.lockPhone(phone);
        // Return invalid rather than throw inside this transaction so wrong-code attempts persist.
        if (otp.consumeLatest(phone, "SMS", "REGISTRATION", code) != OtpChallengeService.ConsumeResult.VALID) return new VerifyResult(null, 0);
        String raw = codec.generate();
        var now = clock.instant();
        proofs.invalidate(phone, now);
        proofs.create(UUID.randomUUID(), phone, codec.hash(raw), now, now.plusSeconds(300));
        return new VerifyResult(raw, 300);
    }

    @Transactional
    public OnboardingService.RegistrationResult register(String phone, String email, String password,
            String fullName, String address, String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 128) throw invalid();
        proofs.lockPhone(phone);
        if (!proofs.consume(phone, codec.hash(rawToken), clock.instant())) throw invalid();
        return onboarding.createVerifiedRegistration(phone, email, password, fullName, address);
    }
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "REGISTRATION_TOKEN_INVALID", "Registration proof is invalid or expired."); }
    public record VerifyResult(String registrationToken, int expiresInSeconds) {}
}
