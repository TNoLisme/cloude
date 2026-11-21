package com.simulatedbank.banking.limit.adapter.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record LimitsResponse(String customerId, String currency, LocalDate usageDate,
        String effectivePerTransactionLimit, String effectiveRemainingDaily, List<LimitView> items, Instant asOf) {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LimitView(String limitId, String scope, String accountId, String perTransactionLimit,
            String dailyLimit, String currency, String usedToday, String remainingDaily,
            Instant effectiveFrom, Instant effectiveTo, int version) { }
}
