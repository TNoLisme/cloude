package com.simulatedbank.banking.identity.application;

import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** DEC-ID-009: the current contract has no device-possession proof; fail closed, even for trusted rows. */
@Service
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class PasscodeLoginService {
    private static final Logger LOG = LoggerFactory.getLogger(PasscodeLoginService.class);
    private final AuthAuditPort audit;
    private final AuthMetrics metrics;

    public PasscodeLoginService(AuthAuditPort audit, AuthMetrics metrics) {
        this.audit = audit;
        this.metrics = metrics;
    }

    public void rejectUnprovenDevice(String username, String correlationId) {
        // No account/device lookup or credential verification: copied metadata cannot authorize login.
        var event = AuthAuditPort.AuthAuditEvent.c3Anonymous(AuthAuditPort.AuthAction.AUTH_LOGIN_FAILED,
                "anon:" + HmacService.sha256Hex(username.toLowerCase(Locale.ROOT)),
                ErrorCode.AUTH_DEVICE_NOT_TRUSTED.name(), null);
        try {
            audit.recordStandalone(event, correlationId);
        } catch (RuntimeException failure) {
            // C3 failure must not replace the denial or expose the provider exception.
            LOG.error("SECURITY_AUDIT_WRITE_FAILED action=AUTH_LOGIN_FAILED correlationId={}", correlationId);
        }
        // BE-ID-IMP-13: passcode login luôn bị từ chối ở MVP (DEC-ID-009) — đếm failure.
        metrics.loginAttempt("PASSCODE", false, ErrorCode.AUTH_DEVICE_NOT_TRUSTED.name());
        throw new BankingBusinessException(ErrorCode.AUTH_DEVICE_NOT_TRUSTED);
    }
}
