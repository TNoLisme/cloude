package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simulatedbank.banking.identity.application.AuthAuditPort;
import com.simulatedbank.banking.identity.application.LogoutService;
import com.simulatedbank.banking.identity.application.SessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** T15/T16 unit: logout revoke theo token hash; idempotent; audit C3 đúng action. */
class LogoutServiceTest {

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final AuthAuditPort audit = mock(AuthAuditPort.class);
    private final LogoutService service = new LogoutService(sessions, audit,
            new com.simulatedbank.banking.identity.application.AuthMetrics(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

    // T15: logout hợp lệ → revoke theo hash token, audit AUTH_SESSION_LOGOUT C3.
    @Test
    void t15_validLogoutRevokesSessionByTokenHash() {
        when(sessions.revokeByTokenHash(anyString())).thenReturn(true);

        boolean revoked = service.logout("raw-token", 101L, "corr-9");

        assertThat(revoked).isTrue();
        verify(sessions).revokeByTokenHash(
                com.simulatedbank.banking.common.crypto.HmacService.sha256Hex("raw-token"));
        ArgumentCaptor<AuthAuditPort.AuthAuditEvent> event = ArgumentCaptor
                .forClass(AuthAuditPort.AuthAuditEvent.class);
        verify(audit).recordStandalone(event.capture(), eq("corr-9"));
        assertThat(event.getValue().action()).isEqualTo(AuthAuditPort.AuthAction.AUTH_SESSION_LOGOUT);
        assertThat(event.getValue().auditClass()).isEqualTo(AuthAuditPort.AuditClass.C3);
    }

    // T16: logout lặp lại → idempotent (không còn ACTIVE), vẫn 200-đường nghiệp vụ, không lỗi.
    @Test
    void t16_repeatedLogoutIsIdempotent() {
        when(sessions.revokeByTokenHash(anyString())).thenReturn(false);

        boolean revoked = service.logout("raw-token", 101L, "corr-9");

        assertThat(revoked).isFalse();
        verify(sessions).revokeByTokenHash(anyString());
        verify(audit).recordStandalone(any(), eq("corr-9"));
    }
}
