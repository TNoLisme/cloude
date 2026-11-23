package com.simulatedbank.banking.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.simulatedbank.banking.common.config.SafeJsonLogEncoder;
import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.common.context.SecurityAuditFallbackLog;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransferObservabilityTest {
    @Test
    void c3EmptyAndExceptionBothFallBackAndSuccessfulWriteDoesNot() {
        var actor = new TransferActor(1, 2L, Set.of("ROLE_CUSTOMER"), "corr", "req");
        var meters = new SimpleMeterRegistry();
        new SecurityDenyAudit(event -> Optional.empty(), meters, Clock.systemUTC()).idempotencyConflict(actor);
        new SecurityDenyAudit(event -> { throw new IllegalStateException("synthetic private details"); },
                meters, Clock.systemUTC()).idempotencyConflict(actor);
        new SecurityDenyAudit(event -> Optional.of(5L), meters, Clock.systemUTC()).idempotencyConflict(actor);
        assertThat(meters.get("transfer.security.deny.fallback").counter().count()).isEqualTo(2);
    }

    @Test
    void fallbackJsonRetainsSafeContextAndOmitsProviderExceptionAndRawActor() {
        var logger = (Logger) LoggerFactory.getLogger(SecurityAuditFallbackLog.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        var context = new RequestContext("safe-correlation", UUID.randomUUID().toString(), null, null);
        var actor = new TransferActor(123456789, 2L, Set.of("ROLE_CUSTOMER"),
                context.correlationId(), context.requestId());
        try (var scope = RequestContextHolder.bind(context)) {
            var audit = new SecurityDenyAudit(event -> {
                throw new IllegalStateException("synthetic-otp-1234\r\nforged-event");
            }, new SimpleMeterRegistry(), Clock.systemUTC());
            audit.idempotencyConflict(actor);
            assertThat(captured.list).singleElement().satisfies(event -> {
                var json = new String(new SafeJsonLogEncoder().encode(event), StandardCharsets.UTF_8);
                assertThat(json).contains("SECURITY_AUDIT_FALLBACK", "SECURITY_IDEMPOTENCY_CONFLICT",
                        "REJECTED", context.correlationId(), context.requestId(), "sha256:")
                        .doesNotContain("synthetic-otp", "forged-event", "123456789");
                assertThat(json.lines()).hasSize(1);
            });
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void registryFailureDoesNotChangeC3Handling() {
        var actor = new TransferActor(1, 2L, Set.of("ROLE_CUSTOMER"), "corr", "req");
        var meters = mock(SimpleMeterRegistry.class);
        when(meters.counter("transfer.security.deny.fallback")).thenThrow(new IllegalStateException("registry error"));
        new SecurityDenyAudit(event -> Optional.empty(), meters, Clock.systemUTC()).idempotencyConflict(actor);
    }

    @Test
    void pendingSnapshotDistinguishesUnknownStaleAndEmptyWithoutQueryingOnScrape() {
        var clock = new MockClock();
        var fail = new AtomicBoolean(true);
        var empty = new AtomicBoolean(false);
        var meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        var collector = new TransferPendingMetrics(meters, () -> {
            if (fail.get()) throw new IllegalStateException("database error");
            return empty.get() ? new TransferPendingMetrics.Snapshot(0, 0)
                    : new TransferPendingMetrics.Snapshot(4, 321);
        });
        collector.refresh();
        assertThat(meters.get("transfer.pending.count").gauge().value()).isNaN();
        assertThat(meters.get("transfer.pending.snapshot.available").gauge().value()).isZero();

        fail.set(false);
        collector.refresh();
        assertThat(meters.get("transfer.pending.count").gauge().value()).isEqualTo(4);
        assertThat(meters.get("transfer.pending.age.seconds").gauge().value()).isEqualTo(321);
        assertThat(meters.get("transfer.pending.snapshot.available").gauge().value()).isEqualTo(1);

        fail.set(true);
        clock.add(Duration.ofSeconds(61));
        collector.refresh();
        assertThat(meters.get("transfer.pending.count").gauge().value()).isEqualTo(4);
        assertThat(meters.get("transfer.pending.snapshot.age.seconds").gauge().value()).isEqualTo(61);
        assertThat(meters.get("transfer.pending.snapshot.available").gauge().value()).isZero();

        fail.set(false);
        empty.set(true);
        collector.refresh();
        assertThat(meters.get("transfer.pending.count").gauge().value()).isZero();
        assertThat(meters.get("transfer.pending.age.seconds").gauge().value()).isZero();
    }

    @Test
    void prometheusScrapeMatchesAlertNamesAndPublishesAttemptHistogram() {
        var meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var telemetry = new TransferTelemetry(meters);
            var pending = new TransferPendingMetrics(meters, () -> new TransferPendingMetrics.Snapshot(0, 0));
            pending.refresh();
            new SecurityDenyAudit(event -> Optional.empty(), meters, Clock.systemUTC());
            telemetry.attempt(TransferTelemetry.Phase.T2, TransferTelemetry.Attempt.COMMITTED,
                    System.nanoTime() - Duration.ofMillis(20).toNanos());
            assertThat(meters.scrape()).contains("transfer_pending_age_seconds",
                    "transfer_pending_snapshot_available", "transfer_pending_snapshot_age_seconds",
                    "transfer_security_deny_fallback_total", "transfer_audit_required_failure_total",
                    "transfer_recovery_incident_total", "transfer_t2_duration_seconds_bucket");
        } finally {
            meters.close();
        }
    }

    @Test
    void notificationRejectsInconsistentPolicyAndRequiresT2() {
        var first = new TransferNotifications.Recipient(2, TransferNotifications.Channel.IN_APP, "SENDER");
        var second = new TransferNotifications.Recipient(2, TransferNotifications.Channel.IN_APP, "RECEIVER");
        var outside = new TransferNotifications.Recipient(99, TransferNotifications.Channel.SMS, "RECEIVER");
        var facts = new TransferNotifications.Facts(1, "TXN20261117A1B2C3D4", 2, 2,
                new TransferAmount(new BigDecimal("30.00"), "VND"),
                "**** **** 0001", "**** **** 0002", "corr");
        var outcome = TransferSettlementCandidate.Outcome.completed();
        var conflicting = new TransferNotifications((input, result) -> List.of(first, second),
                batch -> { throw new AssertionError("unexpected enqueue"); });
        var unrelated = new TransferNotifications((input, result) -> List.of(outside),
                batch -> { throw new AssertionError("unexpected enqueue"); });
        assertThatThrownBy(() -> conflicting.enqueue(facts, outcome)).hasMessageContaining("must join T2");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> conflicting.enqueue(facts, outcome)).hasMessageContaining("Conflicting");
            assertThatThrownBy(() -> unrelated.enqueue(facts, outcome)).hasMessageContaining("outside the transfer");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
