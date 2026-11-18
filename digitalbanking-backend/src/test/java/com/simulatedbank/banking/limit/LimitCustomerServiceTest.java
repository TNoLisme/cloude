package com.simulatedbank.banking.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.audit.api.AccessDeniedAudit;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.limit.application.port.LimitReadPort;
import com.simulatedbank.banking.limit.application.LimitCustomerService;
import com.simulatedbank.banking.limit.application.LimitSecurityDenialReporter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class LimitCustomerServiceTest {
    private static final SessionAuthenticator.Session CUSTOMER =
            new SessionAuthenticator.Session("1", "11", Set.of("ROLE_CUSTOMER", "LIMIT_READ_SELF"), "0".repeat(64));

    @SuppressWarnings("unchecked")
    @Test
    void missingAccountProviderFailsClosedBeforeReadingLimits() {
        ObjectProvider<AccountQuery> accounts = mock(ObjectProvider.class);
        var repository = mock(LimitReadPort.class);
        var reporter = mock(LimitSecurityDenialReporter.class);
        var service = new LimitCustomerService(repository, accounts, reporter,
                Clock.systemUTC(), ZoneOffset.UTC);
        var auth = UsernamePasswordAuthenticationToken.authenticated(CUSTOMER, null, Set.of());
        assertThatThrownBy(() -> service.getLimits(auth, "21", null))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        verifyNoInteractions(repository);
    }

    @SuppressWarnings("unchecked")
    @Test
    void wrongProviderProjectionCannotBypassAccountOwnership() {
        ObjectProvider<AccountQuery> accounts = mock(ObjectProvider.class);
        var account = mock(AccountQuery.class);
        when(accounts.getIfAvailable()).thenReturn(account);
        when(account.findAccount(21)).thenReturn(Optional.of(new AccountQuery.AccountSnapshot(22, 11,
                AccountQuery.Status.ACTIVE, "VND", "**** **** 1234", "1234")));
        var repository = mock(LimitReadPort.class);
        var reporter = mock(LimitSecurityDenialReporter.class);
        var service = new LimitCustomerService(repository, accounts, reporter,
                Clock.systemUTC(), ZoneOffset.UTC);
        var auth = UsernamePasswordAuthenticationToken.authenticated(CUSTOMER, null, Set.of());
        assertThatThrownBy(() -> service.getLimits(auth, "21", null))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        verifyNoInteractions(repository);
    }

    @SuppressWarnings("unchecked")
    @Test
    void businessDateChangesAtConfiguredMidnight() {
        ObjectProvider<AccountQuery> accounts = mock(ObjectProvider.class);
        var repository = mock(LimitReadPort.class);
        var reporter = mock(LimitSecurityDenialReporter.class);
        var auth = UsernamePasswordAuthenticationToken.authenticated(CUSTOMER, null, Set.of());
        var zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        var before = Clock.fixed(java.time.Instant.parse("2026-11-16T16:59:59Z"), ZoneOffset.UTC);
        var after = Clock.fixed(java.time.Instant.parse("2026-11-16T17:00:00Z"), ZoneOffset.UTC);
        new LimitCustomerService(repository, accounts, reporter, before, zone).getLimits(auth, null, null);
        new LimitCustomerService(repository, accounts, reporter, after, zone).getLimits(auth, null, null);
        verify(repository).findSnapshot(eq(11L), isNull(), eq(java.time.LocalDate.of(2026, 11, 16)), eq(before.instant()));
        verify(repository).findSnapshot(eq(11L), isNull(), eq(java.time.LocalDate.of(2026, 11, 17)), eq(after.instant()));
    }

    @SuppressWarnings("unchecked")
    @Test
    void reportToAuditProviderCarriesAccountScopeWithoutFabricatingApproval() {
        ObjectProvider<AccessDeniedAudit> audits = mock(ObjectProvider.class);
        var audit = mock(AccessDeniedAudit.class);
        when(audits.getIfAvailable()).thenReturn(audit);
        var metrics = new SimpleMeterRegistry();
        var reporter = new LimitSecurityDenialReporter(audits, metrics);
        var auth = UsernamePasswordAuthenticationToken.authenticated(CUSTOMER, null, Set.of());
        reporter.report(auth, "/api/v1/limits", "ACCOUNT_SCOPE", "21");
        var event = org.mockito.ArgumentCaptor.forClass(AccessDeniedAudit.Event.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().resourceType()).isEqualTo("ACCOUNT");
        assertThat(event.getValue().resourceId()).isEqualTo("21");
        assertThat(event.getValue().action()).isEqualTo("SECURITY_ACCESS_DENIED");
        assertThat(metrics.getMeters()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void auditFailureRecordsGapWithoutOverridingDenial() {
        ObjectProvider<AccessDeniedAudit> audits = mock(ObjectProvider.class);
        var audit = mock(AccessDeniedAudit.class);
        when(audits.getIfAvailable()).thenReturn(audit);
        doThrow(new IllegalStateException("unavailable")).when(audit).record(any());
        var metrics = new SimpleMeterRegistry();
        var reporter = new LimitSecurityDenialReporter(audits, metrics);
        var auth = UsernamePasswordAuthenticationToken.authenticated(CUSTOMER, null, Set.of());
        reporter.report(auth, "/api/v1/limits", "ACCOUNT_SCOPE");
        assertThat(metrics.counter("banking.audit.security.gap", "action", "SECURITY_ACCESS_DENIED").count())
                .isEqualTo(1);
    }
}
