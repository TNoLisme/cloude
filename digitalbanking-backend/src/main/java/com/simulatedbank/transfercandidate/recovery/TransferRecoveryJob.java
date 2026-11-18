package com.simulatedbank.transfercandidate.recovery;

import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.transfer.application.TransferPendingMetrics;
import com.simulatedbank.banking.transfer.application.TransferRecoveryCandidate;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/** Explicit candidate wiring only. Invocation must establish SYSTEM context via the configured adapter. */
public final class TransferRecoveryJob {
    private static final Logger LOG = LoggerFactory.getLogger(TransferRecoveryJob.class);
    private final TransferRecoveryCandidate recovery;

    private final TransferPendingMetrics pendingMetrics;

    public TransferRecoveryJob(TransferRecoveryCandidate recovery, TransferPendingMetrics pendingMetrics) {
        this.recovery = Objects.requireNonNull(recovery);
        this.pendingMetrics = Objects.requireNonNull(pendingMetrics);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void refreshPendingMetrics() {
        pendingMetrics.refresh();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public synchronized void run() {
        try (var scope = bindJob()) {
            recovery.recoverBatch();
        } catch (RuntimeException failure) {
            LOG.error("Transfer recovery scan failed");
        }
        try (var scope = bindJob()) {
            recovery.purgeBatch();
        } catch (RuntimeException failure) {
            LOG.error("Transfer idempotency purge failed");
        }
    }

    private static RequestContextHolder.Scope bindJob() {
        return RequestContextHolder.bind(new RequestContext(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), null, null));
    }
}
