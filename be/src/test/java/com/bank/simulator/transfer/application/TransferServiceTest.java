package com.bank.simulator.transfer.application;

import com.bank.simulator.account.infrastructure.persistence.AccountJdbcRepository;
import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.application.PinCredentialService;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeRepository;
import com.bank.simulator.identity.infrastructure.otp.OtpHashingService;
import com.bank.simulator.identity.infrastructure.otp.OtpSender;
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
        var dependencies = dependencies();
        var actor = new AuthenticatedActor(UUID.randomUUID(), Set.of("CUSTOMER"));
        var command = new TransferService.CreateCommand(UUID.randomUUID(), UUID.randomUUID(), "02000", "VND", "001234", null);

        assertThatThrownBy(() -> dependencies.service.create(actor, command, "abcdefghijklmnop"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(dependencies.accounts, dependencies.transfers, dependencies.idempotency,
                dependencies.identities, dependencies.otpHashing, dependencies.otpRepository,
                dependencies.sender, dependencies.pinCredentials, dependencies.audit);
    }

    @Test
    void invalidPinStopsTransferBeforeAccountMutation() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        var dependencies = dependencies();
        when(dependencies.identities.findCustomerByUserId(userId)).thenReturn(new IdentityJdbcRepository.CustomerRecord(
                customerId, userId, "Customer", null, Instant.EPOCH, "0912345678", "c@example.test", true));
        when(dependencies.pinCredentials.verify(customerId, "001234"))
                .thenReturn(PinCredentialService.VerifyResult.INVALID);
        var actor = new AuthenticatedActor(userId, Set.of("CUSTOMER"));

        assertThatThrownBy(() -> dependencies.service.create(actor,
                new TransferService.CreateCommand(sourceId, destinationId, "2000", "VND", "001234", null),
                "abcdefghijklmnop"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((ApiException) error).status())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(dependencies.pinCredentials).verify(customerId, "001234");
        verifyNoInteractions(dependencies.accounts, dependencies.transfers, dependencies.idempotency,
                dependencies.otpHashing, dependencies.otpRepository, dependencies.sender, dependencies.audit);
    }

    private Dependencies dependencies() {
        var accounts = mock(AccountJdbcRepository.class);
        var transfers = mock(TransferJdbcRepository.class);
        var idempotency = mock(IdempotencyJdbcRepository.class);
        var identities = mock(IdentityJdbcRepository.class);
        var otpHashing = mock(OtpHashingService.class);
        var otpRepository = mock(OtpChallengeRepository.class);
        var sender = mock(OtpSender.class);
        var pinCredentials = mock(PinCredentialService.class);
        var audit = mock(AuditWriter.class);
        var service = new TransferService(accounts, transfers, idempotency, identities, otpHashing, otpRepository,
                sender, pinCredentials, audit, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        return new Dependencies(service, accounts, transfers, idempotency, identities, otpHashing, otpRepository,
                sender, pinCredentials, audit);
    }

    private record Dependencies(TransferService service, AccountJdbcRepository accounts,
                                TransferJdbcRepository transfers, IdempotencyJdbcRepository idempotency,
                                IdentityJdbcRepository identities, OtpHashingService otpHashing,
                                OtpChallengeRepository otpRepository, OtpSender sender,
                                PinCredentialService pinCredentials, AuditWriter audit) {}
}
