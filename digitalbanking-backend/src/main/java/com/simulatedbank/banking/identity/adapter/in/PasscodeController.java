package com.simulatedbank.banking.identity.adapter.in;

import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.application.PasscodeLoginService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** API-AUTH-02, intentionally unavailable without an approved device-proof contract (DEC-ID-009). */
@RestController
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class PasscodeController {
    private final PasscodeLoginService service;

    public PasscodeController(PasscodeLoginService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/auth/passcode")
    public void login(@RequestBody PasscodeLoginRequest request) {
        try {
            validate(request);
            String correlationId = RequestContextHolder.current().orElseThrow().correlationId();
            service.rejectUnprovenDevice(request.username(), correlationId);
        } finally {
            request.erasePasscode();
        }
    }

    private static void validate(PasscodeLoginRequest request) {
        if (request.username() == null || request.username().isEmpty() || request.username().length() > 64
                || request.deviceFingerprint() == null || request.deviceFingerprint().isEmpty()) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        IdentityRequestValidation.requireUuid(request.deviceRef());
        IdentityRequestValidation.requireDigits(request.passcode());
    }
}
