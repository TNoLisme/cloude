package com.simulatedbank.transfercandidate.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.account.api.AccountBalance;
import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.account.api.AccountTransferEvidence;
import com.simulatedbank.banking.audit.api.TransferAuditEvidence;
import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.error.ProblemResponse;
import com.simulatedbank.banking.fraud.api.FraudEvaluation;
import com.simulatedbank.banking.ledger.api.LedgerPosting;
import com.simulatedbank.banking.limit.api.TransferLimits;
import com.simulatedbank.banking.transfer.adapter.out.integration.TransferAuditAdapter;
import com.simulatedbank.banking.transfer.adapter.out.integration.TransferSettlementHandoff;
import com.simulatedbank.banking.transfer.adapter.out.integration.TransferFraudAdapter;
import com.simulatedbank.banking.transfer.adapter.out.limit.TransferLimitAdapter;
import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferSettlementStore;
import com.simulatedbank.banking.transfer.application.IdempotencyRetention.Snapshot;
import com.simulatedbank.banking.transfer.application.TransferNotifications;
import com.simulatedbank.banking.transfer.application.TransferPreflightCandidate.ReplayResponse;
import com.simulatedbank.banking.transfer.application.TransferTelemetry;
import com.simulatedbank.banking.transfer.application.TransferRecoveryCandidate;
import com.simulatedbank.transfercandidate.recovery.TransferRecoveryJobConfiguration;
import jakarta.persistence.EntityManagerFactory;
import com.simulatedbank.banking.notification.api.NotificationOutboxPort;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit activation requires the bootstrapped Fraud provider and a Notification policy. */
@Import(TransferRecoveryJobConfiguration.class)
public class TransferSettlementIntegrationConfiguration {
    @Bean
    TransferRecoveryCandidate transferRecovery(TransferSettlementHandoff handoff) {
        return handoff.newRecoveryWorker();
    }

    @Bean
    TransferNotifications transferNotifications(NotificationOutboxPort outbox) {
        return new TransferNotifications((facts, outcome) -> List.of(new TransferNotifications.Recipient(
                facts.senderCustomerId(), TransferNotifications.Channel.SMS, "TRANSFER_RESULT")),
                notices -> outbox.enqueueTransferSmsInT2(notices.stream()
                        .map(notice -> new NotificationOutboxPort.TransferSms(notice.recipient().customerId(),
                                notice.transferId(), notice.reference(), notice.amount().value(),
                                notice.amount().currency(), notice.maskedDestination(),
                                notice.outcome().failure().isEmpty(), notice.correlationId()))
                        .toList()));
    }

    @Bean
    TransferSettlementStore transferSettlementStore(JdbcTemplate jdbc) {
        return new TransferSettlementStore(jdbc);
    }

    @Bean
    @DependsOnDatabaseInitialization
    TransferSettlementHandoff transferSettlementHandoff(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            ZoneId businessZoneId, TransferTelemetry telemetry, AccountBalance accounts, AccountQuery accountQuery,
            LedgerPosting ledger, TransferLimits limits, TransferLimitAdapter limitAdapter, TransferAuditAdapter audit,
            TransferNotifications notifications, FraudEvaluation fraud,
            AppProperties properties, ObjectMapper mapper, EntityManagerFactory entityManagers, TransferSettlementStore store,
            AccountTransferEvidence movements, TransferAuditEvidence auditReads) {
        fraud.verifyReady();
        var config = properties.transfer();
        return new TransferSettlementHandoff(jdbc, transactions, businessZoneId, telemetry, accounts, accountQuery,
                ledger, limits, limitAdapter, audit, notifications, new TransferFraudAdapter(fraud), (details, receipt, failure, at) -> {
                    if (failure == null) return new ReplayResponse(201, encode(mapper, TransferController.response(receipt)));
                    var code = TransferController.failureError(failure);
                    var problem = new ProblemResponse(code.type(), code.title(), code.httpStatus(), code.detail(),
                            "/api/v1/transfers", code.name(), details.intent().correlationId(), at, code.retryable(), null,
                            TransferController.failureExtensions(details.reference(), failure));
                    return new ReplayResponse(code.httpStatus(), encode(mapper, problem));
                }, () -> {
                    int hours = properties.transfer().idempotencyTtlHours();
                    return new Snapshot(hours * 3600L, "transfer-ttl-hours-" + hours);
                },
                () -> EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers).flush(),
                store, config.t2LockTimeoutMs(), config.t2StatementTimeoutMs(), movements, auditReads, mapper);
    }

    private static String encode(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not encode terminal transfer response", failure);
        }
    }
}
