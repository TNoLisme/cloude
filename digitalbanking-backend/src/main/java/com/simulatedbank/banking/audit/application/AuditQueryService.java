package com.simulatedbank.banking.audit.application;

import com.simulatedbank.banking.audit.domain.AuditSearchQuery;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal application service for searching and retrieving audit records.
 * Provides safe projection and maps not-found and integrity failures to domain exceptions.
 */
public class AuditQueryService {

    private final AuditQueryJdbcStore store;
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
        /** Convenience projection returning the AuditSummaryView of this detail record. */
        public AuditSummaryView summary() {
            return new AuditSummaryView(auditId, occurredAt, recordedAt, actor, action, entity,
                    amount, currency, correlationId, outcome, recordHash, prevHash, hashVersion);
        }
    }

    public AuditQueryService(AuditQueryJdbcStore store) {
        this(store, Clock.systemUTC());
    }

    public AuditQueryService(AuditQueryJdbcStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public AuditPageView search(AuditSearchQuery query) {
        return search(query, clock.instant());
    }

    public AuditPageView search(AuditSearchQuery query, Instant asOf) {
        Objects.requireNonNull(query, "query is required");
        Instant effectiveAsOf = asOf != null ? asOf : clock.instant();
        AuditQueryJdbcStore.Page page = store.search(query, effectiveAsOf);
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
            return store.findDetail(auditId).map(AuditQueryService::mapDetailView);
        } catch (IllegalStateException ex) {
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

    private static AuditSummaryView mapSummaryView(AuditQueryJdbcStore.Summary s) {
        AuditActorView actor = new AuditActorView(s.actorType(), s.actorUserId(), s.actorRole());
        AuditEntityView entity = new AuditEntityView(s.entityType(), s.entityId());
        return new AuditSummaryView(s.auditId(), s.occurredAt(), s.recordedAt(), actor,
                s.action(), entity, s.amount(), s.currency(), s.correlationId(), s.outcome(),
                s.recordHash(), s.prevHash(), s.hashVersion());
    }

    private static AuditDetailView mapDetailView(AuditQueryJdbcStore.Detail d) {
        var s = d.summary();
        AuditActorView actor = new AuditActorView(s.actorType(), s.actorUserId(), s.actorRole());
        AuditEntityView entity = new AuditEntityView(s.entityType(), s.entityId());
        AuditClientInfoView clientInfo = d.clientInfo() == null ? null
                : new AuditClientInfoView(d.clientInfo().ipMasked(), d.clientInfo().userAgent());
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
                d.beforeState(),
                d.afterState(),
                clientInfo
        );
    }
}
