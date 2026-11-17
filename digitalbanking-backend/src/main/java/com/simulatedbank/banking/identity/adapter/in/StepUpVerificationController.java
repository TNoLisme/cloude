package com.simulatedbank.banking.identity.adapter.in;

import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** API-AUTH-05: verifies an existing step-up challenge; never issues or executes a transfer. */
@RestController
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class StepUpVerificationController {
    private final StepUpChallengePort challenges;

    public StepUpVerificationController(StepUpChallengePort challenges) {
        this.challenges = challenges;
    }

    @PostMapping("/api/v1/auth/step-up/verify")
    public ResponseEntity<VerificationResponse> verify(@RequestBody StepUpVerificationRequest request,
            HttpServletRequest servlet) {
        // Validate inside invocation so every rejection also erases all supplied numeric proofs.
        try {
            long userId = actorUserId(servlet);
            request.validate();
            if (!"OTP".equals(request.method())) {
                throw new BankingBusinessException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
            }
            String correlationId = correlationId(servlet);
            // Null selects the two authenticated step-up purposes, never LOGIN_NEW_DEVICE.
            var verified = challenges.verifyChallenge(new StepUpChallengePort.VerifyCommand(
                    request.challengeId(), userId, null, request.otp(), correlationId));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new VerificationResponse(
                    verified.challengeRef(), "VERIFIED", verified.purpose(), verified.verifiedAt(),
                    verified.expiresAt(), correlationId));
        } finally {
            request.eraseSecrets();
        }
    }

    private static long actorUserId(HttpServletRequest request) {
        Object session = request.getAttribute(
                "com.simulatedbank.banking.common.config.SessionAuthenticationFilter.session");
        if (session instanceof SessionAuthenticator.Session snapshot) {
            return Long.parseLong(snapshot.userId());
        }
        throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
    }


    private static String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute("correlationId");
        return value instanceof String text && !text.isEmpty() ? text : UUID.randomUUID().toString();
    }

    public record VerificationResponse(String challengeId, String status,
            StepUpChallengePort.ChallengePurpose purpose, Instant verifiedAt, Instant expiresAt,
            String correlationId) {
    }
}
