package com.bank.simulator.risk.application;

import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.risk.config.RiskProperties;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.shared.pagination.CursorCodec;
import com.bank.simulator.shared.pagination.CursorPosition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

@Service
public class RiskEvaluationService {
    private final RiskFlagRepository flags;
    private final CursorCodec cursors;
    private final TransferCompletedCountRepository completedTransfers;
    private final RiskProperties properties;
    private final java.time.Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public RiskEvaluationService(RiskFlagRepository flags, CursorCodec cursors,
                                 TransferCompletedCountRepository completedTransfers,
                                 RiskProperties properties, java.time.Clock clock) {
        this.flags = flags;
        this.cursors = cursors;
        this.completedTransfers = completedTransfers;
        this.properties = properties;
        this.clock = clock;
    }

    public RiskEvaluationService(RiskFlagRepository flags, CursorCodec cursors,
                                 TransferCompletedCountRepository completedTransfers,
                                 RiskProperties properties) {
        this(flags, cursors, completedTransfers, properties, java.time.Clock.systemUTC());
    }

    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void evaluate(TransferRiskSnapshot snapshot) {
        Instant detectedAt = clock.instant();
        if (new BigInteger(snapshot.amount()).compareTo(new BigInteger(properties.getLargeTransferThreshold())) > 0) {
            flags.insert(snapshot.transferId(), "LARGE_TRANSFER", "1", "Transfer amount exceeds configured threshold.", detectedAt);
        }
        if (completedTransfers.countSince(snapshot.sourceAccountId(),
                snapshot.completedAt().minus(properties.getFrequencyWindow()), snapshot.completedAt())
                > properties.getFrequencyCountThreshold()) {
            flags.insert(snapshot.transferId(), "HIGH_FREQUENCY", "1", "More than five completed transfers in ten minutes.", detectedAt);
        }
    }

    public RiskFlagPage list(RiskFlagQuery query, AuthenticatedActor actor) {
        if (!actor.hasRole("OPERATOR") && !actor.hasRole("AUDITOR") && !actor.hasRole("ADMIN")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Request is forbidden.");
        }
        CursorPosition cursor;
        try {
            cursor = query.cursor() == null ? null : cursors.decode(query.cursor());
        } catch (IllegalArgumentException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Risk flag cursor is invalid.");
        }
        List<RiskFlagView> rows = flags.find(query, cursor, query.limit() + 1);
        boolean hasNext = rows.size() > query.limit();
        List<RiskFlagView> items = rows.stream().limit(query.limit()).toList();
        String next = hasNext ? cursors.encode(new CursorPosition(1, items.getLast().detectedAt(), items.getLast().flagId())) : null;
        return new RiskFlagPage(items, next);
    }
}
