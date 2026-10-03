package com.bank.simulator.identity.application;

import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeService;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.JwtAccessTokenCodec;
import com.bank.simulator.identity.infrastructure.security.PasswordHashingService;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.identity.infrastructure.security.RefreshSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OnboardingAccountAtomicityTest {

    @Test
    void accountCreationFailurePreventsCompletionAndLeavesNoPostFailureAudit() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        OtpChallengeService otp = mock(OtpChallengeService.class);
        when(otp.consumeLatest("0912345678", "SMS", "REGISTRATION", "012345"))
                .thenReturn(OtpChallengeService.ConsumeResult.VALID);
        when(repository.findByPhone(anyString())).thenReturn(null);
        when(repository.findByIdentifier(anyString(), eq("EMAIL"))).thenReturn(null);
        when(repository.createDefaultAccount(any(), any(), any()))
                .thenThrow(new DuplicateKeyException("account number collision"));

        OnboardingService service = new OnboardingService(repository, otp, mock(PasswordHashingService.class),
                mock(PinHashingService.class), mock(RefreshSessionService.class), mock(JwtAccessTokenCodec.class),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), mock(AccountNumberGenerator.class),
                mock(PinCredentialService.class));

        assertThatThrownBy(() -> service.register("0912345678", "person@example.test",
                "password-123456", "Person", null, "012345"))
                .isInstanceOf(DuplicateKeyException.class);
        verify(repository, times(1)).createDefaultAccount(any(), any(), any());
    }
}
