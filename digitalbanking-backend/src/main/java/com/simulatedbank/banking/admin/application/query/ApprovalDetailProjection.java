package com.simulatedbank.banking.admin.application.query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Persistence projection for {@code ApprovalDetail} (07 §7.2.14): the summary plus the masked typed
 * payload. {@code result} has no canonical column and is not synthesized here.
 *
 * <p>{@code payload} is already the public, mask-safe projection produced by the Admin payload codec:
 * provider-captured {@code expectedVersion} is removed before the value leaves the adapter.</p>
 */
public record ApprovalDetailProjection(ApprovalSummaryProjection summary, Map<String, Object> payload) {

    public ApprovalDetailProjection {
        Objects.requireNonNull(summary, "summary is required");
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(payload, "payload is required")));
    }
}
