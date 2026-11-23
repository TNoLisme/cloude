package com.simulatedbank.banking.admin.application.query;

import java.util.List;
import java.util.Objects;

/**
 * One offset page of approval summaries. Page numbering is 0-based and {@link #totalPages()} is
 * {@code 0} for an empty result, matching {@code PageResponse.page} (07 §2.5/§7.2.5).
 */
public record ApprovalPage(
        List<ApprovalSummaryProjection> items,
        int number,
        int size,
        long totalElements,
        int totalPages) {

    public ApprovalPage {
        items = List.copyOf(Objects.requireNonNull(items, "items are required"));
        if (number < 0 || size < 1 || totalElements < 0 || totalPages < 0) {
            throw new IllegalArgumentException("Invalid page metadata");
        }
        if (items.size() > size) {
            throw new IllegalArgumentException("Page contains more items than its size");
        }
    }
}
