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
import com.simulatedbank.banking.transfer.application.TransferStaffScope;
import com.simulatedbank.banking.transfer.application.TransferTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;

/** Explicit Transfer HTTP registration. The application scan does not activate this configuration. */
public class TransferHttpConfiguration {

    @Bean
    public TransferTelemetry transferTelemetry(MeterRegistry metrics) {
        return new TransferTelemetry(metrics);
    }

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
            FraudFlagReadPort fraudPort, SecurityDenyAudit denyAudit, TransferStaffScope staffScope) {
        return new TransferQueryService(readPort, ownershipPort, fraudPort, denyAudit, staffScope);
    }

    @Bean
    public TransferController transferController(TransferCreateService createService, TransferQueryService queryService,
            AppProperties properties) {
        return new TransferController(createService, queryService, properties.security().http().maxRequestBodyBytes());
    }
}
