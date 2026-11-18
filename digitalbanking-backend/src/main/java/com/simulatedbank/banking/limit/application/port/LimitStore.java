package com.simulatedbank.banking.limit.application.port;

import com.simulatedbank.banking.limit.api.LimitConfiguration;
import com.simulatedbank.banking.limit.api.LimitUsage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Persistence operations used by limit application services. */
public interface LimitStore {
    LimitConfiguration insertConfiguration(LimitConfiguration config);
    Optional<LimitConfiguration> insertProposal(LimitConfiguration config);
    LimitUsage insertUsage(LimitUsage usage);
    Optional<LimitConfiguration> findConfiguration(long limitId);
    Optional<LimitUsage> findUsage(long customerId, Long accountId, LocalDate date);
    List<LimitConfiguration> findEffective(long customerId, long sourceAccountId, Instant asOf);
    boolean hasActivationAfter(long customerId, long sourceAccountId, Instant asOf);
    Instant transactionStart();
    LimitUsage lockUsage(long customerId, Long accountId, LocalDate date);
    void incrementUsage(LimitUsage usage, BigDecimal amount, BigDecimal dailyLimit);
    void lockChangeScope(long customerId, Long accountId);
    Instant currentTime();
    List<LimitConfiguration> findScope(long customerId, Long accountId);
    Optional<LimitConfiguration> findProposal(long approvalId, boolean forUpdate);
    void supersede(long limitId, Instant now);
    LimitConfiguration decideProposal(long limitId, boolean approve, long checkerId, Instant now);
}
