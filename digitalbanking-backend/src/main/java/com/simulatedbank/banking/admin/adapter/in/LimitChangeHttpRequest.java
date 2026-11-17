package com.simulatedbank.banking.admin.adapter.in;

/** API-LIM-02 request body (07 §8.5.2); currently fail-closed because the Limit provider is absent. */
public record LimitChangeHttpRequest(String customerId, String accountId, String perTransactionLimit,
        String dailyLimit, String currency, String effectiveFrom, String reason) {
}
