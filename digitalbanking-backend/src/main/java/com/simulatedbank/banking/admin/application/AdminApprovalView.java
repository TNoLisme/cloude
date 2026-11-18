package com.simulatedbank.banking.admin.application;

import com.simulatedbank.banking.admin.application.port.ApprovalPayloadView;
import com.simulatedbank.banking.admin.application.query.ApprovalDetailProjection;
import com.simulatedbank.banking.admin.application.query.ApprovalPage;
import com.simulatedbank.banking.admin.application.query.ApprovalSummaryProjection;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.common.logging.LogRedaction;
import com.simulatedbank.banking.identity.api.IdentityUserQuery;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Builds the public {@code ApprovalSummary}/{@code ApprovalDetail} maps once, inside the business transaction,
 * so the bytes stored in the idempotency record are exactly what a replay returns (design/02 §6.5).
 *
 * <p>Only approved business fields are emitted; {@code expectedVersion} is removed by {@link ApprovalPayloadView}.
 * Actor display names come from {@link IdentityUserQuery} (staff username, matching API-AUTH-07) with an opaque
 * user-id fallback if the historical user cannot be resolved.</p>
 */
@Component
@ConditionalOnProperty(name = {"app.admin.enabled", "app.identity.enabled"}, havingValue = "true",
        matchIfMissing = true)
public class AdminApprovalView {

    private final ApprovalPayloadView payloads;
    private final IdentityUserQuery users;

    public AdminApprovalView(ApprovalPayloadView payloads, IdentityUserQuery users) {
        this.payloads = payloads;
        this.users = users;
    }

    /** {@code ApprovalSummary} (07 §7.2.14). */
    public Map<String, Object> summary(ApprovalRequest approval) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approvalId", Long.toString(approval.approvalId()));
        body.put("requestType", approval.requestType().name());
        body.put("targetType", approval.targetType().name());
        body.put("targetId", approval.targetId());
        body.put("status", approval.status().name());
        body.put("maker", actor(approval.makerId()));
        body.put("checker", approval.checkerId() == null ? null : actor(approval.checkerId()));
        body.put("createdAt", text(approval.createdAt()));
        body.put("decidedAt", text(approval.decidedAt()));
        body.put("appliedAt", text(approval.appliedAt()));
        body.put("decisionNote", redactedText(approval.decisionNote()));
        body.put("correlationId", approval.correlationId());
        return Collections.unmodifiableMap(body);
    }

    /** {@code ApprovalDetail} = summary + masked typed payload + optional provider result. */
    public Map<String, Object> detail(ApprovalRequest approval, Map<String, Object> result) {
        Map<String, Object> body = new LinkedHashMap<>(summary(approval));
        body.put("payload", redactedPayload(payloads.publicPayload(approval.payload())));
        if (result != null) {
            body.put("result", redactedPayload(result));
        }
        return Collections.unmodifiableMap(body);
    }

    /** {@code API-APR-01} item from the read projection (payload is never loaded for a list page). */
    public Map<String, Object> summary(ApprovalSummaryProjection projection) {
        return summary(projection, new HashMap<>());
    }

    private Map<String, Object> summary(ApprovalSummaryProjection projection,
            Map<Long, Map<String, Object>> actors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approvalId", Long.toString(projection.approvalId()));
        body.put("requestType", projection.requestType().name());
        body.put("targetType", projection.targetType().name());
        body.put("targetId", projection.targetId());
        body.put("status", projection.status().name());
        body.put("maker", actor(projection.makerId(), actors));
        body.put("checker", projection.checkerId() == null ? null : actor(projection.checkerId(), actors));
        body.put("createdAt", text(projection.createdAt()));
        body.put("decidedAt", text(projection.decidedAt()));
        body.put("appliedAt", text(projection.appliedAt()));
        body.put("decisionNote", redactedText(projection.decisionNote()));
        body.put("correlationId", projection.correlationId());
        return Collections.unmodifiableMap(body);
    }

    /** {@code API-APR-02} detail; the projection payload is already the mask-safe public projection. */
    public Map<String, Object> detail(ApprovalDetailProjection projection) {
        Map<String, Object> body = new LinkedHashMap<>(summary(projection.summary()));
        body.put("payload", redactedPayload(projection.payload()));
        return Collections.unmodifiableMap(body);
    }

    /** {@code PageResponse<ApprovalSummary>} (07 §2.5): items + page metadata + snapshot instant. */
    public Map<String, Object> page(ApprovalPage page, Instant asOf) {
        // One display-name lookup per distinct actor for the whole page (no per-row Identity round trip).
        Map<Long, Map<String, Object>> actors = new HashMap<>();
        List<Map<String, Object>> items = page.items().stream()
                .map(item -> summary(item, actors))
                .toList();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("number", page.number());
        meta.put("size", page.size());
        meta.put("totalElements", page.totalElements());
        meta.put("totalPages", page.totalPages());
        meta.put("first", page.number() == 0);
        meta.put("last", page.totalPages() == 0 || page.number() >= page.totalPages() - 1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", Collections.unmodifiableMap(meta));
        body.put("asOf", text(asOf));
        return Collections.unmodifiableMap(body);
    }

    private Map<String, Object> actor(long userId) {
        return actor(userId, new HashMap<>());
    }

    private Map<String, Object> actor(long userId, Map<Long, Map<String, Object>> actors) {
        return actors.computeIfAbsent(userId, this::resolveActor);
    }

    private Map<String, Object> resolveActor(long userId) {
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("userId", Long.toString(userId));
        actor.put("displayName", users.findStaffDisplayName(userId).orElse(Long.toString(userId)));
        return Collections.unmodifiableMap(actor);
    }

    private static String text(Instant value) {
        return value == null ? null : value.toString();
    }

    /** Free-text notes are scrubbed with the same central rule as logs; approved text is unchanged. */
    private static String redactedText(String value) {
        return value == null ? null : LogRedaction.redactText(value);
    }

    /** Mask-safe copy of a payload/result map; non-string business values are preserved. */
    private static Map<String, Object> redactedPayload(Map<String, Object> values) {
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key,
                value instanceof String text ? LogRedaction.redactText(text) : value));
        return Collections.unmodifiableMap(copy);
    }
}
