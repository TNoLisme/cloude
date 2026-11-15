package com.simulatedbank.banking.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.simulatedbank.banking.account.api.AccountQueryPort;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.api.CustomerQueryPort;
import com.simulatedbank.banking.identity.api.IdentityRoleCommandPort;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "banking.modules.enabled", havingValue = "true", matchIfMissing = true)
public class ApprovalService {
    private static final Set<String> API_STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "APPLIED", "CANCELLED");

    private final JdbcTemplate jdbc;
    private final StepUpChallengePort stepUp;
    private final IdentityRoleCommandPort identityRoles;
    private final CustomerQueryPort customers;
    private final AccountQueryPort accounts;
    private final ObjectProvider<ApprovalAuditSink> audit;
    private final JcsCanonicalizer canonicalizer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApprovalService(JdbcTemplate jdbc, StepUpChallengePort stepUp, IdentityRoleCommandPort identityRoles,
            CustomerQueryPort customers, AccountQueryPort accounts, ObjectProvider<ApprovalAuditSink> audit,
            JcsCanonicalizer canonicalizer, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.stepUp = stepUp;
        this.identityRoles = identityRoles;
        this.customers = customers;
        this.accounts = accounts;
        this.audit = audit;
        this.canonicalizer = canonicalizer;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void requireStepUp(Actor actor, RequestType type, JsonNode binding, UUID challengeId) {
        authorizeCreate(actor, type);
        requireAvailableDependencies(type);
        requiredAudit();
        if (challengeId != null) return;
        String hash = requestHash(binding);
        StepUpChallengePort.Challenge challenge = stepUp.create(actor.userId(),
                StepUpChallengePort.Purpose.SENSITIVE_ACTION, hash, null);
        throw new BankingBusinessException(ErrorCode.STEP_UP_REQUIRED, ErrorCode.STEP_UP_REQUIRED.detail(),
                Map.of("challenge", challenge.publicView()));
    }

    @Transactional
    public ApprovalSummary create(Actor maker, RequestType type, String targetType, String targetId,
            JsonNode payload, UUID challengeId) {
        authorizeCreate(maker, type);
        validateRequest(type, targetType, targetId, payload, maker.userId());
        requireAvailableDependencies(type);
        ApprovalAuditSink auditSink = requiredAudit();
        String hash = requestHash(binding("CREATE", type.name(), targetId, payload));
        long consumedChallengeId = stepUp.consumeVerifiedChallenge(challengeId, maker.userId(),
                StepUpChallengePort.Purpose.SENSITIVE_ACTION, hash);
        Long approvalId;
        try {
            approvalId = jdbc.queryForObject("""
                    INSERT INTO banking.approval_request(request_type, target_type, target_id, payload, maker_id,
                        step_up_challenge_id, correlation_id)
                    VALUES (?, ?, ?, ?::jsonb, ?, ?, ?) RETURNING approval_id
                    """, Long.class, type.name(), targetType, targetId, json(payload), maker.userId(),
                    consumedChallengeId, correlationId());
        } catch (DataIntegrityViolationException conflict) {
            throw new BankingBusinessException(ErrorCode.APPROVAL_ALREADY_DECIDED);
        }
        auditSink.recordInTransaction(event(maker.userId(), type.name() + "_REQUESTED", approvalId, "SUCCESS"));
        return toSummary(getUnchecked(approvalId));
    }

    @Transactional
    public ApprovalDetail decide(long approvalId, RequestType expectedType, Actor checker, Decision decision,
            String note, UUID challengeId) {
        authorizeDecision(checker, expectedType);
        requireAvailableDependencies(expectedType);
        ApprovalAuditSink auditSink = requiredAudit();
        ApprovalRow approval = lock(approvalId);
        if (approval.requestType() != expectedType) {
            throw new BankingBusinessException(ErrorCode.APPROVAL_NOT_FOUND);
        }
        if (!"PENDING".equals(approval.status())) {
            throw new BankingBusinessException(ErrorCode.APPROVAL_ALREADY_DECIDED);
        }
        Instant now = clock.instant();
        if (approval.expiresAt() != null && !now.isBefore(approval.expiresAt())) {
            throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (approval.makerId() == checker.userId()) {
            auditC3(checker.userId(), "SECURITY_ACCESS_DENIED", approvalId, "REJECTED");
            throw new BankingBusinessException(ErrorCode.ROLE_CONFLICT);
        }
        String hash = requestHash(binding("DECIDE_" + decision.name(), expectedType.name(),
                Long.toString(approvalId), objectMapper.valueToTree(Map.of("decision", decision.name(),
                        "decisionNote", note == null ? "" : note))));
        long consumedChallengeId = stepUp.consumeVerifiedChallenge(challengeId, checker.userId(),
                StepUpChallengePort.Purpose.SENSITIVE_ACTION, hash);
        if (decision == Decision.REJECT) {
            int updated = jdbc.update("""
                    UPDATE banking.approval_request SET status = 'REJECTED', checker_id = ?, decision_note = ?,
                        step_up_challenge_id = ?, decided_at = ? WHERE approval_id = ? AND status = 'PENDING'
                    """, checker.userId(), note, consumedChallengeId, dbTime(now), approvalId);
            if (updated != 1) throw new BankingBusinessException(ErrorCode.APPROVAL_ALREADY_DECIDED);
            auditSink.recordInTransaction(event(checker.userId(), expectedType.name() + "_REJECTED", approvalId,
                    "SUCCESS"));
            return toDetail(getUnchecked(approvalId));
        }

        JsonNode result = applyRoleChange(approval.payload(), checker.userId());
        int updated = jdbc.update("""
                UPDATE banking.approval_request SET status = 'APPLIED', checker_id = ?, decision_note = ?,
                    step_up_challenge_id = ?, result = ?::jsonb, decided_at = ?, applied_at = ?
                WHERE approval_id = ? AND status = 'PENDING'
                """, checker.userId(), note, consumedChallengeId, json(result), dbTime(now), dbTime(now), approvalId);
        if (updated != 1) throw new BankingBusinessException(ErrorCode.APPROVAL_ALREADY_DECIDED);
        auditSink.recordInTransaction(event(checker.userId(), expectedType.name() + "_APPROVED", approvalId,
                "SUCCESS"));
        return toDetail(getUnchecked(approvalId));
    }

    @Transactional(readOnly = true)
    public ApprovalDetail get(Actor actor, long approvalId) {
        ApprovalRow row = getUnchecked(approvalId);
        authorizeRead(actor, row);
        return toDetail(row);
    }

    @Transactional(readOnly = true)
    public ApprovalPage list(Actor actor, ApprovalFilter filter) {
        validateFilter(filter);
        requireApprovalReader(actor);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        addFilter(where, args, "request_type", filter.requestType());
        addFilter(where, args, "status", filter.status());
        addFilter(where, args, "maker_id", filter.makerId());
        addFilter(where, args, "checker_id", filter.checkerId());
        if (filter.from() != null) {
            where.append(" AND created_at >= ?");
            args.add(dbTime(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" AND created_at < ?");
            args.add(dbTime(filter.to()));
        }
        applyReadScope(actor, where, args);

        String order = "createdAt,asc".equals(filter.sort())
                ? " ORDER BY created_at ASC, approval_id ASC" : " ORDER BY created_at DESC, approval_id DESC";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(filter.size());
        pageArgs.add((long) filter.page() * filter.size());
        List<ApprovalSummary> items = jdbc.query("""
                SELECT approval_id, request_type, target_type, target_id, payload, result, status, maker_id,
                       checker_id, decision_note, correlation_id, created_at, decided_at, applied_at, expires_at
                FROM banking.approval_request
                """ + where + order + " LIMIT ? OFFSET ?", (rs, rowNum) -> toSummary(row(rs, rowNum)),
                pageArgs.toArray());
        Long total = jdbc.queryForObject("SELECT count(*) FROM banking.approval_request" + where,
                Long.class, args.toArray());
        int totalPages = Math.toIntExact((total + filter.size() - 1) / filter.size());
        return new ApprovalPage(items, new PageMeta(filter.page(), filter.size(), total, totalPages,
                filter.page() == 0, filter.page() + 1 >= totalPages), clock.instant());
    }

    private ApprovalRow lock(long approvalId) {
        try {
            return jdbc.queryForObject("""
                    SELECT approval_id, request_type, target_type, target_id, payload, result, status, maker_id,
                           checker_id, decision_note, correlation_id, created_at, decided_at, applied_at, expires_at
                    FROM banking.approval_request WHERE approval_id = ? FOR UPDATE NOWAIT
                    """, this::row, approvalId);
        } catch (EmptyResultDataAccessException absent) {
            throw new BankingBusinessException(ErrorCode.APPROVAL_NOT_FOUND);
        } catch (DataAccessException busy) {
            if (hasSqlState(busy, "55P03")) {
                throw new BankingBusinessException(ErrorCode.APPROVAL_ALREADY_DECIDED);
            }
            throw busy;
        }
    }

    private ApprovalRow getUnchecked(long approvalId) {
        try {
            return jdbc.queryForObject("""
                    SELECT approval_id, request_type, target_type, target_id, payload, result, status, maker_id,
                           checker_id, decision_note, correlation_id, created_at, decided_at, applied_at, expires_at
                    FROM banking.approval_request WHERE approval_id = ?
                    """, this::row, approvalId);
        } catch (EmptyResultDataAccessException absent) {
            throw new BankingBusinessException(ErrorCode.APPROVAL_NOT_FOUND);
        }
    }

    private void authorizeCreate(Actor actor, RequestType type) {
        requireStaff(actor);
        if (type == RequestType.LIMIT_CHANGE) {
            requireAny(actor, "LIMIT_MAKER", "LIMIT_MANAGE");
        } else {
            require(actor, "ROLE_MANAGE");
        }
    }

    private void authorizeDecision(Actor actor, RequestType type) {
        authorizeCreate(actor, type);
        require(actor, "CHECKER");
    }

    private void requireApprovalReader(Actor actor) {
        requireStaff(actor);
        if (!actor.authorities().contains("ROLE_OPERATIONS") && !actor.authorities().contains("ROLE_ADMIN")) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private void authorizeRead(Actor actor, ApprovalRow row) {
        requireApprovalReader(actor);
        if (actor.authorities().contains("ROLE_ADMIN")) return;
        if (row.requestType() != RequestType.LIMIT_CHANGE || !actor.authorities().contains("LIMIT_MAKER")) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        if (row.makerId() != actor.userId() && !actor.authorities().contains("CHECKER")
                && (row.checkerId() == null || row.checkerId() != actor.userId())) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private void applyReadScope(Actor actor, StringBuilder where, List<Object> args) {
        if (actor.authorities().contains("ROLE_ADMIN")) return;
        where.append(" AND request_type = 'LIMIT_CHANGE'");
        if (!actor.authorities().contains("CHECKER")) {
            where.append(" AND (maker_id = ? OR checker_id = ?)");
            args.add(actor.userId());
            args.add(actor.userId());
        }
    }

    private void validateRequest(RequestType type, String targetType, String targetId, JsonNode payload,
            long makerId) {
        if (type == RequestType.ROLE_CHANGE) {
            if (!"APP_USER".equals(targetType) || Long.toString(makerId).equals(targetId)) {
                throw new BankingBusinessException(ErrorCode.ROLE_CONFLICT);
            }
            validateRolePayload(payload, targetId);
        } else {
            validateLimitPayload(payload, targetType, targetId);
        }
    }

    private void validateRolePayload(JsonNode payload, String targetId) {
        long targetUserId = parsePositiveId(payload.path("targetUserId").asText());
        if (targetUserId != parsePositiveId(targetId)) throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        String operation = payload.path("operation").asText();
        boolean roleOperation = "ASSIGN_ROLE".equals(operation) || "REVOKE_ROLE".equals(operation);
        boolean privilegeOperation = "GRANT_PRIVILEGE".equals(operation) || "REVOKE_PRIVILEGE".equals(operation);
        if (!roleOperation && !privilegeOperation) throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        if (roleOperation) {
            if (!Set.of("CUSTOMER", "OPERATIONS", "ADMIN", "AUDITOR").contains(payload.path("roleCode").asText())
                    || !payload.path("privilegeCode").isMissingNode() && !payload.path("privilegeCode").isNull()) {
                throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
            }
        } else if (!"CHECKER".equals(payload.path("privilegeCode").asText())
                || !payload.path("roleCode").isMissingNode() && !payload.path("roleCode").isNull()) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM banking.app_user WHERE user_id = ?",
                Integer.class, targetUserId);
        if (exists == null || exists != 1) throw new BankingBusinessException(ErrorCode.USER_NOT_FOUND);
    }

    private void validateLimitPayload(JsonNode payload, String targetType, String targetId) {
        long customerId = parsePositiveId(payload.path("customerId").asText());
        CustomerQueryPort.CustomerSnapshot customer = customers.getCustomerById(customerId);
        if (!"ACTIVE".equals(customer.status())) throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        BigDecimal perTransaction = money(payload.path("perTransactionLimit"));
        BigDecimal daily = money(payload.path("dailyLimit"));
        if (perTransaction.compareTo(daily) > 0) throw new BankingBusinessException(ErrorCode.LIMIT_OUT_OF_POLICY);
        if (!payload.path("currency").isMissingNode() && !payload.path("currency").isNull()
                && !"VND".equals(payload.path("currency").asText())) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if ("ACCOUNT".equals(targetType)) {
            long accountId = parsePositiveId(targetId);
            AccountQueryPort.AccountSnapshot account = accounts.getAccountOrThrow(accountId);
            if (account.customerId() != customerId) throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        } else if (!"CUSTOMER".equals(targetType) || parsePositiveId(targetId) != customerId) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private JsonNode applyRoleChange(JsonNode payload, long checkerId) {
        IdentityRoleCommandPort.Operation operation;
        try {
            operation = IdentityRoleCommandPort.Operation.valueOf(payload.path("operation").asText());
        } catch (IllegalArgumentException invalid) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        long targetUserId = parsePositiveId(payload.path("targetUserId").asText());
        String code = operation == IdentityRoleCommandPort.Operation.ASSIGN_ROLE
                || operation == IdentityRoleCommandPort.Operation.REVOKE_ROLE
                ? payload.path("roleCode").asText() : payload.path("privilegeCode").asText();
        identityRoles.apply(new IdentityRoleCommandPort.RoleChange(operation, targetUserId, code, checkerId));
        return JsonNodeFactory.instance.objectNode().put("targetUserId", Long.toString(targetUserId))
                .put("operation", operation.name()).put("code", code).put("sessionsRevoked", true);
    }

    private void requireAvailableDependencies(RequestType type) {
        if (type == RequestType.LIMIT_CHANGE) {
            throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Limit module integration is unavailable.");
        }
    }

    private ApprovalAuditSink requiredAudit() {
        ApprovalAuditSink sink = audit.getIfAvailable();
        if (sink == null) throw new BankingBusinessException(ErrorCode.AUDIT_WRITE_FAILED);
        return sink;
    }

    private void auditC3(long actor, String action, long approvalId, String outcome) {
        ApprovalAuditSink sink = audit.getIfAvailable();
        if (sink == null) return;
        ApprovalAuditSink.AuditEvent event = event(actor, action, approvalId, outcome);
        Runnable publish = () -> {
            try {
                sink.recordStandalone(event);
            } catch (RuntimeException ignored) {
                // C3 denial audit must not replace the business denial or alter approval state.
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    private ApprovalAuditSink.AuditEvent event(long actor, String action, long approvalId, String outcome) {
        return new ApprovalAuditSink.AuditEvent(actor, action, "APPROVAL", Long.toString(approvalId), outcome,
                correlationId(), Map.of());
    }

    private ApprovalSummary toSummary(ApprovalRow row) {
        return new ApprovalSummary(Long.toString(row.approvalId()), row.requestType(), row.targetType(), row.targetId(),
                row.status(), actor(row.makerId()), row.checkerId() == null ? null : actor(row.checkerId()),
                row.decisionNote(), row.correlationId(), row.createdAt(), row.decidedAt(), row.appliedAt());
    }

    private ApprovalDetail toDetail(ApprovalRow row) {
        ApprovalSummary summary = toSummary(row);
        return new ApprovalDetail(summary.approvalId(), summary.requestType(), summary.targetType(), summary.targetId(),
                summary.status(), summary.maker(), summary.checker(), summary.decisionNote(), summary.correlationId(),
                summary.createdAt(), summary.decidedAt(), summary.appliedAt(), row.payload(), row.result());
    }

    private ActorSummary actor(long userId) {
        String username = jdbc.queryForObject("SELECT username FROM banking.app_user WHERE user_id = ?", String.class,
                userId);
        return new ActorSummary(Long.toString(userId), username);
    }

    private ApprovalRow row(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new ApprovalRow(rs.getLong("approval_id"), RequestType.valueOf(rs.getString("request_type")),
                    rs.getString("target_type"), rs.getString("target_id"),
                    objectMapper.readTree(rs.getString("payload")), readNullableJson(rs.getString("result")),
                    rs.getString("status"), rs.getLong("maker_id"), rs.getObject("checker_id", Long.class),
                    rs.getString("decision_note"), rs.getString("correlation_id"), instant(rs, "created_at"),
                    instant(rs, "decided_at"), instant(rs, "applied_at"), instant(rs, "expires_at"));
        } catch (JsonProcessingException invalidStoredJson) {
            throw new IllegalStateException("Invalid approval JSON");
        }
    }

    private static void addFilter(StringBuilder where, List<Object> args, String column, Object value) {
        if (value != null) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value instanceof Enum<?> enumValue ? enumValue.name() : value);
        }
    }

    private static void validateFilter(ApprovalFilter filter) {
        if (filter.page() < 0 || filter.size() < 1 || filter.size() > 100
                || filter.requestType() != null && !Set.of("LIMIT_CHANGE", "ROLE_CHANGE").contains(filter.requestType())
                || filter.status() != null && !API_STATUSES.contains(filter.status())
                || filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())
                || !Set.of("createdAt,asc", "createdAt,desc").contains(filter.sort())) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private static BigDecimal money(JsonNode value) {
        try {
            BigDecimal amount = new BigDecimal(value.asText());
            if (amount.signum() <= 0 || amount.scale() != 2) throw new NumberFormatException();
            return amount;
        } catch (NumberFormatException invalid) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private static void requireStaff(Actor actor) {
        if (actor == null || actor.authorities().contains("ROLE_CUSTOMER")) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static void require(Actor actor, String authority) {
        if (actor == null || !actor.authorities().contains(authority)) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static void requireAny(Actor actor, String first, String second) {
        if (actor == null || !actor.authorities().contains(first) && !actor.authorities().contains(second)) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private String requestHash(JsonNode binding) {
        try {
            return HmacService.sha256Hex(canonicalizer.canonicalize(objectMapper.writeValueAsString(binding)));
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid approval payload");
        }
    }

    public JsonNode binding(String action, String type, String targetId, JsonNode payload) {
        return objectMapper.valueToTree(Map.of("action", action, "requestType", type,
                "targetId", targetId, "payload", payload));
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalArgumentException("Invalid approval payload");
        }
    }

    private JsonNode readNullableJson(String json) throws JsonProcessingException {
        return json == null ? null : objectMapper.readTree(json);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime dbTime(Instant value) {
        return value == null ? null : value.atOffset(java.time.ZoneOffset.UTC);
    }

    private String correlationId() {
        return RequestContextHolder.current().map(value -> value.correlationId()).orElse("internal-" + UUID.randomUUID());
    }

    private static boolean hasSqlState(Throwable failure, String expected) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && expected.equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private static long parsePositiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}"))
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        return Long.parseLong(value);
    }

    public enum RequestType { LIMIT_CHANGE, ROLE_CHANGE }
    public enum Decision { APPROVE, REJECT }
    public static final class Actor {
        private final long userId;
        private final Set<String> authorities;

        private Actor(long userId, Set<String> authorities) {
            this.userId = userId;
            this.authorities = Set.copyOf(authorities);
        }

        public static Actor from(SessionAuthenticator.Session session) {
            if (session == null) throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
            return new Actor(Long.parseLong(session.userId()), session.authorities());
        }

        public long userId() {
            return userId;
        }

        public Set<String> authorities() {
            return authorities;
        }
    }
    public record ApprovalFilter(String requestType, String status, Long makerId, Long checkerId, Instant from,
            Instant to, int page, int size, String sort) { }
    public record ActorSummary(String userId, String displayName) { }
    public record ApprovalSummary(String approvalId, RequestType requestType, String targetType, String targetId,
            String status, ActorSummary maker, ActorSummary checker, String decisionNote, String correlationId,
            Instant createdAt, Instant decidedAt, Instant appliedAt) { }
    public record ApprovalDetail(String approvalId, RequestType requestType, String targetType, String targetId,
            String status, ActorSummary maker, ActorSummary checker, String decisionNote, String correlationId,
            Instant createdAt, Instant decidedAt, Instant appliedAt, JsonNode payload, JsonNode result) { }
    public record PageMeta(int number, int size, long totalElements, int totalPages, boolean first, boolean last) { }
    public record ApprovalPage(List<ApprovalSummary> items, PageMeta page, Instant asOf) {
        public ApprovalPage { items = List.copyOf(items); }
    }
    private record ApprovalRow(long approvalId, RequestType requestType, String targetType, String targetId,
            JsonNode payload, JsonNode result, String status, long makerId, Long checkerId, String decisionNote,
            String correlationId, Instant createdAt, Instant decidedAt, Instant appliedAt, Instant expiresAt) { }
}
