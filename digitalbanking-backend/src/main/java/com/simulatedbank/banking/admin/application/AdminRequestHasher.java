package com.simulatedbank.banking.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.admin.domain.AdminText;
import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.LimitChangePayload;
import com.simulatedbank.banking.admin.domain.RoleChangePayload;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * BE-ADM-CON-03: deterministic canonical request hash for Admin idempotency.
 *
 * <p>Rules (07 §5.2, design/02 §6.2):</p>
 * <ul>
 *   <li>Normalize first: IDs are decimal strings, money is scale-2 plain string, reason/decisionNote are
 *       strip-normalized with {@code null} and blank both becoming the empty string, currency is the validated
 *       uppercase value, instants are ISO-8601 UTC.</li>
 *   <li>Serialize the fixed logical object through RFC 8785 JCS, then
 *       {@code lowercase_hex(SHA-256(UTF-8(JCS(payload))))}.</li>
 *   <li>Include actor, operation and the business payload. Exclude request timestamps, correlation/request IDs,
 *       challenge IDs and any header. <b>Exclude server-captured {@code expectedVersion}</b>: a retry of the same
 *       client request must replay even when the target's role version moved on between attempts.</li>
 * </ul>
 *
 * <p>Domain separator {@link #HASH_VERSION} must be bumped whenever the canonical field set changes.</p>
 */
@Component
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
public class AdminRequestHasher {

    public static final String HASH_VERSION = "ADM-IDEMPOTENCY-V1";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JcsCanonicalizer canonicalizer;

    public AdminRequestHasher(JcsCanonicalizer canonicalizer) {
        this.canonicalizer = canonicalizer;
    }

    /** Canonical hash of a role proposal; {@code expectedVersion} is deliberately not a field. */
    public String roleProposalHash(long actorId, RoleChangePayload payload) {
        requireActor(actorId);
        Map<String, Object> canonical = base(actorId, "CREATE_ROLE_PROPOSAL");
        canonical.put("requestType", payload.requestType().name());
        canonical.put("targetType", payload.targetType().name());
        canonical.put("targetId", payload.targetId());
        canonical.put("changeOperation", payload.operation().name());
        canonical.put("targetUserId", Long.toString(payload.targetUserId()));
        canonical.put("roleCode", payload.roleCode());
        canonical.put("privilegeCode", payload.privilegeCode());
        canonical.put("reason", payload.reason() == null ? "" : payload.reason());
        return hash(canonical);
    }

    /** Canonical hash of a limit proposal (no server-captured fields exist in the approved payload). */
    public String limitProposalHash(long actorId, LimitChangePayload payload) {
        requireActor(actorId);
        Map<String, Object> canonical = base(actorId, "CREATE_LIMIT_PROPOSAL");
        canonical.put("requestType", payload.requestType().name());
        canonical.put("targetType", payload.targetType().name());
        canonical.put("targetId", payload.targetId());
        canonical.put("customerId", Long.toString(payload.customerId()));
        canonical.put("accountId", payload.accountId() == null ? null : Long.toString(payload.accountId()));
        canonical.put("perTransactionLimit", payload.perTransactionLimit().toPlainString());
        canonical.put("dailyLimit", payload.dailyLimit().toPlainString());
        canonical.put("currency", payload.currency());
        canonical.put("effectiveFrom", payload.effectiveFrom() == null ? null : payload.effectiveFrom().toString());
        canonical.put("reason", payload.reason() == null ? "" : payload.reason());
        return hash(canonical);
    }

    /** Canonical hash of a checker decision. The challenge reference is proof material, never business payload. */
    public String decisionHash(long actorId, long approvalId, ApprovalDecision decision, String decisionNote) {
        requireActor(actorId);
        if (approvalId <= 0) {
            throw new IllegalArgumentException("approvalId must be positive");
        }
        if (decision == null) {
            throw new IllegalArgumentException("decision is required");
        }
        String note = AdminText.optional(decisionNote, 500);
        Map<String, Object> canonical = base(actorId, "DECIDE_APPROVAL");
        canonical.put("approvalId", Long.toString(approvalId));
        canonical.put("decision", decision.name());
        canonical.put("decisionNote", note == null ? "" : note);
        return hash(canonical);
    }

    private static Map<String, Object> base(long actorId, String operation) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("version", HASH_VERSION);
        canonical.put("operation", operation);
        canonical.put("actorId", Long.toString(actorId));
        return canonical;
    }

    private String hash(Map<String, Object> canonical) {
        try {
            byte[] jcs = canonicalizer.canonicalize(JSON.writeValueAsString(canonical));
            return HmacService.sha256Hex(jcs);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            // The map is server-built; never echo values or parser diagnostics.
            throw new IllegalStateException("Admin request is not canonicalizable");
        }
    }

    private static void requireActor(long actorId) {
        if (actorId <= 0) {
            throw new IllegalArgumentException("actorId must be positive");
        }
    }
}
