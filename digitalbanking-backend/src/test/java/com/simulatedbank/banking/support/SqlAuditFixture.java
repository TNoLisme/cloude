package com.simulatedbank.banking.support;

import com.simulatedbank.banking.audit.api.AuditModuleApi;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-only audit contract double. SQL probes prove caller wiring, never the audit implementation. */
@TestConfiguration(proxyBeanMethods = false)
public class SqlAuditFixture {
    @Bean
    public AuditModuleApi sqlAuditFixtureSink(JdbcTemplate jdbc, PlatformTransactionManager manager, MeterRegistry metrics) {
        return new Sink(jdbc, manager, metrics);
    }

    public static class Sink implements AuditModuleApi {
        private final JdbcTemplate jdbc;
        private final TransactionTemplate standalone;
        private final MeterRegistry metrics;

        public Sink(JdbcTemplate jdbc, PlatformTransactionManager manager, MeterRegistry metrics) {
            this.jdbc = jdbc;
            this.metrics = metrics;
            standalone = new TransactionTemplate(manager);
            standalone.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public void recordInTransaction(ApprovalEvent event) {
            if (event == null || !java.util.Set.of("LIMIT_CHANGE_REQUESTED", "LIMIT_CHANGE_APPROVED", "LIMIT_CHANGE_REJECTED")
                    .contains(event.action())) throw new IllegalArgumentException("Rejected audit fixture input");
            jdbc.update("INSERT INTO limit_test.audit_event(action,correlation_id) VALUES (?,?)", event.action(), event.correlationId());
        }

        @Override
        public void recordDenied(DeniedEvent event) {
            try {
                standalone.executeWithoutResult(tx -> jdbc.update(
                        "INSERT INTO limit_test.audit_event(action,correlation_id) VALUES ('SECURITY_ACCESS_DENIED',?)", event.correlationId()));
            } catch (RuntimeException unavailable) {
                metrics.counter("audit.security.fallback").increment();
            }
        }
    }
}
