package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.simulatedbank.banking.identity.adapter.out.SecurityLogAuditAdapter;
import com.simulatedbank.banking.identity.application.AuthAuditPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * BE-ID-IMP-13 — adapter audit theo hợp đồng `AuthAuditPort` (design/01 §5.2):
 * C2 fail-closed lan truyền (cùng TX caller), C3 fail-open có fallback security log + metric
 * thất bại, metrics hạ tầng lỗi không làm mất bằng chứng, không log secret.
 */
class SecurityLogAuditAdapterTest {

    private static final Logger AUDIT_LOGGER =
            (Logger) LoggerFactory.getLogger("com.simulatedbank.banking.identity.AUDIT");
    private static final AuthAuditPort.AuthAuditEvent C3_EVENT = AuthAuditPort.AuthAuditEvent
            .c3User(AuthAuditPort.AuthAction.AUTH_LOGIN_FAILED, 101L, "WRONG_PASSWORD", null);
    private static final AuthAuditPort.AuthAuditEvent C2_EVENT = AuthAuditPort.AuthAuditEvent
            .c2(AuthAuditPort.AuthAction.AUTH_LOGIN_SUCCESS, 101L, null, null);

    private SimpleMeterRegistry registry;
    private SecurityLogAuditAdapter adapter;
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

    /** Registry chỉ hỏng với meter cụ thể; meter khác vẫn đếm thật (failures counter phải sống). */
    private static class PartiallyBrokenRegistry extends SimpleMeterRegistry {
        private final String brokenName;
        PartiallyBrokenRegistry(String brokenName) { this.brokenName = brokenName; }
        @Override
        public io.micrometer.core.instrument.Counter counter(String name, String... tags) {
            if (name.equals(brokenName)) {
                throw new IllegalStateException("metrics write rejected");
            }
            return super.counter(name, tags);
        }
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        adapter = new SecurityLogAuditAdapter(registry);
        logEvents.start();
        AUDIT_LOGGER.addAppender(logEvents);
    }

    @AfterEach
    void tearDown() {
        AUDIT_LOGGER.detachAppender(logEvents);
        logEvents.stop();
    }

    @Test
    void successfulEventEmitsSecurityLogAndSuccessMetric() {
        adapter.recordStandalone(C3_EVENT, "corr-1");

        List<String> messages = logEvents.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anySatisfy(message -> {
            assertThat(message).contains("SECURITY_AUDIT", "AUTH_LOGIN_FAILED", "corr-1");
            assertThat(message).doesNotContain("hunter2", "Bearer");
        });
        assertThat(registry.counter("auth.audit.events",
                "action", "AUTH_LOGIN_FAILED", "class", "C3", "mode", "BEST_EFFORT",
                "outcome", "SUCCESS").count()).isEqualTo(1.0);
        assertThat(registry.counter("auth.audit.failures", "action", "AUTH_LOGIN_FAILED",
                "class", "C3").count()).isZero();
    }

    /** Persist thật của module audit (design/01 §6.1) chưa có — test double mô phỏng lỗi write. */
    private static final class FailingEmitAdapter extends SecurityLogAuditAdapter {
        FailingEmitAdapter(io.micrometer.core.instrument.MeterRegistry registry) { super(registry); }
        @Override protected void emit(AuthAuditEvent event, String correlationId, String mode) {
            throw new IllegalStateException("audit persistence unavailable");
        }
    }

    @Test
    void c2WriteFailurePropagatesSoTheCallerTransactionRollsBack() {
        var failingAdapter = new FailingEmitAdapter(registry);

        // design/01 §5.2: audit C2 trong TX; lỗi lan ra → TX nghiệp vụ rollback (fail-closed).
        assertThatThrownBy(() -> failingAdapter.recordInTransaction(C2_EVENT, "corr-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit persistence unavailable");
    }

    @Test
    void c3FailureIsSwallowedWithFallbackLogAndFailureMetric() {
        var failingAdapter = new FailingEmitAdapter(registry);

        // design/01 §5.2: C3 fail không được ảnh hưởng response; fallback log + metric thay thế.
        assertThatCode(() -> failingAdapter.recordStandalone(C3_EVENT, "corr-3"))
                .doesNotThrowAnyException();

        List<String> messages = logEvents.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anySatisfy(message ->
                assertThat(message).contains("SECURITY_AUDIT_WRITE_FAILED", "AUTH_LOGIN_FAILED", "corr-3"));
        assertThat(registry.counter("auth.audit.failures", "action", "AUTH_LOGIN_FAILED",
                "class", "C3").count()).isEqualTo(1.0);
    }

    @Test
    void metricsInfrastructureFailureDoesNotRemoveTheSecurityLogEvidence() {
        var broken = new PartiallyBrokenRegistry(null) {
            @Override
            public io.micrometer.core.instrument.Counter counter(String name, String... tags) {
                throw new IllegalStateException("metrics down");
            }
        };
        var failingAdapter = new SecurityLogAuditAdapter(broken);

        assertThatCode(() -> failingAdapter.recordStandalone(C3_EVENT, "corr-4")).doesNotThrowAnyException();

        List<String> messages = logEvents.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anySatisfy(message -> assertThat(message).contains("SECURITY_AUDIT", "corr-4"));
    }

    @Test
    void sensitiveDetailIsRedactedBeforeLogging() {
        var event = AuthAuditPort.AuthAuditEvent
                .c3User(AuthAuditPort.AuthAction.AUTH_LOGIN_FAILED, 101L, "WRONG_PASSWORD",
                        "context password=hunter2 Authorization=Bearer abc.def");
        adapter.recordStandalone(event, "corr-5");

        List<String> messages = logEvents.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anySatisfy(message -> {
            assertThat(message).contains("context");
            assertThat(message).doesNotContain("hunter2", "Bearer abc.def");
        });
    }
}
