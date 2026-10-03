package com.bank.simulator.identity.application;

import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository.UserRecord;
import com.bank.simulator.identity.infrastructure.security.PasswordHashingService;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DemoDataSeederTest {

    private static final String[] PHONES = {"0911111111", "0922222222", "0933333333", "0944444444"};
    private static final String[] EMAILS = {"operator@example.test", "auditor@example.test", "one@example.test", "two@example.test"};

    @Test
    void seedsRequiredPrincipalsAndCustomerAccountsAndPins() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        PasswordHashingService passwords = mock(PasswordHashingService.class);
        PinHashingService pins = mock(PinHashingService.class);
        when(passwords.encode(any())).thenReturn("password-hash");
        when(pins.encode(any())).thenReturn("pin-hash");
        when(repository.findByPhone(anyString())).thenReturn(null);
        when(repository.createDefaultAccount(any(), any(), any())).thenReturn(null);

        seeder(repository, passwords, pins).run(null);

        verify(repository, times(4)).createUser(any(), anyString(), anyString(), anyString(), anyString(), any());
        verify(repository, times(2)).createCustomer(any(), any(), anyString(), isNull(), any());
        verify(repository, times(2)).createDefaultAccount(any(), any(), any());
        verify(repository, times(2)).setPin(any(), eq("pin-hash"), any());
    }

    @Test
    void skipsExistingPrincipalsWithoutChangingCredentialsOrBalances() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        when(repository.findByPhone(anyString())).thenAnswer(invocation -> user(invocation.getArgument(0)));

        seeder(repository, mock(PasswordHashingService.class), mock(PinHashingService.class)).run(null);
        seeder(repository, mock(PasswordHashingService.class), mock(PinHashingService.class)).run(null);

        verify(repository, never()).createUser(any(), anyString(), anyString(), anyString(), anyString(), any());
        verify(repository, never()).createCustomer(any(), any(), anyString(), any(), any());
        verify(repository, never()).createDefaultAccount(any(), any(), any());
        verify(repository, never()).setPin(any(), anyString(), any());
    }

    @Test
    void failsSeedWhenExistingPhoneHasDifferentEmail() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        when(repository.findByPhone(anyString())).thenReturn(null);
        when(repository.findByIdentifier(EMAILS[0], "EMAIL")).thenReturn(
                new UserRecord(UUID.randomUUID(), "0900000000", EMAILS[0], "hash", true,
                        UUID.randomUUID(), "Other", Instant.EPOCH, true));

        assertThatThrownBy(() -> seeder(repository, mock(PasswordHashingService.class),
                mock(PinHashingService.class)).run(null)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).createUser(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void rejectsMissingCredentialsBeforeWriting() {
        IdentityJdbcRepository repository = mock(IdentityJdbcRepository.class);
        DemoDataSeeder seeder = new DemoDataSeeder(repository, mock(PasswordHashingService.class),
                mock(PinHashingService.class), mock(AccountNumberGenerator.class), mock(AuditWriter.class),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), "", EMAILS[0], "long-password-1",
                PHONES[1], EMAILS[1], "long-password-2", PHONES[2], EMAILS[2], "long-password-3", "123456",
                PHONES[3], EMAILS[3], "long-password-4", "654321");

        assertThatThrownBy(() -> seeder.run(null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    private UserRecord user(String phone) {
        return new UserRecord(UUID.randomUUID(), phone, "existing@example.test", "hash", true,
                UUID.randomUUID(), "Existing", Instant.EPOCH, true);
    }

    private DemoDataSeeder seeder(IdentityJdbcRepository repository, PasswordHashingService passwords,
                                  PinHashingService pins) {
        return new DemoDataSeeder(repository, passwords, pins, mock(AccountNumberGenerator.class),
                mock(AuditWriter.class), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                PHONES[0], EMAILS[0], "password-operator", PHONES[1], EMAILS[1], "password-auditor",
                PHONES[2], EMAILS[2], "password-customer-one", "123456",
                PHONES[3], EMAILS[3], "password-customer-two", "654321");
    }
}
