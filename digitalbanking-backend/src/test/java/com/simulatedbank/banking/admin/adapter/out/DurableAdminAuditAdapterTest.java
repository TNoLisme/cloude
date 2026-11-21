package com.simulatedbank.banking.admin.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.admin.application.AdminAuditPort;
import com.simulatedbank.banking.admin.application.AdminAuditPort.AdminAction;
import com.simulatedbank.banking.admin.application.AdminAuditPort.AdminAuditEvent;
import com.simulatedbank.banking.audit.api.AuditEvent;
import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.audit.api.AuditOutcome;
import com.simulatedbank.banking.audit.api.AuditRecordRef;
import com.simulatedbank.banking.common.error.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * BE-ADM-INT-01: the production audit adapter maps the Admin fact into the durable Audit v1 event and keeps the
 * documented C2 fail-closed / C3 best-effort semantics with bounded metric labels.
 */
class DurableAdminAuditAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final AuditRecordRef REF = new AuditRecordRef(1L, "a".repeat(64));

    private final RecordingAudit audit = new RecordingAudit();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DurableAdminAuditAdapter adapter = new DurableAdminAuditAdapter(audit, registry,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void c2MapsTheAllowlistedFactIntoTheDurableEvent() {
        adapter.recordInTransaction(AdminAuditEvent.decided(pending(), com.simulatedbank.banking.admin.domain
                .ApprovalDecision.APPROVE, 1101L, "OPERATIONS", "corr-1", "req-1", Map.of("version", "3"),
                Map.of("previousVersion", "2", "resultingVersion", "3")));

        assertThat(audit.inTransaction).hasSize(1);
        AuditEvent event = audit.inTransaction.getFirst();
        assertThat(event.action().name()).isEqualTo("ROLE_CHANGE_APPROVED");
        assertThat(event.actorUserId()).isEqualTo(1101L);
        assertThat(event.actorRole()).isEqualTo("OPERATIONS");
        assertThat(event.entityType()).isEqualTo("APPROVAL_REQUEST");
        assertThat(event.entityId()).isEqualTo("8001");
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.requestId()).isEqualTo("req-1");
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.afterState()).containsEntry("previousVersion", "2").containsEntry("decision", "APPROVE");
        assertThat(registry.counter("admin.audit.events", "action", "ROLE_CHANGE_APPROVED", "class", "C2",
                "outcome", "SUCCESS").count()).isEqualTo(1.0);
    }

    @Test
    void c2FailurePropagatesSoTheApprovalTransactionRollsBack() {
        audit.failInTransaction = true;

        assertThatThrownBy(() -> adapter.recordInTransaction(requested()))
                .isInstanceOfSatisfying(com.simulatedbank.banking.common.error.BankingBusinessException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(com.simulatedbank.banking.common.error.ErrorCode.AUDIT_WRITE_FAILED));

        assertThat(registry.counter("admin.audit.failures", "action", "ROLE_CHANGE_REQUESTED", "class", "C2")
                .count()).isEqualTo(1.0);
        assertThat(registry.counter("admin.audit.events", "action", "ROLE_CHANGE_REQUESTED", "class", "C2",
                "outcome", "SUCCESS").count()).isZero();
    }

    @Test
    void c3FailureIsContainedAndCounted() {
        audit.failStandalone = true;

        adapter.recordStandalone(AdminAuditEvent.denied(1101L, "OPERATIONS", 8001L, "ROLE_CHANGE",
                "corr-1", "req-1", ErrorCode.ROLE_CONFLICT));

        assertThat(registry.counter("admin.audit.failures", "action", "SECURITY_ACCESS_DENIED", "class", "C3")
                .count()).isEqualTo(1.0);
    }

    @Test
    void c3AbsentWriteIsCountedAsFailureAndNeverThrows() {
        audit.standaloneResult = Optional.empty();

        adapter.recordStandalone(AdminAuditEvent.denied(1101L, "OPERATIONS", 8001L, null,
                "corr-1", null, ErrorCode.AUTH_FORBIDDEN));

        assertThat(registry.counter("admin.audit.failures", "action", "SECURITY_ACCESS_DENIED", "class", "C3")
                .count()).isEqualTo(1.0);
    }

    @Test
    void c3SuccessRecordsOneBoundedEvent() {
        adapter.recordStandalone(AdminAuditEvent.denied(1101L, "OPERATIONS", 8001L, null, "corr-1", null,
                ErrorCode.AUTH_FORBIDDEN));

        assertThat(audit.standalone).hasSize(1);
        assertThat(audit.standalone.getFirst().outcome()).isEqualTo(AuditOutcome.REJECTED);
        assertThat(registry.counter("admin.audit.events", "action", "SECURITY_ACCESS_DENIED", "class", "C3",
                "outcome", "REJECTED").count()).isEqualTo(1.0);
    }

    private static AdminAuditEvent requested() {
        return new AdminAuditEvent(AdminAction.ROLE_CHANGE_REQUESTED, AdminAuditPort.AuditClass.C2, 1100L,
                "OPERATIONS", "APPROVAL_REQUEST", "8001", "ROLE_CHANGE", "APP_USER", "1102", 1100L, null, null,
                "corr-1", "req-1", "SUCCESS", null, null, Map.of("approvalId", "8001"));
    }

    private static com.simulatedbank.banking.admin.domain.ApprovalRequest pending() {
        var payload = new com.simulatedbank.banking.admin.domain.RoleChangePayload(
                com.simulatedbank.banking.admin.domain.RoleChangeOperation.GRANT_PRIVILEGE, 1102L, null,
                "CHECKER", 2L, "reason");
        return com.simulatedbank.banking.admin.domain.ApprovalRequest.restore(8001L, payload, 1100L, "corr-1",
                NOW, com.simulatedbank.banking.admin.domain.ApprovalStatus.APPLIED, 1101L, "ok", NOW, NOW);
    }

    private static final class RecordingAudit implements AuditModuleApi {
        private final List<AuditEvent> inTransaction = new ArrayList<>();
        private final List<AuditEvent> standalone = new ArrayList<>();
        private Optional<AuditRecordRef> standaloneResult = Optional.of(REF);
        private boolean failInTransaction;
        private boolean failStandalone;

        @Override
        public AuditRecordRef recordInTransaction(AuditEvent event) {
            if (failInTransaction) {
                throw new IllegalStateException("audit unavailable");
            }
            inTransaction.add(event);
            return REF;
        }

        @Override
        public Optional<AuditRecordRef> recordStandalone(AuditEvent event) {
            if (failStandalone) {
                throw new IllegalStateException("audit unavailable");
            }
            standalone.add(event);
            return standaloneResult;
        }
    }
}
