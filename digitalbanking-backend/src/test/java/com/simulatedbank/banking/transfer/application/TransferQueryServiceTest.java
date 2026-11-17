package com.simulatedbank.banking.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.transfer.application.AccountOwnershipPort.Ownership;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TransferQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String REF = "TXN20261116A1B2C3D4";

    private final List<AuditEvent> events = new ArrayList<>();
    private final SecurityDenyAudit denyAudit = new SecurityDenyAudit(event -> {
        events.add(event);
        return Optional.of(1L);
    }, new SimpleMeterRegistry(), CLOCK);
    private final AtomicInteger fraudCalls = new AtomicInteger();
    private final FraudFlagReadPort fraudPort = transferId -> {
        fraudCalls.incrementAndGet();
        return List.of(new FraudFlagView(7L, "VELOCITY", 3, "HIGH", "OPEN"));
    };
    private final AccountOwnershipPort ownership = (customerId, accountId) ->
            (customerId == 11L && accountId == 3001L) || (customerId == 12L && accountId == 3002L)
                    ? Ownership.OWNED : Ownership.FORBIDDEN;

    @Test
    void customerOwningSourceReceivesMaskedReceiptWithoutFraudFlags() {
        var service = service(Optional.of(view()));
        var detail = service.get(REF, customer(11L));
        assertThat(detail.transfer().reference()).isEqualTo(REF);
        assertThat(detail.transfer().source().maskedNumber()).isEqualTo("**** **** 3001");
        assertThat(detail.fraudFlags()).isEmpty();
        assertThat(fraudCalls).hasValue(0);
        assertThat(events).isEmpty();
    }

    @Test
    void customerOwningDestinationIsAParticipant() {
        var service = service(Optional.of(view()));
        assertThat(service.get(REF, customer(12L)).transfer().reference()).isEqualTo(REF);
        assertThat(events).isEmpty();
    }

    @Test
    void customerNonParticipantIsDeniedWithC3() {
        var service = service(Optional.of(view()));
        assertThatThrownBy(() -> service.get(REF, customer(99L)))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> assertThat(((BankingBusinessException) ex).code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("SECURITY_ACCESS_DENIED");
            assertThat(event.afterState()).containsEntry("reasonCode", "OWNERSHIP_DENIED");
        });
    }

    @Test
    void absentReferenceIsNotFoundWithoutSecurityAudit() {
        var service = service(Optional.empty());
        assertThatThrownBy(() -> service.get(REF, customer(11L)))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> assertThat(((BankingBusinessException) ex).code()).isEqualTo(ErrorCode.TRANSFER_NOT_FOUND));
        assertThat(events).isEmpty();
    }

    @Test
    void staffWithTransactionReadButNoFraudPermissionGetsNoFlags() {
        var service = service(Optional.of(view()));
        var detail = service.get(REF, staff("TRANSACTION_READ"));
        assertThat(detail.transfer().reference()).isEqualTo(REF);
        assertThat(detail.fraudFlags()).isEmpty();
        assertThat(fraudCalls).hasValue(0);
    }

    @Test
    void staffWithFraudReadReceivesFlags() {
        var service = service(Optional.of(view()));
        var detail = service.get(REF, staff("TRANSACTION_READ", "FRAUD_READ"));
        assertThat(detail.fraudFlags()).singleElement().satisfies(flag -> {
            assertThat(flag.ruleCode()).isEqualTo("VELOCITY");
            assertThat(flag.ruleVersion()).isEqualTo(3);
        });
        assertThat(fraudCalls).hasValue(1);
    }

    @Test
    void staffWithFraudReviewReceivesFlags() {
        var service = service(Optional.of(view()));
        assertThat(service.get(REF, staff("TRANSACTION_READ", "FRAUD_REVIEW")).fraudFlags()).hasSize(1);
    }

    @Test
    void actorWithoutReadPermissionIsDeniedWithC3() {
        var service = service(Optional.of(view()));
        var actor = new TransferActor(1L, 11L, Set.of("ROLE_CUSTOMER", "TRANSFER_CREATE"), "corr", "req");
        assertThatThrownBy(() -> service.get(REF, actor))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> assertThat(((BankingBusinessException) ex).code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        assertThat(events).singleElement().satisfies(event ->
                assertThat(event.afterState()).containsEntry("reasonCode", "PERMISSION_DENIED"));
    }

    private TransferQueryService service(Optional<TransferView> stored) {
        return new TransferQueryService(reference -> stored, ownership, fraudPort, denyAudit);
    }

    private static TransferActor customer(long customerId) {
        return new TransferActor(1L, customerId, Set.of("ROLE_CUSTOMER", "TRANSACTION_READ_SELF"), "corr", "req");
    }

    private static TransferActor staff(String... authorities) {
        var set = new java.util.HashSet<>(Set.of(authorities));
        set.add("ROLE_OPERATIONS");
        return new TransferActor(4L, null, set, "corr", "req");
    }

    private static TransferView view() {
        return new TransferView(9001L, REF, TransferStatus.COMPLETED,
                new TransferAmount(new BigDecimal("150000.00"), "VND"), "memo",
                new MaskedAccount(3001L, "**** **** 3001", "3001"),
                new MaskedAccount(3002L, "**** **** 6789", "6789"),
                "corr-original", NOW, NOW, null, null, null);
    }
}