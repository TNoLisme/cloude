package com.simulatedbank.banking.fraud.application.port;

import com.simulatedbank.banking.common.config.ApiPage;
import java.time.Instant;
import java.util.Map;

public interface FraudFlagQuery {
    ApiPage<Flag> search(ApiPage.Window window, String sort, String status, String severity,
                         String ruleCode, String reference, Long customerId);

    Flag get(long flagId);

    public record Rule(String code, int version, String type) {}
    public record Reviewer(String userId, String displayName) {}
    public record Flag(String flagId, String transferReference, String customerId, Rule rule, String severity,
                       String status, Map<String, Object> matchedValue, String decision, Reviewer reviewedBy,
                       Instant reviewedAt, String correlationId, Instant createdAt) {}

}
