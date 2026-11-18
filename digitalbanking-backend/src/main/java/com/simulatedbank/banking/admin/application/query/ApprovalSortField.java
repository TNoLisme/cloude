package com.simulatedbank.banking.admin.application.query;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;

/**
 * Explicit sort allowlist for {@code API-APR-01} (07 §2.5: {@code /admin/approvals} accepts only
 * {@code createdAt}). The public key is mapped here to the JPA property so a client value is never
 * concatenated into JPQL/SQL.
 */
public enum ApprovalSortField {

    CREATED_AT("createdAt", "createdAt");

    private final String publicKey;
    private final String persistenceProperty;

    ApprovalSortField(String publicKey, String persistenceProperty) {
        this.publicKey = publicKey;
        this.persistenceProperty = persistenceProperty;
    }

    public String publicKey() {
        return publicKey;
    }

    /** Entity attribute used by Spring Data {@code Sort}; never derived from client input. */
    public String persistenceProperty() {
        return persistenceProperty;
    }

    /** Case-sensitive lookup (07 §2.3 enum/field comparison is case-sensitive). */
    public static ApprovalSortField fromPublicKey(String key) {
        for (ApprovalSortField field : values()) {
            if (field.publicKey.equals(key)) {
                return field;
            }
        }
        throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
    }
}
