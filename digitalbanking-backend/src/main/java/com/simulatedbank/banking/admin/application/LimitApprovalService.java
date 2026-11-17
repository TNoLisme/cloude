package com.simulatedbank.banking.admin.application;

import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.admin.application.port.ApprovalStore;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import com.simulatedbank.banking.identity.api.StaffDirectory;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ActionBinding;
import com.simulatedbank.banking.limit.api.LimitChanges;
import com.simulatedbank.banking.limit.api.LimitConfiguration;
import com.simulatedbank.banking.limit.api.LimitRules;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LimitApprovalService {
    private final ApprovalStore approvals;
    private final LimitChanges limits;
    private final LimitRules rules;
    private final ObjectProvider<AuditModuleApi> audits;
    private final LimitApprovalBinding bindings;
    private final ObjectProvider<StepUpChallengePort> stepUps;
    private final ObjectProvider<StaffDirectory> staff;
    private final ObjectProvider<CustomerQuery> customers;
    private final ObjectProvider<AccountQuery> accounts;
    private final TransactionTemplate transactions;

    public LimitApprovalService(ApprovalStore approvals, LimitChanges limits, LimitRules rules, ObjectProvider<AuditModuleApi> audits,
            LimitApprovalBinding bindings, ObjectProvider<StepUpChallengePort> stepUps, ObjectProvider<StaffDirectory> staff,
            ObjectProvider<CustomerQuery> customers, ObjectProvider<AccountQuery> accounts, PlatformTransactionManager manager) {
        this.approvals = approvals; this.limits = limits; this.rules = rules; this.audits = audits; this.bindings = bindings;
        this.stepUps = stepUps; this.staff = staff; this.customers = customers; this.accounts = accounts;
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public record Result(ApprovalRequest approval, StaffDirectory.ActorSummary maker,
                         StaffDirectory.ActorSummary checker, LimitConfiguration configuration) { }

    public Result propose(Authentication authentication, LimitApprovalBinding.Change change, UUID challenge, RequestContext trace) {
        var actor = actor(authentication, false);
        requireNoOuterTransaction();
        required(audits);
        var identity = required(stepUps);
        var directory = required(staff);
        limits.validateChange(change.perTransaction(), change.daily(), change.currency(), change.effectiveFrom());
        checkScope(change.customerId(), change.accountId());
        var maker = profile(directory, actor.id());
        var binding = bindings.create(actor.id(), change);
        requireChallenge(identity, challenge, binding);
        return transactions.execute(tx -> {
            identity.consumeVerified(challenge, binding);
            var approval = approvals.create(change.accountId() == null ? "CUSTOMER" : "ACCOUNT",
                    change.accountId() == null ? Long.toString(change.customerId()) : change.accountId().toString(),
                    change.payload(null), actor.id(), trace.correlationId());
            var pending = limits.prepare(new LimitChanges.Proposal(change.customerId(), change.accountId(), change.perTransaction(),
                    change.daily(), change.currency(), change.effectiveFrom(), actor.id(), approval.approvalId()));
            Object current = rules.current(change.customerId(), change.accountId(), pending.createdAt())
                    .<Object>map(config -> Map.of("perTransactionLimit", config.perTransactionLimit().setScale(2).toPlainString(),
                            "dailyLimit", config.dailyLimit().setScale(2).toPlainString(), "version", config.version())).orElse(null);
            var stored = new LimitApprovalBinding.Change(change.customerId(), change.accountId(), change.perTransaction(),
                    change.daily(), change.currency(), pending.effectiveFrom(), change.reason());
            approval = approvals.setPayload(approval.approvalId(), stored.payload(current));
            record(approval, actor, "LIMIT_CHANGE_REQUESTED", null, null, trace);
            return new Result(approval, maker, null, null);
        });
    }

    public Result decide(Authentication authentication, long approvalId, String decision, String note,
            UUID challenge, RequestContext trace) {
        var actor = actor(authentication, true);
        requireNoOuterTransaction();
        var audit = required(audits);
        if (approvalId <= 0 || decision == null || !java.util.Set.of("APPROVE", "REJECT").contains(decision)) fail(ErrorCode.VALIDATION_ERROR);
        String safeNote = LimitApprovalBinding.note(note);
        var initial = approvals.find(approvalId).orElseThrow(() -> new BankingBusinessException(ErrorCode.APPROVAL_NOT_FOUND));
        requirePending(initial);
        if (initial.makerId() == actor.id()) {
            audit.recordDenied(new AuditModuleApi.DeniedEvent(actor.id(), actor.role(), approvalId,
                    "ROLE_CONFLICT", trace.correlationId(), trace.requestId()));
            fail(ErrorCode.ROLE_CONFLICT);
        }
        var identity = required(stepUps);
        var directory = required(staff);
        checkScope(LimitApprovalBinding.id((String) initial.payload().get("customerId")),
                initial.payload().get("accountId") == null ? null : LimitApprovalBinding.id((String) initial.payload().get("accountId")));
        var maker = profile(directory, initial.makerId());
        var checker = profile(directory, actor.id());
        var binding = bindings.decision(actor.id(), approvalId, decision, safeNote, initial.payload());
        requireChallenge(identity, challenge, binding);
        return transactions.execute(tx -> {
            var locked = approvals.lock(approvalId);
            requirePending(locked);
            var lockedBinding = bindings.decision(actor.id(), approvalId, decision, safeNote, locked.payload());
            if (!binding.equals(lockedBinding)) fail(ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
            identity.consumeVerified(challenge, binding);
            boolean approve = decision.equals("APPROVE");
            Map<?, ?> current = (Map<?, ?>) locked.payload().get("current");
            Integer expectedVersion = current == null ? null : ((Number) current.get("version")).intValue();
            var config = approve ? limits.approve(approvalId, actor.id(), expectedVersion) : limits.reject(approvalId, actor.id());
            var applied = approvals.decide(approvalId, approve, actor.id(), safeNote);
            record(applied, actor, approve ? "LIMIT_CHANGE_APPROVED" : "LIMIT_CHANGE_REJECTED", decision, "PENDING", trace);
            return new Result(applied, maker, checker, config);
        });
    }

    private void checkScope(long customerId, Long accountId) {
        var customer = required(customers).findById(customerId)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        if (customer.customerId() != customerId) fail(ErrorCode.AUTH_FORBIDDEN);
        if (accountId != null) {
            var account = required(accounts).findById(accountId)
                    .orElseThrow(() -> new BankingBusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
            if (account.accountId() != accountId || account.customerId() != customerId) fail(ErrorCode.AUTH_FORBIDDEN);
            if (!"VND".equals(account.currency())) fail(ErrorCode.VALIDATION_ERROR);
        }
    }

    private void record(ApprovalRequest approval, Actor actor, String action, String decision, String before, RequestContext trace) {
        required(audits).recordInTransaction(new AuditModuleApi.ApprovalEvent(action, approval.approvalId(), actor.id(), actor.role(),
                approval.targetType(), approval.targetId(), approval.makerId(), approval.checkerId(), decision, before,
                approval.status(), trace.correlationId(), trace.requestId()));
    }

    private static void requireChallenge(StepUpChallengePort identity, UUID challenge, ActionBinding binding) {
        if (challenge == null) {
            var requested = identity.requestChallenge(binding);
            if (requested == null || !"SENSITIVE_ACTION".equals(requested.purpose())) fail(ErrorCode.SERVICE_UNAVAILABLE);
            throw new BankingBusinessException(ErrorCode.STEP_UP_REQUIRED, ErrorCode.STEP_UP_REQUIRED.detail(), Map.of("challenge",
                    Map.of("challengeId", requested.challengeId().toString(), "purpose", requested.purpose(),
                            "methods", requested.methods(), "expiresAt", requested.expiresAt().toString(),
                            "maxAttempts", requested.maxAttempts(), "remainingAttempts", requested.remainingAttempts())));
        }
    }

    private static StaffDirectory.ActorSummary profile(StaffDirectory directory, long id) {
        var actor = directory.findActor(id).orElseThrow(() -> new BankingBusinessException(ErrorCode.USER_NOT_FOUND));
        if (actor.userId() != id || actor.displayName() == null || actor.displayName().isBlank()) fail(ErrorCode.SERVICE_UNAVAILABLE);
        return actor;
    }

    private record Actor(long id, String role) { }

    private static Actor actor(Authentication authentication, boolean checker) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionAuthenticator.Session session)) {
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        var authorities = session.authorities();
        if ((!authorities.contains("ROLE_OPERATIONS") && !authorities.contains("ROLE_ADMIN"))
                || (!authorities.contains("LIMIT_MAKER") && !authorities.contains("LIMIT_MANAGE"))
                || (checker && !authorities.contains("CHECKER"))) fail(ErrorCode.AUTH_FORBIDDEN);
        return new Actor(LimitApprovalBinding.id(session.userId()), authorities.contains("ROLE_ADMIN") ? "ADMIN" : "OPERATIONS");
    }

    private static <T> T required(ObjectProvider<T> provider) {
        T value = provider.getIfAvailable();
        if (value == null) fail(ErrorCode.SERVICE_UNAVAILABLE);
        return value;
    }

    private static void requirePending(ApprovalRequest approval) {
        if (!"PENDING".equals(approval.status())) fail(ErrorCode.APPROVAL_ALREADY_DECIDED);
    }

    private static void requireNoOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("Admin orchestration must start outside a business transaction");
        }
    }

    private static void fail(ErrorCode code) { throw new BankingBusinessException(code); }
}
