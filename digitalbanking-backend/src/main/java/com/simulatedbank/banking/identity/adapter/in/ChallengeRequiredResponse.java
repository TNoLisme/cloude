package com.simulatedbank.banking.identity.adapter.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.simulatedbank.banking.identity.application.FidoLoginService;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengeView;
import java.time.Instant;
import java.util.List;

/** Shared API-AUTH-01/03 challenge response; no authenticated credential or secret material. */
public record ChallengeRequiredResponse(String code, String message, Challenge challenge, String correlationId) {
    public static ChallengeRequiredResponse login(ChallengeView view, String correlationId) {
        return new ChallengeRequiredResponse("AUTH_CHALLENGE_REQUIRED", "Thiết bị cần xác thực OTP.",
                new Challenge(view.challengeRef(), view.purpose().name(), List.of(view.method()), view.channel(),
                        view.expiresAt(), view.maxAttempts(), view.remainingAttempts()), correlationId);
    }

    public static ChallengeRequiredResponse fido(FidoLoginService.Begun view, String correlationId) {
        return new ChallengeRequiredResponse("AUTH_CHALLENGE_REQUIRED", "Cần xác thực FIDO/WebAuthn.",
                new Challenge(view.challengeRef(), "LOGIN_NEW_DEVICE", List.of("FIDO"), null,
                        view.expiresAt(), view.maxAttempts(), view.maxAttempts(), null,
                        new FidoRequestOptions(view.nonce(), view.rpId(),
                                List.of(new AllowedCredential("public-key", view.credentialId())),
                                view.timeoutMillis(), "required")), correlationId);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Challenge(String challengeId, String purpose, List<String> methods, String channel,
            Instant expiresAt, int maxAttempts, int remainingAttempts, String maskedDestination,
            FidoRequestOptions fidoRequestOptions) {
        public Challenge(String challengeId, String purpose, List<String> methods, String channel,
                Instant expiresAt, int maxAttempts, int remainingAttempts) {
            this(challengeId, purpose, methods, channel, expiresAt, maxAttempts, remainingAttempts, null, null);
        }
    }

    public record FidoRequestOptions(String challenge, String rpId, List<AllowedCredential> allowCredentials,
            long timeout, String userVerification) {
    }

    public record AllowedCredential(String type, String id) {
    }
}
