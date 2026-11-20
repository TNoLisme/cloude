package com.simulatedbank.transfercandidate.web;

import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.transfer.adapter.inbound.TransferCreateInput;
import com.simulatedbank.banking.transfer.application.FraudFlagView;
import com.simulatedbank.banking.transfer.application.MaskedAccount;
import com.simulatedbank.banking.transfer.application.StepUpChallenge;
import com.simulatedbank.banking.transfer.application.TransferActor;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.AccountInactive;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.AccountNotFound;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Completed;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Conflict;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Failed;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.InProgress;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.InsufficientBalance;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.LimitExceeded;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Replay;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.SourceNotOwned;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.StepUpRequired;
import com.simulatedbank.banking.transfer.application.TransferCreateService;
import com.simulatedbank.banking.transfer.application.TransferDetailView;
import com.simulatedbank.banking.transfer.application.TransferFailureCode;
import com.simulatedbank.banking.transfer.application.TransferQueryService;
import com.simulatedbank.banking.transfer.application.TransferView;
import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Candidate HTTP boundary for Transfer (BE-TRF-03). Lives outside the {@code banking} scan root and
 * is not component-scanned: register via {@link TransferHttpConfiguration} only once the orchestrator
 * (BE-TRF-04/05), real providers and the reviewed migration are ready. Thin per BE-CON-21-26: parse,
 * call one application service, map the outcome; no transaction, no entity, no hand-built Problem.
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {
    static final String REFERENCE = "TXN[0-9]{8}[A-Z0-9]{8,20}";

    private final TransferCreateService createService;
    private final TransferQueryService queryService;
    private final int maxBodyBytes;

    public TransferController(TransferCreateService createService, TransferQueryService queryService, int maxBodyBytes) {
        this.createService = createService;
        this.queryService = queryService;
        this.maxBodyBytes = maxBodyBytes;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody byte[] body,
            @RequestHeader(name = "Idempotency-Key", required = false) List<String> idempotencyKeys,
            Principal principal) throws MethodArgumentNotValidException {
        TransferActor actor = actor(principal);
        TransferCreateInput.Validated input = parse(body, idempotencyKeys);
        return mapOutcome(createService.create(actor, input));
    }

    @GetMapping("/{reference}")
    public TransferDetail get(@PathVariable String reference, Principal principal)
            throws MethodArgumentNotValidException {
        if (reference == null || !reference.matches(REFERENCE)) {
            throw validationError("reference");
        }
        return detail(queryService.get(reference, actor(principal)));
    }

    private ResponseEntity<?> mapOutcome(TransferCreateOutcome outcome) {
        return switch (outcome) {
            case Completed completed -> ResponseEntity.status(201)
                    .location(URI.create("/api/v1/transfers/" + completed.view().reference()))
                    .body(response(completed.view()));
            case Failed failed -> throw failed(failed.view());
            case StepUpRequired stepUp -> throw new BankingBusinessException(ErrorCode.STEP_UP_REQUIRED,
                    ErrorCode.STEP_UP_REQUIRED.detail(), Map.of("challenge", challengeMap(stepUp.challenge())));
            case InProgress ignored -> throw new BankingBusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
            case Conflict ignored -> throw new BankingBusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            case AccountNotFound ignored -> throw new BankingBusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
            case AccountInactive ignored -> throw new BankingBusinessException(ErrorCode.ACCOUNT_INACTIVE);
            case InsufficientBalance ignored -> throw new BankingBusinessException(ErrorCode.INSUFFICIENT_BALANCE);
            case LimitExceeded ignored -> throw new BankingBusinessException(ErrorCode.LIMIT_EXCEEDED);
            case TransferCreateOutcome.CustomerInactive ignored -> throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
            case SourceNotOwned ignored -> throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
            case TransferCreateOutcome.Committed committed -> {
                var response = committed.response();
                var builder = ResponseEntity.status(response.httpStatus())
                        .contentType(response.httpStatus() == 201 ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_PROBLEM_JSON);
                if (response.httpStatus() == 201) {
                    builder.location(URI.create("/api/v1/transfers/" + committed.reference()));
                }
                yield builder.body(response.safeBody());
            }
            case Replay replay -> ResponseEntity.status(replay.httpStatus())
                    .header("Idempotent-Replayed", "true")
                    .contentType(replay.httpStatus() == 201 ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_PROBLEM_JSON)
                    .body(replay.body());
        };
    }

    private static BankingBusinessException failed(TransferView view) {
        ErrorCode code = failureError(view.failureCode());
        return new BankingBusinessException(code, code.detail(), failureExtensions(view.reference(), view.failureCode()));
    }

    static ErrorCode failureError(TransferFailureCode failure) {
        return switch (failure) {
            case INSUFFICIENT_BALANCE -> ErrorCode.INSUFFICIENT_BALANCE;
            case LIMIT_EXCEEDED -> ErrorCode.LIMIT_EXCEEDED;
            case FRAUD_BLOCKED -> ErrorCode.FRAUD_BLOCKED;
            default -> ErrorCode.TRANSFER_FAILED;
        };
    }

    static Map<String, Object> failureExtensions(String reference,
            TransferFailureCode failure) {
        return Map.of("reference", reference, "transferStatus", "FAILED", "failureCode", failure.name());
    }

    private TransferCreateInput.Validated parse(byte[] body, List<String> keys) throws MethodArgumentNotValidException {
        try {
            return TransferCreateInput.parse(body, keys, maxBodyBytes);
        } catch (TransferCreateInput.InvalidRequestException ex) {
            if (ex.kind() == TransferCreateInput.Kind.MALFORMED) {
                throw new BankingBusinessException(ErrorCode.MALFORMED_REQUEST);
            }
            if (ex.kind() == TransferCreateInput.Kind.TOO_LARGE) {
                throw new BankingBusinessException(ErrorCode.REQUEST_TOO_LARGE);
            }
            throw validationError(ex.field());
        }
    }

    static TransferActor actor(Principal principal) {
        Authentication authentication = (Authentication) principal;
        SessionAuthenticator.Session session = (SessionAuthenticator.Session) authentication.getPrincipal();
        RequestContext context = RequestContextHolder.current()
                .orElseThrow(() -> new IllegalStateException("Missing request context"));
        Long customerId = session.customerId() == null ? null : Long.parseLong(session.customerId());
        return new TransferActor(Long.parseLong(session.userId()), customerId, session.authorities(),
                context.correlationId(), context.requestId());
    }

    private static MethodArgumentNotValidException validationError(String field) {
        var binding = new BeanPropertyBindingResult(new Object(), "transferCreateRequest");
        binding.addError(new FieldError("transferCreateRequest", field == null ? "request" : field,
                "Giá trị không hợp lệ."));
        return new MethodArgumentNotValidException(null, binding);
    }

    static TransferResponse response(TransferView view) {
        return new TransferResponse(view.reference(), view.status().name(), view.amount().value().toPlainString(),
                view.amount().currency(), view.description(), accountRef(view.source()), accountRef(view.destination()),
                view.failureCode() == null ? null : view.failureCode().name(), view.failureMessage(),
                view.correlationId(), view.createdAt(), view.completedAt(), view.failedAt());
    }

    private static TransferDetail detail(TransferDetailView detail) {
        List<FraudFlagSummary> flags = detail.fraudFlags().isEmpty() ? null : detail.fraudFlags().stream()
                .map(TransferController::flagSummary).toList();
        return new TransferDetail(response(detail.transfer()), flags);
    }

    private static FraudFlagSummary flagSummary(FraudFlagView flag) {
        return new FraudFlagSummary(Long.toString(flag.flagId()), flag.ruleCode(), flag.ruleVersion(),
                flag.severity(), flag.status());
    }

    private static AccountRef accountRef(MaskedAccount account) {
        return new AccountRef(Long.toString(account.accountId()), account.maskedNumber(), account.last4());
    }

    private static Map<String, Object> challengeMap(StepUpChallenge challenge) {
        Map<String, Object> map = new HashMap<>();
        map.put("challengeId", challenge.challengeId());
        map.put("purpose", challenge.purpose());
        map.put("methods", challenge.methods());
        map.put("expiresAt", challenge.expiresAt());
        map.put("maxAttempts", challenge.maxAttempts());
        map.put("remainingAttempts", challenge.remainingAttempts());
        if (challenge.channel() != null) {
            map.put("channel", challenge.channel());
        }
        if (challenge.maskedDestination() != null) {
            map.put("maskedDestination", challenge.maskedDestination());
        }
        return map;
    }
}