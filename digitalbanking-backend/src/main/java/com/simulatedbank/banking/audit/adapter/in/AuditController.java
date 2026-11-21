package com.simulatedbank.banking.audit.adapter.in;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.audit.api.AuditAction;
import com.simulatedbank.banking.audit.api.AuditActorType;
import com.simulatedbank.banking.audit.api.AuditEvent;
import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.audit.api.AuditOutcome;
import com.simulatedbank.banking.audit.application.AuditQueryService;
import com.simulatedbank.banking.audit.application.AuditQueryService.AuditSummaryView;
import com.simulatedbank.banking.audit.domain.AuditSearchQuery;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/audit")
@ConditionalOnProperty(name = "app.audit.enabled", havingValue = "true", matchIfMissing = true)
public class AuditController {
    private final AuditQueryService queries;
    private final AuditModuleApi audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate readSnapshot;
    private final TransactionTemplate auditWrite;

    public AuditController(AuditQueryService queries, AuditModuleApi audit, ObjectMapper json,
            Clock clock, PlatformTransactionManager transactions) {
        this.queries = queries;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        readSnapshot = new TransactionTemplate(transactions);
        readSnapshot.setReadOnly(true);
        readSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        auditWrite = new TransactionTemplate(transactions);
        auditWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Filters(Instant from, Instant to, String sort, String correlationId, String reference,
            Long actorUserId, String actorType, String action, String entityType, String entityId, String outcome) {
        AuditSearchQuery query(Integer page, Integer size, Instant now) {
            try {
                return AuditSearchQuery.create(from, to, page, size, sort, now,
                        correlationId, reference, actorUserId, actorType, action, entityType, entityId, outcome);
            } catch (IllegalArgumentException invalid) {
                throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
            }
        }
    }

    @GetMapping
    public AuditQueryService.AuditPageView search(@ModelAttribute Filters filters,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Instant now = clock.instant();
        return readSnapshot.execute(tx -> queries.search(filters.query(page, size, now), now));
    }

    @GetMapping("/{auditId}")
    public AuditQueryService.AuditDetailView get(@PathVariable long auditId) {
        if (auditId <= 0) throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        return queries.getDetail(auditId);
    }

    public enum Format { CSV, JSONL }

    @GetMapping("/export")
    @PreAuthorize("hasRole('AUDITOR') and hasAuthority('AUDIT_EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(@ModelAttribute Filters filters,
            @RequestParam(defaultValue = "CSV") Format format, @RequestParam(defaultValue = "10000") int maxRows,
            @AuthenticationPrincipal SessionAuthenticator.Session actor) throws IOException {
        if (maxRows < 1 || maxRows > 10000) throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        Instant now = clock.instant();
        var query = filters.query(0, 100, now);
        List<AuditSummaryView> records = readSnapshot.execute(tx -> exportRows(filters, maxRows, now));
        byte[] file = encode(format, records);
        String exportId = UUID.randomUUID().toString();
        var context = RequestContextHolder.current().orElseThrow();
        var state = Map.<String, Object>of("exportId", exportId, "rowCount", records.size(),
                "from", query.from().toString(), "to", query.to().toString(), "format", format.name(),
                "filterHash", HmacService.sha256Hex(json.writeValueAsBytes(query)),
                "fileHash", HmacService.sha256Hex(file));
        auditWrite.executeWithoutResult(tx -> audit.recordInTransaction(new AuditEvent(AuditAction.AUDIT_EXPORTED,
                AuditActorType.USER, Long.parseLong(actor.userId()), "AUDITOR", "AUDIT_LOG", exportId, null,
                state, null, null, AuditOutcome.SUCCESS, context.correlationId(), context.requestId(), null, now)));

        String suffix = format == Format.CSV ? "csv" : "jsonl";
        String mediaType = format == Format.CSV ? "text/csv" : "application/x-ndjson";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mediaType + "; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-" + exportId + "." + suffix + "\"")
                .contentLength(file.length).body(output -> output.write(file));
    }

    private List<AuditSummaryView> exportRows(Filters filters, int maxRows, Instant now) {
        var first = queries.search(filters.query(0, 100, now), now);
        if (first.page().totalElements() > maxRows) {
            throw new BankingBusinessException(ErrorCode.EXPORT_TOO_LARGE);
        }
        var rows = new ArrayList<>(first.items());
        for (int page = 1; page < first.page().totalPages(); page++) {
            rows.addAll(queries.search(filters.query(page, 100, now), now).items());
        }
        return rows;
    }

    private byte[] encode(Format format, List<AuditSummaryView> records) throws IOException {
        var file = new ByteArrayOutputStream();
        if (format == Format.CSV) {
            file.writeBytes(("auditId,occurredAt,recordedAt,actorType,actorUserId,actorRole,action,entityType,entityId,"
                    + "amount,currency,correlationId,outcome,recordHash,prevHash,hashVersion\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        for (AuditSummaryView record : records) {
            if (format == Format.JSONL) {
                file.write(json.writeValueAsBytes(record));
                file.write('\n');
            } else {
                file.write(csvRow(record).getBytes(StandardCharsets.UTF_8));
            }
        }
        return file.toByteArray();
    }

    private static String csvRow(AuditSummaryView row) {
        return Arrays.stream(new Object[]{row.auditId(), row.occurredAt(), row.recordedAt(), row.actor().type(),
                row.actor().userId(), row.actor().role(), row.action(), row.entity().type(), row.entity().id(),
                row.amount(), row.currency(), row.correlationId(), row.outcome(), row.recordHash(), row.prevHash(),
                row.hashVersion()}).map(AuditController::csvCell).collect(Collectors.joining(",", "", "\r\n"));
    }

    private static String csvCell(Object value) {
        String text = value == null ? "" : value.toString();
        String trimmed = text.stripLeading();
        if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
