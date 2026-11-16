package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.application.AuthAuditPort;
import com.simulatedbank.banking.identity.application.PasscodeLoginService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PasscodeLoginServiceTest {
    private final AuthAuditPort audit = mock(AuthAuditPort.class);
    private final PasscodeLoginService service = new PasscodeLoginService(audit,
            new com.simulatedbank.banking.identity.application.AuthMetrics(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

    @Test
    void unprovenDeviceDenialRecordsAnonymousFailureWithoutLeakingUsername() {
        assertDenied();
        var event = ArgumentCaptor.forClass(AuthAuditPort.AuthAuditEvent.class);
        verify(audit).recordStandalone(event.capture(), eq("passcode-test"));
        assertThat(event.getValue().actorUserId()).isNull();
        assertThat(event.getValue().anonymousRef()).matches("anon:[0-9a-f]{64}");
        assertThat(event.getValue().toString()).doesNotContain("SensitiveUser");
        assertThat(event.getValue().action()).isEqualTo(AuthAuditPort.AuthAction.AUTH_LOGIN_FAILED);
        assertThat(event.getValue().auditClass()).isEqualTo(AuthAuditPort.AuditClass.C3);
    }

    @Test
    void unavailableAuditCannotTurnDenialIntoSuccessOrLeakItsException() {
        doThrow(new IllegalStateException("sensitive-provider-failure"))
                .when(audit).recordStandalone(any(), eq("passcode-test"));
        assertDenied();
    }

    private void assertDenied() {
        assertThatThrownBy(() -> service.rejectUnprovenDevice("SensitiveUser", "passcode-test"))
                .isInstanceOfSatisfying(BankingBusinessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ErrorCode.AUTH_DEVICE_NOT_TRUSTED);
                    assertThat(failure.getMessage()).doesNotContain("SensitiveUser", "sensitive-provider-failure");
                    assertThat(failure.getCause()).isNull();
                });
    }
}
