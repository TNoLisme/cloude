package com.simulatedbank.banking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.admin.adapter.out.ApprovalJdbcRepository;
import com.simulatedbank.banking.admin.application.LimitApprovalBinding;
import com.simulatedbank.banking.admin.application.LimitApprovalService;
import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import com.simulatedbank.banking.identity.api.StaffDirectory;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.limit.api.LimitChanges;
import com.simulatedbank.banking.limit.api.LimitRules;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;

class LimitApprovalServiceTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    @SuppressWarnings("unchecked")
    void absentRequiredProviderRefusesBeforeAnyBusinessMutation(int missing) {
        var approvals = mock(ApprovalJdbcRepository.class);
        var limits = mock(LimitChanges.class);
        var audit = mock(AuditModuleApi.class);
        var identity = mock(StepUpChallengePort.class);
        var directory = mock(StaffDirectory.class);
        var customer = mock(CustomerQuery.class);
        var account = mock(AccountQuery.class);
        ObjectProvider<StepUpChallengePort> stepUps = mock(ObjectProvider.class);
        ObjectProvider<StaffDirectory> staff = mock(ObjectProvider.class);
        ObjectProvider<CustomerQuery> customers = mock(ObjectProvider.class);
        ObjectProvider<AccountQuery> accounts = mock(ObjectProvider.class);
        when(stepUps.getIfAvailable()).thenReturn(missing == 0 ? null : identity);
        when(staff.getIfAvailable()).thenReturn(missing == 1 ? null : directory);
        when(customers.getIfAvailable()).thenReturn(missing == 2 ? null : customer);
        when(accounts.getIfAvailable()).thenReturn(missing == 3 ? null : account);
        when(customer.findById(11)).thenReturn(Optional.of(new CustomerQuery.CustomerSnapshot(11)));
        var manager = mock(PlatformTransactionManager.class);
        var service = new LimitApprovalService(approvals, limits, mock(LimitRules.class), audit,
                mock(LimitApprovalBinding.class), stepUps, staff, customers, accounts, manager);
        var principal = new SessionAuthenticator.Session("1", null, Set.of("ROLE_OPERATIONS", "LIMIT_MAKER"), "0".repeat(64));
        var actor = UsernamePasswordAuthenticationToken.authenticated(principal, null, java.util.List.of());
        var change = new LimitApprovalBinding.Change(11, 21L, new BigDecimal("1.00"), new BigDecimal("2.00"), "VND", null, null);
        var trace = new RequestContext("test:missing", UUID.randomUUID().toString(), null, null);
        assertThatThrownBy(() -> service.propose(actor, change, null, trace))
                .isInstanceOfSatisfying(BankingBusinessException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        verifyNoInteractions(approvals, audit, manager);
        verify(identity, never()).requestChallenge(any());
        verify(identity, never()).consumeVerified(any(), any());
        verify(limits, never()).prepare(any());
    }
}
