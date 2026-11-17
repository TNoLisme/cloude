package com.simulatedbank.banking.identity.api;

import java.util.Optional;

/**
 * Minimal Identity read contract for Admin approval projections (07 §7.2.14 `ActorSummary.displayName`).
 *
 * <p>Identity remains the only owner of {@code app_user}. Admin must not query the table or import Identity
 * internals; it asks for the safe staff display name (the login username, matching {@code API-AUTH-07}). The
 * operation is read-only and returns empty for an unknown/non-staff user so a historical approval can still be
 * rendered with an opaque user id.</p>
 */
public interface IdentityUserQuery {

    /** Staff-only display name; never customer PII, full name, phone, email or credential material. */
    Optional<String> findStaffDisplayName(long userId);
}
