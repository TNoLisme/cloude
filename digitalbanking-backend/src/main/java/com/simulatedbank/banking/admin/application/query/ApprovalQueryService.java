package com.simulatedbank.banking.admin.application.query;

import com.simulatedbank.banking.admin.application.port.ApprovalRequestReader;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BE-ADM-DB-02/BE-ADM-API-03 read boundary for {@code API-APR-01}/{@code API-APR-02}.
 *
 * <p>Owns defaulting, the sort/field allowlist, page bounds and the read-only transaction. Actor visibility is
 * mandatory ({@link ApprovalVisibility}, DEC-ADM-009): the caller's own approvals plus, for a checker, the
 * request types they may decide. The predicate is pushed into SQL with the filters and the count, so
 * {@code makerId}/{@code checkerId} remain ordinary filters inside the scope and can never widen it.</p>
 *
 * <p>Rules implemented (07 §2.5/§7.2.5/§8.8.3):</p>
 * <ul>
 *   <li>{@code page} default 0, {@code size} default 20 and 1–100, 0-based; a page past the last one
 *       returns {@code items: []} without an error.</li>
 *   <li>{@code sort} default {@code createdAt,desc}; only {@code createdAt} is whitelisted.</li>
 *   <li>{@code from} inclusive / {@code to} exclusive; default window 30 days ending now, maximum
 *       span 366 days; {@code from >= to} is {@code 400 VALIDATION_ERROR}.</li>
 *   <li>Unknown or out-of-scope detail identifier returns empty; the API layer maps it to {@code 404
 *       APPROVAL_NOT_FOUND} without revealing scope.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
public class ApprovalQueryService {

    static final Duration DEFAULT_LOOKBACK = Duration.ofDays(30);
    static final Duration MAX_RANGE = Duration.ofDays(366);

    private final ApprovalRequestReader reader;
    private final Clock clock;

    public ApprovalQueryService(ApprovalRequestReader reader, Clock clock) {
        this.reader = Objects.requireNonNull(reader, "reader is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Transactional(readOnly = true)
    public ApprovalPage list(ApprovalListQuery query, ApprovalVisibility visibility) {
        Objects.requireNonNull(query, "query is required");
        Objects.requireNonNull(visibility, "visibility is required");
        return reader.findPage(criteria(query, visibility));
    }

    @Transactional(readOnly = true)
    public Optional<ApprovalDetailProjection> findDetail(long approvalId, ApprovalVisibility visibility) {
        Objects.requireNonNull(visibility, "visibility is required");
        if (approvalId <= 0) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return reader.findDetail(approvalId, visibility);
    }

    private ApprovalCriteria criteria(ApprovalListQuery query, ApprovalVisibility visibility) {
        ApprovalRequestType requestType = query.requestType();
        if (requestType != null && !requestType.openInMvp()) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Long makerId = positiveId(query.makerId());
        Long checkerId = positiveId(query.checkerId());

        Instant to = query.to() == null ? clock.instant() : query.to();
        Instant from = query.from() == null ? to.minus(DEFAULT_LOOKBACK) : query.from();
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }

        int page = query.page() == null ? ApprovalQueryLimits.DEFAULT_PAGE : query.page();
        if (page < 0) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        int size = query.size() == null ? ApprovalQueryLimits.DEFAULT_PAGE_SIZE : query.size();
        if (size < 1 || size > ApprovalQueryLimits.MAX_PAGE_SIZE) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }

        ApprovalSort sort = ApprovalSort.parse(query.sort());
        return new ApprovalCriteria(requestType, query.status(), makerId, checkerId,
                from, to, page, size, sort, visibility);
    }

    private static Long positiveId(Long value) {
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return value;
    }
}
