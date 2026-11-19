package com.simulatedbank.banking.account.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.simulatedbank.banking.account.application.AccountAuditPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * BE-ACC-11 — audit C3 của Account trên đường đọc.
 *
 * <p>Xác nhận: (1) owner mismatch phát một sự kiện {@code SECURITY_ACCESS_DENIED} có đủ
 * actor/entity/reason/correlation, không mang số tài khoản/ciphertext/blind index/PII;
 * (2) audit lỗi là best-effort — không đổi response 403, có fallback security log + metric
 * thất bại; (3) metrics hỏng không làm mất security log. Durable {@code audit_log}
 * (ACC-DR-15) vẫn BLOCKED nên adapter này là bằng chứng runtime duy nhất hiện có.</p>
 */
class AccountSecurityLogAuditAdapterTest {

    private static final Logger AUDIT_LOGGER =
            (Logger) LoggerFactory.getLogger("com.simulatedbank.banking.account.AUDIT");
    private static final AccountAuditPort.AccountAuditEvent DENIED =
            AccountAuditPort.AccountAuditEvent.accessDenied(501L, 2001L);

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
    void accessDeniedEmitsPiiSafeEvidenceAndMetric() {
        var adapter = new AccountSecurityLogAuditAdapter(registry, customers(), event -> {});

        adapter.recordStandalone(DENIED, "acc-audit-correlation-1");

        assertThat(logEvents.list)
                .filteredOn(event -> event.getFormattedMessage().equals("SECURITY_AUDIT"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getTimeStamp()).isPositive();
                    Map<String, Object> fields = fields(event);
                    assertThat(fields)
                            .containsEntry("action", "SECURITY_ACCESS_DENIED")
                            .containsEntry("outcome", "REJECTED")
                            .containsEntry("actor_customer_id", 501L)
                            .containsEntry("entity_type", "ACCOUNT")
                            .containsEntry("entity_id", "2001")
                            .containsEntry("reason_code", "AUTH_FORBIDDEN")
                            .containsEntry("correlation_id", "acc-audit-correlation-1");
                    assertThat(fields.toString()).doesNotContain(
                            "0012345678", "0055555555", "Bearer", "token", "ciphertext");
                });
        assertThat(registry.counter("account.audit.events",
                "action", "SECURITY_ACCESS_DENIED", "outcome", "REJECTED").count()).isEqualTo(1.0);
        assertThat(registry.counter("account.audit.failures",
                "action", "SECURITY_ACCESS_DENIED", "outcome", "REJECTED").count()).isZero();
    }

    @Test
    void auditProviderFailureDoesNotChangeTheDeniedOutcomeAndEmitsFallbackEvidence() {
        var adapter = new FailingAdapter(registry);

        assertThatCode(() -> adapter.recordStandalone(DENIED, "acc-audit-correlation-2"))
                .doesNotThrowAnyException();

        assertThat(logEvents.list)
                .filteredOn(event -> event.getFormattedMessage().equals("SECURITY_AUDIT_WRITE_FAILED"))
                .singleElement()
                .satisfies(event -> assertThat(fields(event))
                        .containsEntry("action", "SECURITY_ACCESS_DENIED")
                        .containsEntry("outcome", "WRITE_FAILED")
                        .containsEntry("correlation_id", "acc-audit-correlation-2"));
        assertThat(registry.counter("account.audit.failures",
                "action", "SECURITY_ACCESS_DENIED", "outcome", "REJECTED").count()).isEqualTo(1.0);
    }

    @Test
    void metricsInfrastructureFailureDoesNotRemoveTheSecurityLogEvidence() {
        var adapter = new AccountSecurityLogAuditAdapter(new FailingRegistry(), customers(), event -> {});

        assertThatCode(() -> adapter.recordStandalone(DENIED, "acc-audit-correlation-3"))
                .doesNotThrowAnyException();

        assertThat(logEvents.list)
                .filteredOn(event -> event.getFormattedMessage().equals("SECURITY_AUDIT"))
                .singleElement()
                .satisfies(event -> assertThat(fields(event))
                        .containsEntry("entity_id", "2001")
                        .containsEntry("correlation_id", "acc-audit-correlation-3"));
        assertThat(logEvents.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .startsWith("AUDIT_METRIC_WRITE_FAILED")
                        .contains("account.audit.events"));
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private static com.simulatedbank.banking.customer.api.CustomerQuery customers() {
        return org.mockito.Mockito.mock(com.simulatedbank.banking.customer.api.CustomerQuery.class);
    }

    private static final class FailingAdapter extends AccountSecurityLogAuditAdapter {
        private FailingAdapter(MeterRegistry registry) {
            super(registry, customers(), event -> {});
        }

        @Override
        protected void emit(AccountAuditEvent event, String correlationId) {
            throw new IllegalStateException("audit persistence unavailable");
        }
    }

    private static final class FailingRegistry extends SimpleMeterRegistry {
        @Override
        public Counter counter(String name, String... tags) {
            throw new IllegalStateException("metrics write rejected");
        }
    }
}
