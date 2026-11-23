package com.simulatedbank.banking.admin.application.query;

import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import java.time.Instant;
import java.util.Objects;

/**
 * Validated, persistence-facing list criteria. All instants, page bounds and the sort are already
 * normalized; adapters never reinterpret client input.
 *
 * @param from       inclusive {@code created_at} lower bound
 * @param to         exclusive {@code created_at} upper bound
 * @param visibility server-derived actor scope; mandatory so no adapter can run an unscoped read
 */
public record ApprovalCriteria(
        ApprovalRequestType requestType,
        ApprovalStatus status,
        Long makerId,
        Long checkerId,
        Instant from,
        Instant to,
        int page,
        int size,
        ApprovalSort sort,
        ApprovalVisibility visibility) {

    public ApprovalCriteria {
        Objects.requireNonNull(from, "from is required");
        Objects.requireNonNull(to, "to is required");
        Objects.requireNonNull(sort, "sort is required");
        Objects.requireNonNull(visibility, "visibility is required");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > ApprovalQueryLimits.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size is outside the documented bounds");
        }
        if (requestType != null && !requestType.openInMvp()) {
            throw new IllegalArgumentException("request type is not open in the MVP API");
        }
        requirePositive(makerId);
        requirePositive(checkerId);
    }

    private static void requirePositive(Long id) {
        if (id != null && id <= 0) {
            throw new IllegalArgumentException("identifier filters must be positive");
        }
    }
}
