package com.simulatedbank.banking.transfer.application;

import com.simulatedbank.banking.common.config.ApiPage;
import com.simulatedbank.banking.transfer.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** Read-only history projection. The HTTP use case resolves scope before calling the port. */
public interface TransactionHistory {
    ApiPage<Item> list(Filter filter);

    enum Direction { DEBIT, CREDIT, INTERNAL }

    record Counterparty(String displayName, String accountNumberMasked) {
        public static Counterparty withLast4(String last4) {
            return new Counterparty("Tài khoản •" + last4, "**** **** " + last4);
        }
    }

    record Item(String reference, Direction direction, String amount, String currency, String status,
                       Counterparty counterparty, String description, Instant createdAt, Instant completedAt) {}

    record Filter(Long customerId, Long accountId, TransferStatus status, Direction direction,
                         BigDecimal minAmount, BigDecimal maxAmount, boolean accountPerspective, String sort, ApiPage.Window window) {}

}
