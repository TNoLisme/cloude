package com.simulatedbank.banking.admin.application.stepup;

import com.simulatedbank.banking.admin.application.port.ApprovalPayloadCanonicalizer;
import com.simulatedbank.banking.admin.domain.ChangePayload;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * BE-ADM-CON-03: server-side binding of a Maker {@code SENSITIVE_ACTION} challenge to one concrete proposal.
 *
 * <p>Resolves MOD-OI-10 without inventing an approval identifier: the challenge is issued before the approval row
 * exists, so the binding uses the client's {@code Idempotency-Key} as {@code bindingKey} and a canonical hash of
 * the fully validated proposal (including the server-captured {@code expectedVersion}). The client cannot change
 * the target, values, maker or provider version between 428 and retry; any change produces a different hash and
 * Identity rejects consumption with {@code STEP_UP_CHALLENGE_MISMATCH}.</p>
 *
 * <p>This hash is intentionally separate from {@code AdminRequestHasher}: the idempotency hash excludes
 * {@code expectedVersion} so a retry can replay, while the step-up proof must bind the exact version the maker
 * authorized.</p>
 */
@Component
@ConditionalOnProperty(name = {"app.admin.enabled", "app.identity.enabled"}, havingValue = "true",
        matchIfMissing = true)
public class AdminProposalStepUpBinding {

    /** Versioned domain separator: changing the bound field set must bump this value. */
    public static final String HASH_VERSION = "ADM-PROPOSAL-STEPUP-V1";

    private static final int MAX_BINDING_KEY = 255;

    private final ApprovalPayloadCanonicalizer payloads;

    public AdminProposalStepUpBinding(ApprovalPayloadCanonicalizer payloads) {
        this.payloads = payloads;
    }

    /** Binding key of a proposal challenge; the idempotency key that scopes the retry. */
    public String bindingKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_BINDING_KEY) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return idempotencyKey;
    }

    /** Canonical step-up hash of one validated proposal payload for one maker. */
    public String proposalRequestHash(ChangePayload payload, long makerId) {
        Objects.requireNonNull(payload, "payload is required");
        if (makerId <= 0) {
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        StringBuilder canonical = new StringBuilder(512);
        field(canonical, "v", HASH_VERSION);
        field(canonical, "makerId", Long.toString(makerId));
        field(canonical, "requestType", payload.requestType().name());
        field(canonical, "targetType", payload.targetType().name());
        field(canonical, "targetId", payload.targetId());
        field(canonical, "payload", payloadText(payload));
        return HmacService.sha256Hex(canonical.toString());
    }

    private String payloadText(ChangePayload payload) {
        byte[] canonical = payloads.canonicalJson(payload);
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
