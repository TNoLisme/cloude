package com.simulatedbank.banking.support;

import com.simulatedbank.banking.audit.api.AuditModuleApi;
import com.simulatedbank.banking.audit.api.AuditAction;
import com.simulatedbank.banking.audit.api.AuditActorType;
import com.simulatedbank.banking.audit.api.AuditEvent;
import com.simulatedbank.banking.audit.api.AuditOutcome;
import com.simulatedbank.banking.audit.api.AuditRecordRef;
import java.time.Instant;
import java.util.Optional;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
    @Primary
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
        public AuditRecordRef recordInTransaction(AuditEvent event) {
            if (event == null || !java.util.Set.of(AuditAction.LIMIT_CHANGE_REQUESTED,
                    AuditAction.LIMIT_CHANGE_APPROVED, AuditAction.LIMIT_CHANGE_REJECTED)
                    .contains(event.action())) throw new IllegalArgumentException("Rejected audit fixture input");
            Long id = jdbc.queryForObject("INSERT INTO limit_test.audit_event(action,correlation_id) VALUES (?,?) RETURNING id",
                    Long.class, event.action().name(), event.correlationId());
            return new AuditRecordRef(id, "0".repeat(64));
        }

        @Override
        public Optional<AuditRecordRef> recordStandalone(AuditEvent event) {
            try {
                standalone.executeWithoutResult(tx -> jdbc.update(
                        "INSERT INTO limit_test.audit_event(action,correlation_id) VALUES ('SECURITY_ACCESS_DENIED',?)", event.correlationId()));
                return Optional.of(new AuditRecordRef(1, "0".repeat(64)));
            } catch (RuntimeException unavailable) {
                metrics.counter("audit.security.fallback").increment();
                return Optional.empty();
            }
        }
    }

    public static AuditEvent approvalEvent(AuditAction action, String correlationId) {
        return new AuditEvent(action, AuditActorType.USER, 1L, "OPERATIONS", "APPROVAL_REQUEST", "81",
                null, null, null, null, AuditOutcome.SUCCESS, correlationId, "limit-test-request", null,
                Instant.parse("2026-11-18T00:00:00Z"));
    }
}
