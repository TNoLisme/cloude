package com.simulatedbank.banking.admin.application.port;

import com.simulatedbank.banking.admin.domain.ChangePayload;

/**
 * Admin-owned canonical representation of an approval payload used by the step-up request hash.
 *
 * <p>The adapter must emit RFC 8785 (JCS) UTF-8 bytes so the same typed payload always produces the same
 * bytes regardless of map/JSON field order. The bytes never leave the hash computation and are never logged.</p>
 */
public interface ApprovalPayloadCanonicalizer {

    /** Canonical JSON bytes of the typed payload; the payload is already validated by its record constructor. */
    byte[] canonicalJson(ChangePayload payload);
}
