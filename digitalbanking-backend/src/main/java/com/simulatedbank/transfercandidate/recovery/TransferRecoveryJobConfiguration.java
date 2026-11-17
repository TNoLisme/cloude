package com.simulatedbank.transfercandidate.recovery;

import com.simulatedbank.banking.transfer.adapter.out.persistence.TransferRecoveryStore;
import com.simulatedbank.banking.transfer.application.TransferPendingMetrics;
import com.simulatedbank.banking.transfer.application.TransferRecoveryCandidate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in candidate registration outside the backend component-scan root. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class TransferRecoveryJobConfiguration {
    @Bean
    TransferRecoveryJob transferRecoveryJob(TransferRecoveryCandidate recovery, TransferPendingMetrics pendingMetrics) {
        return new TransferRecoveryJob(recovery, pendingMetrics);
    }

    @Bean
    TransferPendingMetrics transferPendingMetrics(MeterRegistry registry, PlatformTransactionManager primaryManager,
                                                  JdbcTemplate primaryJdbc) {
        var store = new TransferRecoveryStore(primaryJdbc);
        var read = new TransactionTemplate(primaryManager);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
        read.setTimeout(2);
        return new TransferPendingMetrics(registry, () -> read.execute(status -> store.pendingSnapshot()));
    }
}
