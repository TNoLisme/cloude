package com.simulatedbank.banking.admin.application.stepup;

import com.simulatedbank.banking.admin.application.port.ApprovalPayloadCanonicalizer;
import com.simulatedbank.banking.admin.domain.AdminText;
import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * BE-ADM-CON-02: server-side binding of a {@code SENSITIVE_ACTION} step-up challenge to one checker decision.
 *
 * <p>Logical binding required by 07 §6.5 and {@code design/05} §5.2:</p>
 * <ul>
 *   <li>{@code purpose} — always {@code SENSITIVE_ACTION} (chosen by the server, never by the client).</li>
 *   <li>{@code bindingKey} — the persisted {@code approvalId} (07 §6.5 "gắn với approvalId").</li>
 *   <li>{@code checkerId} — authenticated checker; part of the hash, and the challenge row is owned by them.</li>
 *   <li>{@code requestHash} — SHA-256 over the canonical decision fields, including the immutable payload.</li>
 * </ul>
 *
 * <p>The hash is recomputed from trusted server state (the locked approval row, the authenticated principal and
 * the decision requested now). A client-supplied hash is never accepted as proof of binding.</p>
 *
 * <p>Canonical form: a fixed field order with length-prefixed UTF-8 values (so no field value can be re-split into
 * another field), the payload encoded with the Common RFC 8785 canonicalizer (so JSON field order never matters),
 * then SHA-256 hex — the same {@code HmacService.sha256Hex} convention the Identity challenge port validates
 * ({@code request_hash CHAR(64)} lower-case hex, see {@code OtpChallengeService}).</p>
 */
@Component
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
public class ApprovalStepUpBinding {

    /** Versioned domain separator; a change to the canonical field set must bump this value. */
    public static final String HASH_VERSION = "ADM-APPROVAL-STEPUP-V1";

    private final ApprovalPayloadCanonicalizer payloads;

    public ApprovalStepUpBinding(ApprovalPayloadCanonicalizer payloads) {
        this.payloads = payloads;
    }

    /** Binding key of a checker decision challenge: the persisted approval identifier. */
    public String bindingKey(long approvalId) {
        if (approvalId <= 0) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR, "approvalId là bắt buộc.");
        }
        return Long.toString(approvalId);
    }

    /**
     * Request hash of the checker decision bound to {@code approval}.
     *
     * @param approval       locked or freshly loaded approval; its payload is the authoritative request content
     * @param checkerId      authenticated checker (never a client field)
     * @param decision       decision this challenge authorizes
     * @param normalizedNote decision note already normalized exactly like the domain transition records it
     */
    public String decisionRequestHash(ApprovalRequest approval, long checkerId, ApprovalDecision decision,
            String normalizedNote) {
        Objects.requireNonNull(approval, "approval is required");
        Objects.requireNonNull(decision, "decision is required");
        if (approval.approvalId() <= 0) {
            // MOD-OI-10: a challenge must never be issued for a proposal that has no persisted identifier.
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR, "approvalId là bắt buộc.");
        }
        if (checkerId <= 0) {
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        String note = AdminText.optional(normalizedNote, 500);
        StringBuilder canonical = new StringBuilder(512);
        field(canonical, "v", HASH_VERSION);
        field(canonical, "approvalId", Long.toString(approval.approvalId()));
        field(canonical, "requestType", approval.requestType().name());
        field(canonical, "targetType", approval.targetType().name());
        field(canonical, "targetId", approval.targetId());
        field(canonical, "makerId", Long.toString(approval.makerId()));
        field(canonical, "payload", payloadText(approval));
        field(canonical, "checkerId", Long.toString(checkerId));
        field(canonical, "decision", decision.name());
        field(canonical, "decisionNote", note);
        return HmacService.sha256Hex(canonical.toString());
    }

    private String payloadText(ApprovalRequest approval) {
        byte[] canonical = payloads.canonicalJson(approval.payload());
        if (canonical == null) {
            throw new IllegalStateException("Approval payload canonicalizer returned no bytes");
        }
        return new String(canonical, StandardCharsets.UTF_8);
    }

    /** Length-prefixed field so values stay unambiguous even when they contain separators or newlines. */
    private static void field(StringBuilder target, String name, String value) {
        target.append(name).append('=');
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.length()).append(':').append(value);
        }
        target.append('\n');
    }
}
