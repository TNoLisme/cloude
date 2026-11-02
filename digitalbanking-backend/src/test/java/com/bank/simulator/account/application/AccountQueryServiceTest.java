package com.bank.simulator.account.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.shared.ratelimit.RateLimitInterceptor;
import com.bank.simulator.shared.ratelimit.RateLimitPolicyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AccountQueryServiceTest {
    @Test
    void operatorLookupRejectsWildcardAndDoesNotQuery() {
        var identities = mock(IdentityJdbcRepository.class);
        var accounts = mock(AccountJdbcRepository.class);
        var audit = mock(AuditWriter.class);
        var limiter = mock(RateLimitInterceptor.class);
        var policies = mock(RateLimitPolicyFactory.class);
        var service = new AccountQueryService(accounts, identities, audit, limiter, policies);
        var actor = new AuthenticatedActor(UUID.randomUUID(), Set.of("OPERATOR"));

        assertThatThrownBy(() -> service.lookup(actor, null, "person%example.test"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    var exception = (ApiException) error;
                    org.assertj.core.api.Assertions.assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    org.assertj.core.api.Assertions.assertThat(exception.code()).isEqualTo("VALIDATION_ERROR");
                });
        verifyNoInteractions(identities, accounts, limiter, audit);
    }

    @Test
    void accountReadRejectsNonOwner() {
        UUID accountId = UUID.randomUUID();
        UUID ownerCustomerId = UUID.randomUUID();
        var identities = mock(IdentityJdbcRepository.class);
        var accounts = mock(AccountJdbcRepository.class);
        var audit = mock(AuditWriter.class);
        var limiter = mock(RateLimitInterceptor.class);
        var policies = mock(RateLimitPolicyFactory.class);
        var service = new AccountQueryService(accounts, identities, audit, limiter, policies);
        var actor = new AuthenticatedActor(UUID.randomUUID(), Set.of("CUSTOMER"));
        when(accounts.find(accountId)).thenReturn(new AccountJdbcRepository.AccountRow(accountId, ownerCustomerId,
                "123456789012", "CHECKING", "ACTIVE", "0", "VND", Instant.EPOCH));
        when(identities.findCustomerByUserId(actor.userId())).thenReturn(
                new IdentityJdbcRepository.CustomerRecord(UUID.randomUUID(), actor.userId(), "Other", null,
                        Instant.EPOCH, "0912345678", "other@example.test", true));

        assertThatThrownBy(() -> service.get(actor, accountId))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((ApiException) error).status())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
