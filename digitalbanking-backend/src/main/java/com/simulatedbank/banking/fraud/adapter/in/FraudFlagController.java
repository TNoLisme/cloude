package com.simulatedbank.banking.fraud.adapter.in;

import com.simulatedbank.banking.audit.api.AuditAction;
import com.simulatedbank.banking.audit.api.AuditActorType;
import com.simulatedbank.banking.audit.api.AuditEvent;
import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.audit.api.AuditOutcome;
import com.simulatedbank.banking.common.config.ApiPage;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.fraud.application.port.FraudFlagQuery;
import com.simulatedbank.banking.fraud.application.FraudReviewService;
import com.simulatedbank.banking.fraud.application.dto.ReviewFraudFlagRequest;
import com.simulatedbank.banking.fraud.domain.FlagStatus;
import com.simulatedbank.banking.fraud.domain.Severity;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/fraud-flags")
@ConditionalOnProperty(name = {"app.fraud.enabled", "app.audit.enabled"}, havingValue = "true", matchIfMissing = true)
public class FraudFlagController {
    private final FraudFlagQuery queries;
    private final FraudReviewService reviews;
    private final AuditModuleApi audit;
    private final Clock clock;

    public FraudFlagController(FraudFlagQuery queries, FraudReviewService reviews, AuditModuleApi audit, Clock clock) {
        this.queries = queries;
        this.reviews = reviews;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping
    public ApiPage<FraudFlagQuery.Flag> list(@RequestParam(required = false) FlagStatus status,
            @RequestParam(required = false) Severity severity, @RequestParam(required = false) String ruleCode,
            @RequestParam(required = false) String reference, @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        if ((customerId != null && customerId <= 0) || (ruleCode != null && !ruleCode.matches("[A-Z0-9_]{1,64}"))
                || (reference != null && !reference.matches("TXN[0-9]{8}[A-Z0-9]{8,20}"))) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return queries.search(ApiPage.Window.resolve(from, to, page, size, clock.instant()), sort,
                status == null ? null : status.name(), severity == null ? null : severity.name(),
                ruleCode, reference, customerId);
    }

    @PostMapping("/{id}/review")
    @Transactional
    public FraudFlagQuery.Flag review(@PathVariable long id, @Valid @RequestBody ReviewFraudFlagRequest request,
            @AuthenticationPrincipal SessionAuthenticator.Session actor) {
        if (id <= 0) throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        long userId = Long.parseLong(actor.userId());
        reviews.reviewFlag(id, request, userId);
        var flag = queries.get(id);
        var context = RequestContextHolder.current().orElseThrow();
        var state = Map.<String, Object>of("transferReference", flag.transferReference(),
                "ruleCode", flag.rule().code(), "severity", flag.severity(),
                "decision", flag.decision(), "status", flag.status());
        audit.recordInTransaction(new AuditEvent(AuditAction.FRAUD_FLAG_REVIEWED, AuditActorType.USER, userId,
                "OPERATIONS", "FRAUD_FLAG", flag.flagId(), null, state, null, null, AuditOutcome.SUCCESS,
                context.correlationId(), context.requestId(), null, clock.instant()));
        return flag;
    }
}
