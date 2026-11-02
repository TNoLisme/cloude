package com.bank.simulator.identity.application;

import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeService;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.JwtAccessTokenCodec;
import com.bank.simulator.identity.infrastructure.security.PasswordHashingService;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.identity.infrastructure.security.RefreshSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OnboardingAuditTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void registrationAuditUsesRequestCorrelationIdAndContainsNoSecrets() {
        AtomicReference<UUID> userId = new AtomicReference<>();
        UUID correlationId = UUID.randomUUID();
        var account = new IdentityJdbcRepository.AccountRecord(UUID.randomUUID(), "123456789012",
                "CHECKING", "ACTIVE", "0", "VND", Instant.EPOCH);
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        OtpChallengeService otp = mock(OtpChallengeService.class);
        PasswordHashingService passwordHashing = mock(PasswordHashingService.class);
        AuditWriter audit = mock(AuditWriter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditWriter> auditProvider = mock(ObjectProvider.class);
        when(auditProvider.getIfAvailable()).thenReturn(audit);
        when(otp.consumeLatest("0912345678", "SMS", "REGISTRATION", "012345"))
                .thenReturn(OtpChallengeService.ConsumeResult.VALID);
        doAnswer(invocation -> {
            userId.set(invocation.getArgument(0));
            return null;
        }).when(repository).createUser(any(), anyString(), anyString(), anyString(), anyString(), any());
        when(repository.findByPhone("0912345678")).thenReturn(null);
        when(repository.findByIdentifier("person@example.test", "EMAIL")).thenReturn(null);
        when(passwordHashing.encode("correct-horse-battery")).thenReturn("password-hash");
        when(repository.createDefaultAccount(any(), any(), any())).thenReturn(account);
        MDC.put("correlationId", correlationId.toString());

        OnboardingService service = new OnboardingService(repository, otp, passwordHashing,
                mock(PinHashingService.class), mock(RefreshSessionService.class), mock(JwtAccessTokenCodec.class),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), mock(AccountNumberGenerator.class),
                mock(PinCredentialService.class), auditProvider);

        service.register("0912345678", "Person@Example.Test", "correct-horse-battery", "Person", null, "012345");

        verify(audit).record(isNull(), isNull(), eq("CUSTOMER_REGISTERED"), eq("CUSTOMER"),
                any(UUID.class), eq("SUCCESS"), eq(correlationId), eq("Customer registered with default account."));
        verify(repository).createUser(eq(userId.get()), anyString(), anyString(), anyString(), anyString(), any());
    }
}
