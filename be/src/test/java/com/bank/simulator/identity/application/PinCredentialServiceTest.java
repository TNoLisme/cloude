package com.bank.simulator.identity.application;

import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
class PinCredentialServiceTest {

    @Test
    void invalidPinVerificationPersistsFailureWithoutChangingPin() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        PinHashingService hashing = mock(PinHashingService.class);
        UUID customerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        when(repository.lockPin(customerId)).thenReturn(new IdentityJdbcRepository.PinRecord("hash", 1, null));
        when(hashing.matches("000000", "hash")).thenReturn(false);
        PinCredentialService service = new PinCredentialService(repository, hashing,
                Clock.fixed(now, ZoneOffset.UTC));

        PinCredentialService.VerifyResult result = service.verify(customerId, "000000");

        assertThat(result).isEqualTo(PinCredentialService.VerifyResult.INVALID);
        verify(repository).updatePinFailure(customerId, 2, null, now);
    }
    @Test
    void fifthInvalidPinPersistsLockUntilFifteenMinutesLater() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        PinHashingService hashing = mock(PinHashingService.class);
        UUID customerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        when(repository.lockPin(customerId)).thenReturn(new IdentityJdbcRepository.PinRecord("hash", 4, null));
        when(hashing.matches("000000", "hash")).thenReturn(false);
        PinCredentialService service = new PinCredentialService(repository, hashing,
                Clock.fixed(now, ZoneOffset.UTC));

        PinCredentialService.ChangeResult result = service.change(customerId, "000000", "123456");

        assertThat(result).isEqualTo(PinCredentialService.ChangeResult.LOCKED);
        verify(repository).updatePinFailure(customerId, 5, now.plusSeconds(900), now);
    }

    @Test
    void validPinVerificationResetsFailureCounterWhenPriorFailuresExist() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        PinHashingService hashing = mock(PinHashingService.class);
        UUID customerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        when(repository.lockPin(customerId)).thenReturn(new IdentityJdbcRepository.PinRecord("hash", 3, null));
        when(hashing.matches("123456", "hash")).thenReturn(true);
        PinCredentialService service = new PinCredentialService(repository, hashing,
                Clock.fixed(now, ZoneOffset.UTC));

        PinCredentialService.VerifyResult result = service.verify(customerId, "123456");

        assertThat(result).isEqualTo(PinCredentialService.VerifyResult.VALID);
        verify(repository).resetPinFailure(customerId, now);
    }

    @Test
    void fifthInvalidPinInVerifyLocksForFifteenMinutes() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        PinHashingService hashing = mock(PinHashingService.class);
        UUID customerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        when(repository.lockPin(customerId)).thenReturn(new IdentityJdbcRepository.PinRecord("hash", 4, null));
        when(hashing.matches("000000", "hash")).thenReturn(false);
        PinCredentialService service = new PinCredentialService(repository, hashing,
                Clock.fixed(now, ZoneOffset.UTC));

        PinCredentialService.VerifyResult result = service.verify(customerId, "000000");

        assertThat(result).isEqualTo(PinCredentialService.VerifyResult.LOCKED);
        verify(repository).updatePinFailure(customerId, 5, now.plusSeconds(900), now);
    }

    @Test
    void duringLockoutVerificationFailsEvenIfPinMatches() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        PinHashingService hashing = mock(PinHashingService.class);
        UUID customerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        when(repository.lockPin(customerId)).thenReturn(new IdentityJdbcRepository.PinRecord("hash", 5, now.plusSeconds(300)));
        PinCredentialService service = new PinCredentialService(repository, hashing,
                Clock.fixed(now, ZoneOffset.UTC));

        PinCredentialService.VerifyResult result = service.verify(customerId, "123456");

        assertThat(result).isEqualTo(PinCredentialService.VerifyResult.LOCKED);
        verify(hashing, never()).matches(anyString(), anyString());
        verify(repository, never()).resetPinFailure(customerId, now);
    }

    @Test
    void expiredLockoutCountsFromOneOnNextFailure() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        PinHashingService hashing = mock(PinHashingService.class);
        UUID customerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        when(repository.lockPin(customerId)).thenReturn(new IdentityJdbcRepository.PinRecord("hash", 5, now.minusSeconds(60)));
        when(hashing.matches("000000", "hash")).thenReturn(false);
        PinCredentialService service = new PinCredentialService(repository, hashing,
                Clock.fixed(now, ZoneOffset.UTC));

        PinCredentialService.VerifyResult result = service.verify(customerId, "000000");

        assertThat(result).isEqualTo(PinCredentialService.VerifyResult.INVALID);
        verify(repository).updatePinFailure(customerId, 1, null, now);
    }

    @Test
    void expiredLockoutResetsCounterOnSuccessfulVerification() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        PinHashingService hashing = mock(PinHashingService.class);
        UUID customerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        when(repository.lockPin(customerId)).thenReturn(new IdentityJdbcRepository.PinRecord("hash", 5, now.minusSeconds(60)));
        when(hashing.matches("123456", "hash")).thenReturn(true);
        PinCredentialService service = new PinCredentialService(repository, hashing,
                Clock.fixed(now, ZoneOffset.UTC));

        PinCredentialService.VerifyResult result = service.verify(customerId, "123456");

        assertThat(result).isEqualTo(PinCredentialService.VerifyResult.VALID);
        verify(repository).resetPinFailure(customerId, now);
    }
}
