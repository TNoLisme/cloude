package com.simulatedbank.banking.admin.application.query;

import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import java.time.Instant;

/**
 * Raw list filters of {@code API-APR-01} before defaulting/validation (07 §8.8.3).
 *
 * <p>Nullable values mean "not supplied". {@code makerId}/{@code checkerId} are optional documented
 * filters, never authorization: {@link ApprovalVisibility} is derived server-side and applied inside the
 * same SQL predicate, so these filters can only narrow the visible set.</p>
 */
public record ApprovalListQuery(
        ApprovalRequestType requestType,
        ApprovalStatus status,
        Long makerId,
        Long checkerId,
        Instant from,
        Instant to,
        Integer page,
        Integer size,
        String sort) {
}
