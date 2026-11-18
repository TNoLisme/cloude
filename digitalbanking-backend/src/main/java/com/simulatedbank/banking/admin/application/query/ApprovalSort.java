package com.simulatedbank.banking.admin.application.query;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.util.Objects;

/**
 * Validated sort of a pending/decided approval page.
 *
 * <p>Accepted wire values are exactly the documented {@code field,asc|desc} pairs (07 §2.3) whose
 * field belongs to the {@code /admin/approvals} allowlist (07 §2.5). An absent or blank parameter
 * uses the endpoint default {@code createdAt,desc}; any other field, direction or malformed pair is
 * {@code 400 VALIDATION_ERROR}.</p>
 */
public record ApprovalSort(ApprovalSortField field, ApprovalSortDirection direction) {

    /** Documented endpoint default (07 §2.5 lists {@code createdAt}; newest first). */
    public static final ApprovalSort DEFAULT = new ApprovalSort(ApprovalSortField.CREATED_AT,
            ApprovalSortDirection.DESC);

    public ApprovalSort {
        Objects.requireNonNull(field, "sort field is required");
        Objects.requireNonNull(direction, "sort direction is required");
    }

    /** Parses the client {@code sort} parameter; never accepts a field outside the allowlist. */
    public static ApprovalSort parse(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String[] parts = value.strip().split(",", -1);
        if (parts.length != 2) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        String fieldToken = parts[0].strip();
        String directionToken = parts[1].strip();
        if (fieldToken.isEmpty() || directionToken.isEmpty()) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return new ApprovalSort(
                ApprovalSortField.fromPublicKey(fieldToken),
                ApprovalSortDirection.fromToken(directionToken));
    }
}
