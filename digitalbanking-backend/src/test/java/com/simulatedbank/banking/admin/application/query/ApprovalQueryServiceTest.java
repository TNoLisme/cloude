package com.simulatedbank.banking.admin.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simulatedbank.banking.admin.application.port.ApprovalRequestReader;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** BE-ADM-DB-02: query defaulting, allowlist and bounds without touching persistence. */
class ApprovalQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final ApprovalVisibility VISIBILITY =
            new ApprovalVisibility(77L, java.util.Set.of(ApprovalRequestType.ROLE_CHANGE));

    private final ApprovalRequestReader reader = mock(ApprovalRequestReader.class);
    private ApprovalQueryService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalQueryService(reader, Clock.fixed(NOW, ZoneOffset.UTC));
        when(reader.findPage(any())).thenReturn(new ApprovalPage(List.of(), 0, 20, 0, 0));
        when(reader.findDetail(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(java.util.Optional.empty());
    }

    @Test
    void defaultsUseDocumentedWindowPageAndSort() {
        assertThat(service.list(new ApprovalListQuery(null, null, null, null, null, null, null, null, null),
                VISIBILITY))
                .isEqualTo(new ApprovalPage(List.of(), 0, 20, 0, 0));

        ApprovalCriteria criteria = capturedCriteria();
        assertThat(criteria.requestType()).isNull();
        assertThat(criteria.status()).isNull();
        assertThat(criteria.makerId()).isNull();
        assertThat(criteria.checkerId()).isNull();
        assertThat(criteria.from()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        assertThat(criteria.to()).isEqualTo(NOW);
        assertThat(criteria.page()).isZero();
        assertThat(criteria.size()).isEqualTo(20);
        assertThat(criteria.sort()).isEqualTo(new ApprovalSort(ApprovalSortField.CREATED_AT,
                ApprovalSortDirection.DESC));
    }

    @Test
    void fromDefaultsRelativeToExplicitTo() {
        Instant to = NOW.minus(Duration.ofDays(5));

        service.list(new ApprovalListQuery(null, null, null, null, null, to, null, null, null), VISIBILITY);

        ApprovalCriteria criteria = capturedCriteria();
        assertThat(criteria.to()).isEqualTo(to);
        assertThat(criteria.from()).isEqualTo(to.minus(Duration.ofDays(30)));
    }

    @Test
    void explicitFiltersPageAndAscendingSortArePassedThrough() {
        Instant from = NOW.minus(Duration.ofDays(1));
        service.list(new ApprovalListQuery(ApprovalRequestType.ROLE_CHANGE, ApprovalStatus.PENDING, 11L, 12L,
                from, NOW, 2, 5, "createdAt,asc"), VISIBILITY);

        ApprovalCriteria criteria = capturedCriteria();
        assertThat(criteria.requestType()).isEqualTo(ApprovalRequestType.ROLE_CHANGE);
        assertThat(criteria.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(criteria.makerId()).isEqualTo(11L);
        assertThat(criteria.checkerId()).isEqualTo(12L);
        assertThat(criteria.from()).isEqualTo(from);
        assertThat(criteria.page()).isEqualTo(2);
        assertThat(criteria.size()).isEqualTo(5);
        assertThat(criteria.sort()).isEqualTo(new ApprovalSort(ApprovalSortField.CREATED_AT,
                ApprovalSortDirection.ASC));
    }

    @Test
    void blankSortFallsBackToDefaultButMalformedOrUnknownSortIsRejected() {
        service.list(new ApprovalListQuery(null, null, null, null, null, null, null, null, "  "), VISIBILITY);
        assertThat(capturedCriteria().sort()).isEqualTo(ApprovalSort.DEFAULT);

        assertValidationRejected(new ApprovalListQuery(null, null, null, null, null, null, null, null, "status,desc"));
        assertValidationRejected(new ApprovalListQuery(null, null, null, null, null, null, null, null, "CreatedAt,desc"));
        assertValidationRejected(new ApprovalListQuery(null, null, null, null, null, null, null, null, "createdAt"));
        assertValidationRejected(new ApprovalListQuery(null, null, null, null, null, null, null, null,
                "createdAt,sideways"));
        assertValidationRejected(new ApprovalListQuery(null, null, null, null, null, null, null, null,
                "createdAt,desc,extra"));
        assertValidationRejected(new ApprovalListQuery(null, null, null, null, null, null, null, null, ",desc"));
    }

    @Test
    void pageAndSizeMustStayInsideDocumentedBounds() {
        assertValidationRejected(new ApprovalListQuery(null, null, null, null, null, null, -1, null, null));
        assertValidationRejected(new ApprovalListQuery(null, null, null, null, null, null, null, 0, null));
        assertValidationRejected(new ApprovalListQuery(null, null, null, null, null, null, null, 101, null));

        service.list(new ApprovalListQuery(null, null, null, null, null, null, 0, 100, null), VISIBILITY);
        assertThat(capturedCriteria().size()).isEqualTo(100);
    }

    @Test
    void timeWindowMustBeOrderedAndAtMost366Days() {
        assertValidationRejected(new ApprovalListQuery(null, null, null, null, NOW, NOW, null, null, null));
        assertValidationRejected(new ApprovalListQuery(null, null, null, null, NOW, NOW.minusSeconds(1),
                null, null, null));
        assertValidationRejected(new ApprovalListQuery(null, null, null, null,
                NOW.minus(Duration.ofDays(367)), NOW, null, null, null));

        service.list(new ApprovalListQuery(null, null, null, null, NOW.minus(Duration.ofDays(366)), NOW,
                null, null, null), VISIBILITY);
    }

    @Test
    void onlyRequestTypesOpenInTheApiAreAccepted() {
        assertValidationRejected(new ApprovalListQuery(ApprovalRequestType.RETENTION_HOLD, null, null, null,
                null, null, null, null, null));
        assertValidationRejected(new ApprovalListQuery(ApprovalRequestType.RETENTION_POLICY_CHANGE, null, null,
                null, null, null, null, null, null));
    }

    @Test
    void identifierFiltersMustBePositive() {
        assertValidationRejected(new ApprovalListQuery(null, null, 0L, null, null, null, null, null, null));
        assertValidationRejected(new ApprovalListQuery(null, null, null, -1L, null, null, null, null, null));
    }

    @Test
    void detailRejectsInvalidIdAndReturnsReaderResult() {
        assertThatThrownBy(() -> service.findDetail(0, VISIBILITY))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThat(service.findDetail(9L, VISIBILITY)).isEmpty();
        verify(reader).findDetail(9L, VISIBILITY);
    }

    @Test
    void actorVisibilityIsCarriedIntoTheQueryAndCannotBeDropped() {
        service.list(new ApprovalListQuery(null, null, null, null, null, null, null, null, null), VISIBILITY);

        ApprovalCriteria criteria = capturedCriteria();
        assertThat(criteria.visibility()).isEqualTo(VISIBILITY);
        assertThat(criteria.visibility().actorUserId()).isEqualTo(77L);
        assertThat(criteria.visibility().canReview(ApprovalRequestType.ROLE_CHANGE)).isTrue();
        assertThat(criteria.visibility().canReview(ApprovalRequestType.LIMIT_CHANGE)).isFalse();
        assertThatThrownBy(() -> service.list(
                new ApprovalListQuery(null, null, null, null, null, null, null, null, null), null))
                .isInstanceOf(NullPointerException.class);
    }

    private ApprovalCriteria capturedCriteria() {
        ArgumentCaptor<ApprovalCriteria> captor = ArgumentCaptor.forClass(ApprovalCriteria.class);
        verify(reader, org.mockito.Mockito.atLeastOnce()).findPage(captor.capture());
        return captor.getValue();
    }

    private void assertValidationRejected(ApprovalListQuery query) {
        org.mockito.Mockito.clearInvocations(reader);
        assertThatThrownBy(() -> service.list(query, VISIBILITY))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(reader, never()).findPage(any());
    }
}
