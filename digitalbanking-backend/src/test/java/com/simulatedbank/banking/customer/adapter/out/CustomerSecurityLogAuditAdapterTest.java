package com.simulatedbank.banking.customer.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.simulatedbank.banking.customer.application.CustomerAuditPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * BE-CUS-IMP-07: bằng chứng adapter Audit C3 của Customer ghi đủ actor/action/target/outcome/
 * correlation, không mang PII, và fail-open đúng policy khi provider Audit chưa khả dụng.
 */
class CustomerSecurityLogAuditAdapterTest {

    private static final Logger AUDIT_LOGGER =
            (Logger) LoggerFactory.getLogger("com.simulatedbank.banking.customer.AUDIT");
    private static final CustomerAuditPort.CustomerAuditEvent DENIED =
            CustomerAuditPort.CustomerAuditEvent.accessDenied(501L, "OPERATIONS", 2001L);

    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        logEvents.start();
        AUDIT_LOGGER.addAppender(logEvents);
    }

    @AfterEach
    void tearDown() {
        AUDIT_LOGGER.detachAppender(logEvents);
        logEvents.stop();
        registry.close();
    }

    @Test
    @DisplayName("CT-API-017/T-AUD-16 Customer boundary: denied audit evidence contains only PII-safe fields")
    void deniedOperationProducesTraceablePiiSafeEvidence() {
        var adapter = new CustomerSecurityLogAuditAdapter(registry);

        adapter.recordStandalone(DENIED, "cus-audit-correlation-1");

        List<ILoggingEvent> evidence = logEvents.list.stream()
                .filter(event -> event.getFormattedMessage().equals("SECURITY_AUDIT"))
                .toList();
        assertThat(evidence).singleElement().satisfies(event -> {
            assertThat(event.getTimeStamp()).isPositive();
            Map<String, Object> fields = fields(event);
            assertThat(fields).containsEntry("action", "SECURITY_ACCESS_DENIED")
                    .containsEntry("outcome", "REJECTED")
                    .containsEntry("actor_user_id", 501L)
                    .containsEntry("actor_roles", "OPERATIONS")
                    .containsEntry("entity_type", "CUSTOMER")
                    .containsEntry("entity_id", "2001")
                    .containsEntry("reason_code", "AUTH_FORBIDDEN")
                    .containsEntry("correlation_id", "cus-audit-correlation-1");
            assertThat(fields.toString()).doesNotContain(
                    "Nguyễn", "synthetic@example.com", "+84900000123", "Bearer", "token");
        });
        assertThat(registry.counter("customer.audit.events", "action", "SECURITY_ACCESS_DENIED",
                "outcome", "REJECTED").count()).isEqualTo(1.0);
    }

    @Test
    void auditProviderFailureDoesNotChangeTheDeniedOutcomeAndEmitsFallbackEvidence() {
        var adapter = new FailingAdapter(registry);

        assertThatCode(() -> adapter.recordStandalone(DENIED, "cus-audit-correlation-2"))
                .doesNotThrowAnyException();

        assertThat(logEvents.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).isEqualTo("SECURITY_AUDIT_WRITE_FAILED");
            assertThat(fields(event)).containsEntry("action", "SECURITY_ACCESS_DENIED")
                    .containsEntry("outcome", "WRITE_FAILED")
                    .containsEntry("correlation_id", "cus-audit-correlation-2");
        });
        assertThat(registry.counter("customer.audit.failures", "action", "SECURITY_ACCESS_DENIED",
                "outcome", "REJECTED").count()).isEqualTo(1.0);
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private static final class FailingAdapter extends CustomerSecurityLogAuditAdapter {
        private FailingAdapter(SimpleMeterRegistry registry) {
            super(registry);
        }

        @Override
        protected void emit(CustomerAuditEvent event, String correlationId) {
            throw new IllegalStateException("audit persistence unavailable");
        }
    }
}
