package com.bank.simulator.identity.application;

import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PinCredentialServiceTest {

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
}
