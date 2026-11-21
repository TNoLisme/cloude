package com.simulatedbank.banking.admin.application.port;

import com.simulatedbank.banking.admin.domain.ChangePayload;
import java.util.Map;

/**
 * Admin-owned public projection of an approval payload. It must contain only business fields approved for the
 * response (07 §7.2.14) and never provider entities, secrets, hashes or full PII.
 */
public interface ApprovalPayloadView {

    /** Ordered, mask-safe payload map used by {@code ApprovalDetail} and stored for idempotent replay. */
    Map<String, Object> publicPayload(ChangePayload payload);
}
