package com.simulatedbank.banking.admin.adapter.in;

import com.simulatedbank.banking.admin.application.AdminIdempotencyService;
import com.simulatedbank.banking.common.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * BE-ADM-CON-03: bounded purge of terminal, expired Admin idempotency records.
 *
 * <p>Uses the existing retention cleanup schedule ({@code app.jobs.retention-cleanup-cron}) and deletes only
 * {@code COMPLETED}/{@code FAILED} rows past {@code expires_at}. {@code RECEIVED} rows are never eligible, and
 * the store query uses {@code FOR UPDATE SKIP LOCKED} so concurrent job executions cannot collide.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
public class AdminIdempotencyPurgeJob {

    private static final Logger LOG = LoggerFactory.getLogger("com.simulatedbank.banking.admin.IDEMPOTENCY");

    /** Bounded so one run cannot hold a long transaction; the next scheduled run continues. */
    private static final int MAX_BATCHES = 20;

    private final AdminIdempotencyService idempotency;
    private final int batchSize;

    public AdminIdempotencyPurgeJob(AdminIdempotencyService idempotency, AppProperties properties) {
        this.idempotency = idempotency;
        this.batchSize = properties.admin().idempotencyPurgeBatchSize();
    }

    @Scheduled(cron = "${app.jobs.retention-cleanup-cron:0 0 3 * * *}")
    public void purgeExpired() {
        int total = 0;
        try {
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                int deleted = idempotency.purgeExpired(batchSize);
                total += deleted;
                if (deleted < batchSize) {
                    break;
                }
            }
            if (total > 0) {
                LOG.info("ADMIN_IDEMPOTENCY_PURGED count={}", total);
            }
        } catch (RuntimeException storageFailure) {
            // No SQL/exception message: it can contain identifiers. Leave rows for the next run.
            LOG.error("ADMIN_IDEMPOTENCY_PURGE_FAILED");
        }
    }
}
