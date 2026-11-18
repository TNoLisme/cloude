package com.simulatedbank.banking.admin.application;

import com.simulatedbank.banking.admin.application.port.ApprovalPayloadView;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.identity.api.IdentityUserQuery;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
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
        body.put("decisionNote", approval.decisionNote());
        body.put("correlationId", approval.correlationId());
        return Collections.unmodifiableMap(body);
    }

    /** {@code ApprovalDetail} = summary + masked typed payload + optional provider result. */
    public Map<String, Object> detail(ApprovalRequest approval, Map<String, Object> result) {
        Map<String, Object> body = new LinkedHashMap<>(summary(approval));
        body.put("payload", payloads.publicPayload(approval.payload()));
        if (result != null) {
            body.put("result", Collections.unmodifiableMap(new LinkedHashMap<>(result)));
        }
        return Collections.unmodifiableMap(body);
    }

    private Map<String, Object> actor(long userId) {
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("userId", Long.toString(userId));
        actor.put("displayName", users.findStaffDisplayName(userId).orElse(Long.toString(userId)));
        return Collections.unmodifiableMap(actor);
    }

    private static String text(Instant value) {
        return value == null ? null : value.toString();
    }
}
