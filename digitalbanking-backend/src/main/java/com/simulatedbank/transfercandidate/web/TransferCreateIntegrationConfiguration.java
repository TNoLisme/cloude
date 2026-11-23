package com.simulatedbank.transfercandidate.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import com.simulatedbank.banking.identity.api.StepUpChallengePort;
import com.simulatedbank.banking.limit.api.TransferLimits;
import com.simulatedbank.banking.transfer.adapter.out.integration.TransferAuditAdapter;
import com.simulatedbank.banking.transfer.adapter.out.integration.TransferCreateAdapters;
import com.simulatedbank.banking.transfer.adapter.out.integration.TransferCreateAdapters.PostCommitSettlement;
import com.simulatedbank.banking.transfer.adapter.out.integration.TransferIdentityAdapter;
import com.simulatedbank.banking.transfer.adapter.out.limit.TransferLimitAdapter;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferPersistence;
import com.simulatedbank.banking.transfer.application.TransferCreateOrchestrator;
import com.simulatedbank.banking.transfer.application.TransferOrchestrator;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate;
import com.simulatedbank.banking.transfer.application.TransferRequestHash;
import com.simulatedbank.banking.transfer.application.TransferTelemetry;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit T0/T1 registration. Activation requires a separately supplied real settlement implementation. */
public class TransferCreateIntegrationConfiguration {
    @Bean
    TransferPersistence transferPersistence(JdbcTemplate jdbc) {
        return new TransferPersistence(jdbc);
    }

    @Bean
    TransferAuditAdapter transferAuditAdapter(AuditModuleApi audit) {
        return new TransferAuditAdapter(audit);
    }

    @Bean
    TransferOrchestrator transferCreateOrchestrator(AccountQuery accounts, CustomerQuery customers,
            TransferLimits limits, TransferLimitAdapter limitAdapter, TransferPersistence persistence,
            StepUpChallengePort identity, TransferAuditAdapter audit, PlatformTransactionManager transactions,
            TransferTelemetry telemetry, PostCommitSettlement settlement, ZoneId businessZoneId,
            AppProperties properties, ObjectMapper objectMapper, JcsCanonicalizer canonicalizer) {
        var challenges = new TransferIdentityAdapter(identity);
        var thresholds = properties.security().stepUp();
        var single = thresholds.singleAmountVnd().stripTrailingZeros();
        var daily = thresholds.dailyAmountVnd().stripTrailingZeros();
        // Values are deployment configuration; encode the exact thresholds in the policy version.
        var policy = new TransferLimits.StepUpPolicy("amount-v1:" + single + ":" + daily, single, daily);
        var adapter = new TransferCreateAdapters(accounts, customers, limits, limitAdapter, persistence, challenges,
                audit, transactions, telemetry, settlement, businessZoneId, properties.transfer().idempotencyTtlHours(), policy);
        var preflight = new TransferPreflightCandidate(adapter,
                new TransferRequestHash(objectMapper, canonicalizer), telemetry);
        return new TransferCreateOrchestrator(preflight, challenges);
    }
}
