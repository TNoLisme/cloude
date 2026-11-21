package com.simulatedbank.transfercandidate.web;

import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.fraud.api.FraudFlagSummaryQuery;
import com.simulatedbank.banking.ledger.config.LedgerPostingConfiguration;
import com.simulatedbank.banking.transfer.adapter.out.persistence.JdbcTransferRead;
import com.simulatedbank.banking.transfer.adapter.out.persistence.JdbcTransactionHistory;
import com.simulatedbank.banking.transfer.application.AccountOwnershipPort;
import com.simulatedbank.banking.transfer.application.FraudFlagReadPort;
import com.simulatedbank.banking.transfer.application.FraudFlagView;
import com.simulatedbank.banking.transfer.application.TransactionHistory;
import com.simulatedbank.banking.transfer.application.TransferReadPort;
import com.simulatedbank.banking.transfer.application.TransferStaffScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.transfer.enabled", havingValue = "true", matchIfMissing = true)
@Import({TransferHttpConfiguration.class, TransferCreateIntegrationConfiguration.class,
        TransferSettlementIntegrationConfiguration.class, LedgerPostingConfiguration.class, TransactionHistoryController.class})
public class TransferRuntimeConfiguration {
    @Bean
    TransactionHistory transactionHistory(NamedParameterJdbcTemplate jdbc) {
        return new JdbcTransactionHistory(jdbc);
    }

    @Bean
    TransferReadPort transferReadPort(JdbcTemplate jdbc, AccountQuery accounts) {
        return new JdbcTransferRead(jdbc, accounts);
    }

    @Bean
    AccountOwnershipPort transferReceiptOwnership(AccountQuery accounts) {
        return (customerId, accountId) -> accounts.findAccount(accountId)
                .map(account -> account.customerId() == customerId
                        ? AccountOwnershipPort.Ownership.OWNED : AccountOwnershipPort.Ownership.FORBIDDEN)
                .orElse(AccountOwnershipPort.Ownership.NOT_FOUND);
    }

    @Bean
    FraudFlagReadPort transferFraudFlags(FraudFlagSummaryQuery fraud) {
        return transferId -> fraud.findByTransfer(transferId).stream()
                .map(flag -> new FraudFlagView(flag.flagId(), flag.ruleCode(), flag.ruleVersion(),
                        flag.severity(), flag.status()))
                .toList();
    }

    @Bean
    TransferStaffScope transferStaffScope() {
        // TransferQueryService checks the role and TRANSACTION_READ before invoking this policy.
        // MVP staff scope is the single bank.
        return (staffUserId, transferId) -> true;
    }
}
