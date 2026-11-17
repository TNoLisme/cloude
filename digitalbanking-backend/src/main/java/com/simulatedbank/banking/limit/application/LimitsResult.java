package com.simulatedbank.banking.limit.application;

import com.simulatedbank.banking.limit.application.port.LimitReadPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LimitsResult(long customerId, String currency, LocalDate usageDate,
        BigDecimal effectivePerTransactionLimit, BigDecimal effectiveRemainingDaily,
        List<LimitReadPort.Row> items, Instant asOf) { }
