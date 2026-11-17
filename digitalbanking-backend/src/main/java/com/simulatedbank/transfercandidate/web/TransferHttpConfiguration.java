package com.simulatedbank.transfercandidate.web;

import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.transfer.application.AccountOwnershipPort;
import com.simulatedbank.banking.transfer.application.AuditEventPort;
import com.simulatedbank.banking.transfer.application.FraudFlagReadPort;
import com.simulatedbank.banking.transfer.application.SecurityDenyAudit;
import com.simulatedbank.banking.transfer.application.TransferCreateService;
import com.simulatedbank.banking.transfer.application.TransferOrchestrator;
import com.simulatedbank.banking.transfer.application.TransferQueryService;
import com.simulatedbank.banking.transfer.application.TransferReadPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;

/**
 * Explicit registration of the Transfer HTTP boundary. Not component-scanned and not active in
 * production until BE-TRF-04/05 wire the orchestrator and real provider adapters; tests import it
 * with test-only fakes. No fake bean is registered here for production use.
 */
public class TransferHttpConfiguration {

    @Bean
    public SecurityDenyAudit transferSecurityDenyAudit(AuditEventPort port, MeterRegistry metrics, Clock clock) {
        return new SecurityDenyAudit(port, metrics, clock);
    }

    @Bean
    public TransferCreateService transferCreateService(TransferOrchestrator orchestrator, SecurityDenyAudit denyAudit) {
        return new TransferCreateService(orchestrator, denyAudit);
    }

    @Bean
    public TransferQueryService transferQueryService(TransferReadPort readPort, AccountOwnershipPort ownershipPort,
            FraudFlagReadPort fraudPort, SecurityDenyAudit denyAudit) {
        return new TransferQueryService(readPort, ownershipPort, fraudPort, denyAudit);
    }

    @Bean
    public TransferController transferController(TransferCreateService createService, TransferQueryService queryService,
            AppProperties properties) {
        return new TransferController(createService, queryService, properties.security().http().maxRequestBodyBytes());
    }
}