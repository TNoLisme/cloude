package com.bank.simulator.transfer.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeRepository;
import com.bank.simulator.identity.infrastructure.otp.OtpHashingService;
import com.bank.simulator.identity.infrastructure.otp.OtpSender;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.shared.idempotency.IdempotencyJdbcRepository;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TransferServiceTest {
    @Test
    void customerCannotCreateTransferWithNonCanonicalAmount() {
        var accounts = mock(AccountJdbcRepository.class);
        var transfers = mock(TransferJdbcRepository.class);
        var idempotency = mock(IdempotencyJdbcRepository.class);
        var identities = mock(IdentityJdbcRepository.class);
        var otpHashing = mock(OtpHashingService.class);
        var otpRepository = mock(OtpChallengeRepository.class);
        var sender = mock(OtpSender.class);
        var pinHashing = mock(PinHashingService.class);
        var audit = mock(AuditWriter.class);
        var service = new TransferService(accounts, transfers, idempotency, identities, otpHashing, otpRepository,
                sender, pinHashing, audit, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var actor = new AuthenticatedActor(UUID.randomUUID(), Set.of("CUSTOMER"));
        var command = new TransferService.CreateCommand(UUID.randomUUID(), UUID.randomUUID(), "02000", "VND", "001234", null);

        assertThatThrownBy(() -> service.create(actor, command, "abcdefghijklmnop"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(accounts, transfers, idempotency, identities, otpHashing, otpRepository, sender, pinHashing, audit);
    }

    @Test
    void accountDebitsRequireSufficientFundsUnderLocks() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        var accounts = mock(AccountJdbcRepository.class);
        var transfers = mock(TransferJdbcRepository.class);
        var idempotency = mock(IdempotencyJdbcRepository.class);
        var identities = mock(IdentityJdbcRepository.class);
        var otpHashing = mock(OtpHashingService.class);
        var otpRepository = mock(OtpChallengeRepository.class);
        var sender = mock(OtpSender.class);
        var pinHashing = mock(PinHashingService.class);
        var audit = mock(AuditWriter.class);
        var service = new TransferService(accounts, transfers, idempotency, identities, otpHashing, otpRepository,
                sender, pinHashing, audit, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var actor = new AuthenticatedActor(userId, Set.of("CUSTOMER"));
        when(identities.findCustomerByUserId(userId)).thenReturn(new IdentityJdbcRepository.CustomerRecord(
                customerId, userId, "Customer", null, Instant.EPOCH, "0912345678", "c@example.test", true));
        when(accounts.lock(sourceId)).thenReturn(new AccountJdbcRepository.AccountRow(sourceId, customerId,
                "123456789012", "CHECKING", "ACTIVE", "1000", "VND", Instant.EPOCH));
        when(accounts.lock(destinationId)).thenReturn(new AccountJdbcRepository.AccountRow(destinationId, UUID.randomUUID(),
                "123456789013", "CHECKING", "ACTIVE", "0", "VND", Instant.EPOCH));
        when(identities.lockPin(customerId)).thenReturn(new IdentityJdbcRepository.PinRecord("hashed-pin", 0, null));
        when(pinHashing.matches("001234", "hashed-pin")).thenReturn(true);
        when(idempotency.find(userId, "createTransfer", "abcdefghijklmnop")).thenReturn(null);

        assertThatThrownBy(() -> service.create(actor,
                new TransferService.CreateCommand(sourceId, destinationId, "2000", "VND", "001234", null),
                "abcdefghijklmnop"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((ApiException) error).status())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(accounts, never()).debit(sourceId, "2000");
        verify(accounts, never()).credit(destinationId, "2000");
    }
}
