package com.simulatedbank.banking.admin.adapter.out;

import com.simulatedbank.banking.admin.application.query.ApprovalSummaryProjection;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Module-internal Spring Data access to Admin approval persistence. */
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
interface ApprovalRequestJpaRepository extends JpaRepository<ApprovalRequestEntity, Long> {

    /**
     * Waiting {@code SELECT … FOR UPDATE}. The approved approval concurrency contract is one winner and
     * {@code 409} for the loser after the winner commits (design/02 §7.9/§9.6), so this lock must not be
     * silently replaced by {@code NOWAIT}/{@code SKIP LOCKED}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from ApprovalRequestEntity request where request.approvalId = :approvalId")
    Optional<ApprovalRequestEntity> findByIdForUpdate(@Param("approvalId") long approvalId);

    /**
     * Maker-only scope (07 §8.8.3): the row must belong to the caller. The constructor expression selects only
     * summary columns, so the JSONB {@code payload} is never read for a list page. Callers supply an
     * allowlisted, deterministic {@link Pageable} sort.
     */
    @Query(value = """
            select new com.simulatedbank.banking.admin.application.query.ApprovalSummaryProjection(
                request.approvalId, request.requestType, request.targetType, request.targetId, request.status,
                request.makerId, request.checkerId, request.createdAt, request.decidedAt, request.appliedAt,
                request.decisionNote, request.correlationId)
            from ApprovalRequestEntity request
            where request.createdAt >= :from and request.createdAt < :to
              and request.makerId = :actorId
              and (:requestType is null or request.requestType = :requestType)
              and (:status is null or request.status = :status)
              and (:makerId is null or request.makerId = :makerId)
              and (:checkerId is null or request.checkerId = :checkerId)
            """, countQuery = """
            select count(request)
            from ApprovalRequestEntity request
            where request.createdAt >= :from and request.createdAt < :to
              and request.makerId = :actorId
              and (:requestType is null or request.requestType = :requestType)
              and (:status is null or request.status = :status)
              and (:makerId is null or request.makerId = :makerId)
              and (:checkerId is null or request.checkerId = :checkerId)
            """)
    Page<ApprovalSummaryProjection> findSummariesForMaker(
            @Param("requestType") ApprovalRequestType requestType,
            @Param("status") ApprovalStatus status,
            @Param("makerId") Long makerId,
            @Param("checkerId") Long checkerId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("actorId") long actorId,
            Pageable pageable);

    /**
     * Reviewer scope (07 §8.8.3): the caller's own requests plus all requests of the types the checker may
     * decide. The scope predicate is part of both the page and the count query, so pagination metadata cannot
     * leak records outside the scope.
     */
    @Query(value = """
            select new com.simulatedbank.banking.admin.application.query.ApprovalSummaryProjection(
                request.approvalId, request.requestType, request.targetType, request.targetId, request.status,
                request.makerId, request.checkerId, request.createdAt, request.decidedAt, request.appliedAt,
                request.decisionNote, request.correlationId)
            from ApprovalRequestEntity request
            where request.createdAt >= :from and request.createdAt < :to
              and (request.makerId = :actorId or request.requestType in :reviewableTypes)
              and (:requestType is null or request.requestType = :requestType)
              and (:status is null or request.status = :status)
              and (:makerId is null or request.makerId = :makerId)
              and (:checkerId is null or request.checkerId = :checkerId)
            """, countQuery = """
            select count(request)
            from ApprovalRequestEntity request
            where request.createdAt >= :from and request.createdAt < :to
              and (request.makerId = :actorId or request.requestType in :reviewableTypes)
              and (:requestType is null or request.requestType = :requestType)
              and (:status is null or request.status = :status)
              and (:makerId is null or request.makerId = :makerId)
              and (:checkerId is null or request.checkerId = :checkerId)
            """)
    Page<ApprovalSummaryProjection> findSummariesForReviewer(
            @Param("requestType") ApprovalRequestType requestType,
            @Param("status") ApprovalStatus status,
            @Param("makerId") Long makerId,
            @Param("checkerId") Long checkerId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("actorId") long actorId,
            @Param("reviewableTypes") Collection<ApprovalRequestType> reviewableTypes,
            Pageable pageable);

    /** Explicit detail projection of one approval made by the caller; empty when unknown or out of scope. */
    @Query("""
            select new com.simulatedbank.banking.admin.adapter.out.ApprovalDetailRow(
                request.approvalId, request.requestType, request.targetType, request.targetId, request.payload,
                request.status, request.makerId, request.checkerId, request.decisionNote, request.correlationId,
                request.createdAt, request.decidedAt, request.appliedAt)
            from ApprovalRequestEntity request
            where request.approvalId = :approvalId and request.makerId = :actorId
            """)
    Optional<ApprovalDetailRow> findDetailRowForMaker(@Param("approvalId") long approvalId,
            @Param("actorId") long actorId);

    /** Detail projection within reviewer scope; empty when unknown or out of scope (404 anti-enumeration). */
    @Query("""
            select new com.simulatedbank.banking.admin.adapter.out.ApprovalDetailRow(
                request.approvalId, request.requestType, request.targetType, request.targetId, request.payload,
                request.status, request.makerId, request.checkerId, request.decisionNote, request.correlationId,
                request.createdAt, request.decidedAt, request.appliedAt)
            from ApprovalRequestEntity request
            where request.approvalId = :approvalId
              and (request.makerId = :actorId or request.requestType in :reviewableTypes)
            """)
    Optional<ApprovalDetailRow> findDetailRowForReviewer(@Param("approvalId") long approvalId,
            @Param("actorId") long actorId,
            @Param("reviewableTypes") Collection<ApprovalRequestType> reviewableTypes);
}
