package com.simulatedbank.banking.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.simulatedbank.banking.audit.application.port.AuditQueryPort;
import com.simulatedbank.banking.audit.domain.AuditSearchQuery;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditQueryServiceTest {

    @Mock
    private AuditQueryPort port;

    private Clock clock;
    private AuditQueryService service;
    private ObjectMapper mapper;

    private static final Instant FIXED_NOW = Instant.parse("2026-11-14T10:00:00Z");

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new AuditQueryService(port, clock);
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
    }

    @Test
    void searchMapsToAuditPageViewWithStructuredActorAndEntity() {
        var query = AuditSearchQuery.create(null, null, 0, 20, null, FIXED_NOW);
        var summary = new AuditQueryPort.AuditRecordSummary("42", FIXED_NOW.minusSeconds(60), FIXED_NOW,
                "USER", "7", "CUSTOMER", "TRANSFER_CREATED", "TRANSFER", "TXN123", "150.00", "VND",
                "corr-1", "SUCCESS", "0".repeat(64), "a".repeat(64), 1);
        var page = new AuditQueryPort.AuditRecordPage(List.of(summary), 0, 20, 1L, 1, true, true, FIXED_NOW);

        when(port.search(eq(query), eq(FIXED_NOW))).thenReturn(page);

        var result = service.search(query);

        assertThat(result.asOf()).isEqualTo(FIXED_NOW);
        assertThat(result.page().number()).isZero();
        assertThat(result.page().size()).isEqualTo(20);
        assertThat(result.page().totalElements()).isEqualTo(1L);
        assertThat(result.page().totalPages()).isEqualTo(1);
        assertThat(result.page().first()).isTrue();
        assertThat(result.page().last()).isTrue();

        assertThat(result.items()).hasSize(1);
        var item = result.items().get(0);
        assertThat(item.auditId()).isEqualTo("42");
        assertThat(item.actor().type()).isEqualTo("USER");
        assertThat(item.actor().userId()).isEqualTo("7");
        assertThat(item.actor().role()).isEqualTo("CUSTOMER");
        assertThat(item.entity().type()).isEqualTo("TRANSFER");
        assertThat(item.entity().id()).isEqualTo("TXN123");
        assertThat(item.amount()).isEqualTo("150.00");
        assertThat(item.currency()).isEqualTo("VND");
        assertThat(item.correlationId()).isEqualTo("corr-1");
        assertThat(item.outcome()).isEqualTo("SUCCESS");
        assertThat(item.prevHash()).isEqualTo("0".repeat(64));
        assertThat(item.recordHash()).isEqualTo("a".repeat(64));
        assertThat(item.hashVersion()).isEqualTo(1);
    }

    @Test
    void getDetailReturnsFlattenedDetailViewMatchingOpenApiSchema() throws Exception {
        var summary = new AuditQueryPort.AuditRecordSummary("42", FIXED_NOW.minusSeconds(60), FIXED_NOW,
                "USER", "7", "CUSTOMER", "TRANSFER_CREATED", "TRANSFER", "TXN123", "150.00", "VND",
                "corr-1", "SUCCESS", "0".repeat(64), "a".repeat(64), 1);
        var detail = new AuditQueryPort.AuditRecordDetail(summary, "req-1", null, "{\"status\":\"PENDING\"}",
                "{\"ipMasked\":\"192.168.1.***\",\"userAgent\":\"Mozilla/5.0\"}");

        when(port.findDetail(42L)).thenReturn(Optional.of(detail));

        var result = service.getDetail(42L);

        // Top-level fields
        assertThat(result.auditId()).isEqualTo("42");
        assertThat(result.actor().role()).isEqualTo("CUSTOMER");
        assertThat(result.entity().id()).isEqualTo("TXN123");
        assertThat(result.requestId()).isEqualTo("req-1");
        assertThat(result.beforeState()).isNull();
        assertThat(result.afterState()).containsEntry("status", "PENDING");
        assertThat(result.clientInfo()).isNotNull();
        assertThat(result.clientInfo().ipMasked()).isEqualTo("192.168.1.***");
        assertThat(result.clientInfo().userAgent()).isEqualTo("Mozilla/5.0");

        // Convenience summary accessor
        assertThat(result.summary().auditId()).isEqualTo("42");
        assertThat(result.summary().action()).isEqualTo("TRANSFER_CREATED");

        // Contract check on actual serialized JSON output
        String json = mapper.writeValueAsString(result);
        JsonNode root = mapper.readTree(json);

        // All OpenAPI AuditSummary fields must be at root level
        assertThat(root.has("auditId")).isTrue();
        assertThat(root.has("occurredAt")).isTrue();
        assertThat(root.has("recordedAt")).isTrue();
        assertThat(root.has("actor")).isTrue();
        assertThat(root.has("action")).isTrue();
        assertThat(root.has("entity")).isTrue();
        assertThat(root.has("amount")).isTrue();
        assertThat(root.has("currency")).isTrue();
        assertThat(root.has("correlationId")).isTrue();
        assertThat(root.has("outcome")).isTrue();
        assertThat(root.has("recordHash")).isTrue();
        assertThat(root.has("prevHash")).isTrue();
        assertThat(root.has("hashVersion")).isTrue();

        // AuditDetail extensions must also be at root level
        assertThat(root.has("requestId")).isTrue();
        assertThat(root.has("beforeState")).isTrue();
        assertThat(root.has("afterState")).isTrue();
        assertThat(root.has("clientInfo")).isTrue();

        // Must NOT contain a nested "summary" object or raw JSON fields
        assertThat(root.has("summary")).isFalse();
        assertThat(root.has("beforeStateJson")).isFalse();
        assertThat(root.has("afterStateJson")).isFalse();
        assertThat(root.has("clientInfoMaskedJson")).isFalse();
    }

    @Test
    void auditRecordDetailConstructorIsPureDataRecordAndDoesNotParseOrValidateJson() {
        var summary = new AuditQueryPort.AuditRecordSummary("42", FIXED_NOW.minusSeconds(60), FIXED_NOW,
                "USER", "7", "CUSTOMER", "TRANSFER_CREATED", "UNKNOWN_ENTITY", "TXN123", "150.00", "VND",
                "corr-1", "SUCCESS", "0".repeat(64), "a".repeat(64), 1);
        // Malformed JSON strings and unknown entity passed to record constructor
        var rawDetail = new AuditQueryPort.AuditRecordDetail(summary, "req-1",
                "{malformed_json::", "{prohibited_key: 123}", "unmasked_ip:10.0.0.1");

        // Constructor does NOT throw and simply preserves the raw string payloads
        assertThat(rawDetail.summary().entityType()).isEqualTo("UNKNOWN_ENTITY");
        assertThat(rawDetail.beforeStateJson()).isEqualTo("{malformed_json::");
        assertThat(rawDetail.afterStateJson()).isEqualTo("{prohibited_key: 123}");
        assertThat(rawDetail.clientInfoMaskedJson()).isEqualTo("unmasked_ip:10.0.0.1");
    }

    @Test
    void getDetailThrowsInternalErrorWhenSnapshotContainsProhibitedField() {
        var summary = new AuditQueryPort.AuditRecordSummary("42", FIXED_NOW.minusSeconds(60), FIXED_NOW,
                "USER", "7", "CUSTOMER", "TRANSFER_CREATED", "TRANSFER", "TXN123", "150.00", "VND",
                "corr-1", "SUCCESS", "0".repeat(64), "a".repeat(64), 1);
        // customerName is not in TRANSFER allowlist
        var detail = new AuditQueryPort.AuditRecordDetail(summary, "req-1", null,
                "{\"status\":\"PENDING\",\"customerName\":\"Malicious Leak\"}",
                "{\"ipMasked\":\"192.168.1.***\",\"userAgent\":\"Mozilla/5.0\"}");

        when(port.findDetail(42L)).thenReturn(Optional.of(detail));

        assertThatThrownBy(() -> service.getDetail(42L))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> {
                    var b = (BankingBusinessException) ex;
                    assertThat(b.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(b.getMessage()).doesNotContain("Malicious Leak");
                });
    }

    @Test
    void getDetailThrowsInternalErrorWhenSnapshotContainsMalformedJson() {
        var summary = new AuditQueryPort.AuditRecordSummary("42", FIXED_NOW.minusSeconds(60), FIXED_NOW,
                "USER", "7", "CUSTOMER", "TRANSFER_CREATED", "TRANSFER", "TXN123", "150.00", "VND",
                "corr-1", "SUCCESS", "0".repeat(64), "a".repeat(64), 1);
        var detail = new AuditQueryPort.AuditRecordDetail(summary, "req-1", null,
                "{invalid_json_content",
                "{\"ipMasked\":\"192.168.1.***\",\"userAgent\":\"Mozilla/5.0\"}");

        when(port.findDetail(42L)).thenReturn(Optional.of(detail));

        assertThatThrownBy(() -> service.getDetail(42L))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> {
                    var b = (BankingBusinessException) ex;
                    assertThat(b.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(b.getMessage()).doesNotContain("invalid_json_content");
                });
    }

    @Test
    void getDetailThrowsInternalErrorWhenClientInfoContainsUnmaskedIp() {
        var summary = new AuditQueryPort.AuditRecordSummary("42", FIXED_NOW.minusSeconds(60), FIXED_NOW,
                "USER", "7", "CUSTOMER", "TRANSFER_CREATED", "TRANSFER", "TXN123", "150.00", "VND",
                "corr-1", "SUCCESS", "0".repeat(64), "a".repeat(64), 1);
        var detail = new AuditQueryPort.AuditRecordDetail(summary, "req-1", null,
                "{\"status\":\"PENDING\"}",
                "{\"ipMasked\":\"192.168.1.100\",\"userAgent\":\"Mozilla/5.0\"}");

        when(port.findDetail(42L)).thenReturn(Optional.of(detail));

        assertThatThrownBy(() -> service.getDetail(42L))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> {
                    var b = (BankingBusinessException) ex;
                    assertThat(b.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(b.getMessage()).doesNotContain("192.168.1.100");
                });
    }

    @Test
    void getDetailThrowsBankingBusinessExceptionWhenNotFound() {
        when(port.findDetail(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(999L))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> {
                    var business = (BankingBusinessException) ex;
                    assertThat(business.code()).isEqualTo(ErrorCode.AUDIT_NOT_FOUND);
                });
    }

    @Test
    void getDetailThrowsBankingBusinessExceptionWhenNegativeId() {
        assertThatThrownBy(() -> service.getDetail(-1L))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> {
                    var business = (BankingBusinessException) ex;
                    assertThat(business.code()).isEqualTo(ErrorCode.AUDIT_NOT_FOUND);
                });
    }

    @Test
    void findDetailReturnsEmptyWhenNotFound() {
        when(port.findDetail(999L)).thenReturn(Optional.empty());
        assertThat(service.findDetail(999L)).isEmpty();
        assertThat(service.findDetail(-1L)).isEmpty();
    }
}
