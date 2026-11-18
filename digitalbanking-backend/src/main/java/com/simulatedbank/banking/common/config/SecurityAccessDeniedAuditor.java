package com.simulatedbank.banking.common.config;

import com.simulatedbank.banking.common.context.CorrelationAndRequestIdFilter;
import com.simulatedbank.banking.common.context.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Audit C3 cho các lượt truy cập bị từ chối ở security chain dùng chung (`SECURITY_ACCESS_DENIED`,
 * design/01. audit-traceability.md §5.2/§5.3/§5.6 — outcome `REJECTED`, best-effort, không bao giờ
 * đổi response 403 thành 500 hay rollback gì cả).
 *
 * <p>Nguồn sự kiện: {@code AuthorizationDeniedEvent} mà Spring Security phát mặc định cho các
 * quyết định DENY của {@code AuthorizationFilter} (cấu hình trong
 * {@link SecurityFilterChainConfig#securityFilterChain}). Cơ chế emit tạm thời giống hợp đồng
 * đã duyệt của identity (DEC-ID-003/BE-ID-IMP-13): security log + metric, persistence
 * `audit_log` thuộc module audit chưa có → thay adapter thật khi module audit hoàn tất.</p>
 *
 * <p>Ranh giới: KHÔNG import module nghiệp vụ (BE-CON-08/18); entity type suy ra bằng bảng ánh
 * xạ đóng tĩnh theo tài nguyên trong path (`customers` → `CUSTOMER`, …), không suy diễn gì thêm;
 * không log PII, token, ciphertext hay user-agent. `actor_user_id` là ID số của principal tin cậy
 * (snapshot {@link SessionAuthenticator.Session} do {@code SessionAuthenticationFilter} đặt). Sự
 * kiện 401 và các lỗi validation không phát sự kiện này (design/01 §5.7).</p>
 */
@Component
public class SecurityAccessDeniedAuditor {

    private static final Logger LOG = LoggerFactory.getLogger("com.simulatedbank.banking.common.AUDIT");

    /** Bảng ánh xạ đóng: tài nguyên REST → `entity_type` theo design/01 §6.3. */
    private static final Map<String, String> ENTITY_TYPES = Map.ofEntries(
            Map.entry("customers", "CUSTOMER"),
            Map.entry("accounts", "ACCOUNT"),
            Map.entry("transfers", "TRANSFER"),
            Map.entry("transactions", "TRANSFER"),
            Map.entry("limits", "LIMIT_CONFIG"),
            Map.entry("audit", "AUDIT_LOG"),
            Map.entry("approvals", "APPROVAL_REQUEST"),
            Map.entry("fraud-flags", "FRAUD_FLAG"),
            Map.entry("challenges", "AUTH_CHALLENGE"),
            Map.entry("session", "SESSION"));

    /**
     * Tài nguyên ghi của Admin (`POST /api/v1/admin/limits|roles|approvals[/{id}][/approve]`).
     * Theo design/01 §6.3, entity cho limit/role change là {@code APPROVAL_REQUEST} với
     * `entity_id = approval_id`; không có entity `ROLE` trong catalog nên không được phát ra.
     */
    private static final Set<String> ADMIN_APPROVAL_RESOURCES = Set.of("limits", "roles", "approvals");

    private final MeterRegistry meterRegistry;

    public SecurityAccessDeniedAuditor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @EventListener
    public void onAuthorizationDenied(AuthorizationDeniedEvent<?> event) {
        try {
            record(event);
        } catch (RuntimeException failure) {
            // C3 best-effort: sự kiện audit không được làm hỏng response 403 của chain, nhưng không được im lặng —
            // metric failure + security log thay cho audit record (design/01 §5.2).
            counter("security.audit.failures", "action", "SECURITY_ACCESS_DENIED", "outcome", "WRITE_FAILED");
            LOG.atError()
                    .addKeyValue("action", "SECURITY_ACCESS_DENIED")
                    .addKeyValue("outcome", "WRITE_FAILED")
                    .log("SECURITY_AUDIT_WRITE_FAILED");
        }
    }

    private void record(AuthorizationDeniedEvent<?> event) {
        // Chỉ audit 403 sau xác thực: principal phải là snapshot Session tin cậy. Deny cho caller
        // vô danh (→ 401 entry point) và mọi lỗi trước AuthorizationFilter không thuộc sự kiện này
        // (design/01 §5.7: 401 không audit; validation error chỉ technical log).
        Authentication authentication = event.getAuthentication().get();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof SessionAuthenticator.Session session)) {
            return;
        }
        Long actorUserId = Long.parseLong(session.userId());
        String actorRoles = rolesOf(session.authorities());
        HttpServletRequest request = event.getObject() instanceof HttpServletRequest servlet ? servlet : null;
        String[] entity = request != null ? resolveEntity(request.getRequestURI())
                : new String[] {"UNKNOWN", null};
        RequestContext context = request != null
                && request.getAttribute(CorrelationAndRequestIdFilter.CONTEXT_ATTRIBUTE) instanceof RequestContext snapshot
                        ? snapshot : null;
        counter("security.audit.events", "action", "SECURITY_ACCESS_DENIED", "outcome", "REJECTED",
                "entity_type", entity[0]);
        LOG.atInfo()
                .addKeyValue("action", "SECURITY_ACCESS_DENIED")
                .addKeyValue("outcome", "REJECTED")
                .addKeyValue("actor_user_id", actorUserId)
                .addKeyValue("actor_roles", actorRoles)
                .addKeyValue("entity_type", entity[0])
                .addKeyValue("entity_id", entity[1])
                .addKeyValue("reason_code", "AUTH_FORBIDDEN")
                .addKeyValue("correlation_id", context != null ? context.correlationId() : null)
                .addKeyValue("request_id", context != null ? context.requestId() : null)
                .log("SECURITY_AUDIT");
    }

    private static String rolesOf(Set<String> authorities) {
        Set<String> roles = new LinkedHashSet<>();
        for (String authority : authorities) {
            if (authority.startsWith("ROLE_")) {
                roles.add(authority.substring("ROLE_".length()));
            }
        }
        return String.join(",", roles);
    }

    /**
     * Phân rã path cuối cùng của URI (không chứa query/header): segment số dương là `entity_id`,
     * segment đứng trước là tài nguyên ánh xạ qua {@link #ENTITY_TYPES}; tài nguyên không nằm
     * trong bảng ánh xạ → `UNKNOWN` (label metric phải bounded, không bao giờ chứa input client).
     *
     * <p>Nhánh Admin (`/api/v1/admin/{limits|roles|approvals}[/{approvalId}][/approve]`) luôn trả
     * `APPROVAL_REQUEST` + `approval_id` đúng design/01 §6.3, kể cả khi path kết thúc bằng
     * `approve`; không phát entity `ROLE`/`UNKNOWN` cho các lượt deny của workflow duyệt.</p>
     */
    private static String[] resolveEntity(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return new String[] {"UNKNOWN", null};
        }
        String path = requestUri.indexOf('?') >= 0 ? requestUri.substring(0, requestUri.indexOf('?')) : requestUri;
        String[] segments = path.split("/");
        if (segments.length < 2) {
            return new String[] {"UNKNOWN", null};
        }
        if (isAdminApprovalPath(segments)) {
            return new String[] {"APPROVAL_REQUEST", lastNumericSegment(segments)};
        }
        String last = segments[segments.length - 1];
        String entityId = last.matches("[1-9][0-9]{0,18}") ? last : null;
        String resource = entityId != null ? segments[segments.length - 2] : last;
        return new String[] {ENTITY_TYPES.getOrDefault(resource, "UNKNOWN"), entityId};
    }

    /** `/api/v1/admin/{limits|roles|approvals}…` — workflow duyệt Admin (design/01 §6.3). */
    private static boolean isAdminApprovalPath(String[] segments) {
        return segments.length > 4
                && "api".equals(segments[1]) && "v1".equals(segments[2]) && "admin".equals(segments[3])
                && ADMIN_APPROVAL_RESOURCES.contains(segments[4]);
    }

    private static String lastNumericSegment(String[] segments) {
        for (int index = segments.length - 1; index > 3; index--) {
            if (segments[index].matches("[1-9][0-9]{0,18}")) {
                return segments[index];
            }
        }
        return null;
    }

    private void counter(String name, String... tags) {
        try {
            meterRegistry.counter(name, tags).increment();
        } catch (RuntimeException metricsFailure) {
            LOG.error("AUDIT_METRIC_WRITE_FAILED meter={}", name);
        }
    }
}
