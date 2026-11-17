package com.simulatedbank.banking.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.transfer.adapter.inbound.TransferCreateInput;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Completed;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Conflict;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.InProgress;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.SourceNotOwned;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TransferCreateServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String BODY = """
            {"sourceAccountId":"3001","destination":{"accountId":"3002"},"amount":"150000.00"}
            """;

    private final RecordingAudit audit = new RecordingAudit();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final SecurityDenyAudit denyAudit = new SecurityDenyAudit(audit, metrics, CLOCK);
    private final AtomicInteger orchestratorCalls = new AtomicInteger();

    @Test
    void customerWithPermissionDelegatesAndReturnsCompletedWithoutSecurityAudit() {
        TransferCreateOutcome completed = new Completed(view());
        var service = service(request -> completed);
        var outcome = service.create(customer(), input());
        assertThat(outcome).isSameAs(completed);
        assertThat(orchestratorCalls).hasValue(1);
        assertThat(audit.events).isEmpty();
    }

    @Test
    void missingPermissionIsDeniedWithC3AndNeverOrchestrates() {
        var service = service(request -> { throw new AssertionError("must not orchestrate"); });
        var actor = new TransferActor(1L, 11L, Set.of("ROLE_CUSTOMER"), "corr-1", "req-1");
        assertThatThrownBy(() -> service.create(actor, input()))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> assertThat(((BankingBusinessException) ex).code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        assertThat(orchestratorCalls).hasValue(0);
        assertThat(audit.events).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("SECURITY_ACCESS_DENIED");
            assertThat(event.outcome()).isEqualTo("REJECTED");
            assertThat(event.afterState()).containsEntry("reasonCode", "PERMISSION_DENIED");
            assertThat(event.correlationId()).isEqualTo("corr-1");
        });
    }

    @Test
    void sessionWithoutCustomerIsDeniedEvenWithPermission() {
        var service = service(request -> { throw new AssertionError("must not orchestrate"); });
        var staff = new TransferActor(4L, null, Set.of("TRANSFER_CREATE"), "corr-2", "req-2");
        assertThatThrownBy(() -> service.create(staff, input()))
                .isInstanceOf(BankingBusinessException.class);
        assertThat(orchestratorCalls).hasValue(0);
        assertThat(audit.events).hasSize(1);
    }

    @Test
    void sourceNotOwnedOutcomeIsDeniedWithOwnershipC3() {
        var service = service(request -> new SourceNotOwned());
        assertThatThrownBy(() -> service.create(customer(), input()))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> assertThat(((BankingBusinessException) ex).code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        assertThat(audit.events).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("SECURITY_ACCESS_DENIED");
            assertThat(event.entityType()).isEqualTo("ACCOUNT");
            assertThat(event.entityId()).isEqualTo("3001");
            assertThat(event.afterState()).containsEntry("reasonCode", "OWNERSHIP_DENIED");
        });
    }

    @Test
    void conflictEmitsIdempotencyConflictC3AndReturnsConflict() {
        var service = service(request -> new Conflict());
        var outcome = service.create(customer(), input());
        assertThat(outcome).isInstanceOf(Conflict.class);
        assertThat(audit.events).singleElement().satisfies(event ->
                assertThat(event.action()).isEqualTo("SECURITY_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void nonSecurityBusinessOutcomesPassThroughWithoutAudit() {
        var service = service(request -> new InProgress());
        assertThat(service.create(customer(), input())).isInstanceOf(InProgress.class);
        assertThat(audit.events).isEmpty();
    }

    @Test
    void auditFailureFallsBackToMetricWithoutChangingDenyResponse() {
        audit.failure = new RuntimeException("audit store down");
        var service = service(request -> { throw new AssertionError("must not orchestrate"); });
        var actor = new TransferActor(1L, 11L, Set.of("ROLE_CUSTOMER"), "corr-3", "req-3");
        assertThatThrownBy(() -> service.create(actor, input()))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> assertThat(((BankingBusinessException) ex).code()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        assertThat(metrics.get("transfer.security.deny.fallback").counter().count()).isEqualTo(1.0);
    }

    private TransferCreateService service(TransferOrchestrator orchestrator) {
        return new TransferCreateService(request -> {
            orchestratorCalls.incrementAndGet();
            return orchestrator.create(request);
        }, denyAudit);
    }

    private static TransferActor customer() {
        return new TransferActor(1L, 11L, Set.of("ROLE_CUSTOMER", "TRANSFER_CREATE"), "corr-1", "req-1");
    }

    private static TransferCreateInput.Validated input() {
        return TransferCreateInput.parse(BODY.getBytes(StandardCharsets.UTF_8), List.of("key-1"), 65536);
    }

    private static TransferView view() {
        return new TransferView(9001L, "TXN20261116A1B2C3D4", TransferStatus.COMPLETED,
                new TransferAmount(new BigDecimal("150000.00"), "VND"), "memo",
                new MaskedAccount(3001L, "**** **** 3001", "3001"),
                new MaskedAccount(3002L, "**** **** 6789", "6789"),
                "corr-1", NOW, NOW, null, null, null);
    }

    static final class RecordingAudit implements AuditEventPort {
        final List<AuditEvent> events = new ArrayList<>();
        RuntimeException failure;
        @Override public Optional<Long> recordStandalone(AuditEvent event) {
            if (failure != null) throw failure;
            events.add(event);
            return Optional.of((long) events.size());
        }
    }
}