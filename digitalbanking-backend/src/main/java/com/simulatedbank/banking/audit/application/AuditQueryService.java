package com.simulatedbank.banking.audit.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.simulatedbank.banking.audit.application.port.AuditQueryPort;
import com.simulatedbank.banking.audit.domain.AuditSearchQuery;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal application service for searching and retrieving audit records.
 * Provides safe projection and maps not-found and integrity failures to domain exceptions.
 * Interacts with persistence exclusively through AuditQueryPort.
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.audit.enabled", havingValue = "true", matchIfMissing = true)
public class AuditQueryService {

    private final AuditQueryPort port;
    private final Clock clock;

    public record AuditActorView(String type, String userId, String role) { }

    public record AuditEntityView(String type, String id) { }

    public record AuditSummaryView(String auditId, Instant occurredAt, Instant recordedAt,
                                   AuditActorView actor, String action, AuditEntityView entity,
                                   String amount, String currency, String correlationId,
                                   String outcome, String recordHash, String prevHash,
                                   int hashVersion) { }

    public record PageMetaView(int number, int size, long totalElements, int totalPages,
                               boolean first, boolean last) { }

    public record AuditPageView(List<AuditSummaryView> items, PageMetaView page, Instant asOf) { }

    public record AuditClientInfoView(String ipMasked, String userAgent) { }

    /**
     * Flattened detail view matching OpenAPI AuditDetail (allOf AuditSummary + detail extensions).
     */
    public record AuditDetailView(
            String auditId,
            Instant occurredAt,
            Instant recordedAt,
            AuditActorView actor,
            String action,
            AuditEntityView entity,
            String amount,
            String currency,
            String correlationId,
            String outcome,
            String recordHash,
            String prevHash,
            int hashVersion,
            String requestId,
            Map<String, Object> beforeState,
            Map<String, Object> afterState,
            AuditClientInfoView clientInfo
    ) {
        /** Convenience projection returning the AuditSummaryView of this detail record. Ignored in JSON serialization to strictly adhere to flattened OpenAPI AuditDetail. */
        @JsonIgnore
        public AuditSummaryView summary() {
            return new AuditSummaryView(auditId, occurredAt, recordedAt, actor, action, entity,
                    amount, currency, correlationId, outcome, recordHash, prevHash, hashVersion);
        }
    }

    public AuditQueryService(AuditQueryPort port) {
        this(port, Clock.systemUTC());
    }

    @Autowired
    public AuditQueryService(AuditQueryPort port, Clock clock) {
        this.port = Objects.requireNonNull(port, "port is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public AuditPageView search(AuditSearchQuery query) {
        return search(query, clock.instant());
    }

    public AuditPageView search(AuditSearchQuery query, Instant asOf) {
        Objects.requireNonNull(query, "query is required");
        Instant effectiveAsOf = asOf != null ? asOf : clock.instant();
        AuditQueryPort.AuditRecordPage page = port.search(query, effectiveAsOf);
        List<AuditSummaryView> items = page.items().stream()
                .map(AuditQueryService::mapSummaryView)
                .toList();
        PageMetaView meta = new PageMetaView(page.number(), page.size(), page.totalElements(),
                page.totalPages(), page.first(), page.last());
        return new AuditPageView(items, meta, page.asOf());
    }

    public Optional<AuditDetailView> findDetail(long auditId) {
        if (auditId <= 0) return Optional.empty();
        try {
            return port.findDetail(auditId).map(AuditQueryService::mapDetailView);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw new BankingBusinessException(ErrorCode.INTERNAL_ERROR, "Dữ liệu audit lịch sử không an toàn hoặc không hợp lệ.");
        }
    }

    public AuditDetailView getDetail(long auditId) {
        if (auditId <= 0) {
            throw new BankingBusinessException(ErrorCode.AUDIT_NOT_FOUND, "Không tìm thấy bản ghi audit.");
        }
        return findDetail(auditId).orElseThrow(() ->
                new BankingBusinessException(ErrorCode.AUDIT_NOT_FOUND, "Không tìm thấy bản ghi audit."));
    }

    private static AuditSummaryView mapSummaryView(AuditQueryPort.AuditRecordSummary s) {
        AuditActorView actor = new AuditActorView(s.actorType(), s.actorUserId(), s.actorRole());
        AuditEntityView entity = new AuditEntityView(s.entityType(), s.entityId());
        return new AuditSummaryView(s.auditId(), s.occurredAt(), s.recordedAt(), actor,
                s.action(), entity, s.amount(), s.currency(), s.correlationId(), s.outcome(),
                s.recordHash(), s.prevHash(), s.hashVersion());
    }

    private static AuditDetailView mapDetailView(AuditQueryPort.AuditRecordDetail d) {
        var s = d.summary();
        AuditActorView actor = new AuditActorView(s.actorType(), s.actorUserId(), s.actorRole());
        AuditEntityView entity = new AuditEntityView(s.entityType(), s.entityId());

        Map<String, Object> beforeState = AuditDetailProjection.projectSnapshot(s.entityType(), d.beforeStateJson());
        Map<String, Object> afterState = AuditDetailProjection.projectSnapshot(s.entityType(), d.afterStateJson());
        AuditDetailProjection.ClientInfo clientInfoProjected = AuditDetailProjection.projectClientInfo(d.clientInfoMaskedJson());
        AuditClientInfoView clientInfo = clientInfoProjected == null ? null
                : new AuditClientInfoView(clientInfoProjected.ipMasked(), clientInfoProjected.userAgent());

        return new AuditDetailView(
                s.auditId(),
                s.occurredAt(),
                s.recordedAt(),
                actor,
                s.action(),
                entity,
                s.amount(),
                s.currency(),
                s.correlationId(),
                s.outcome(),
                s.recordHash(),
                s.prevHash(),
                s.hashVersion(),
                d.requestId(),
                beforeState,
                afterState,
                clientInfo
        );
    }
}
