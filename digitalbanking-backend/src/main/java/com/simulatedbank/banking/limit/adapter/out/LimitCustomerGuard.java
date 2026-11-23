package com.simulatedbank.banking.limit.adapter.out;

import com.simulatedbank.banking.common.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(name = "app.limit.enabled", havingValue = "true", matchIfMissing = true)
class LimitCustomerGuard {
    private final JdbcTemplate jdbc;
    private final String defaultLockTimeout;

    LimitCustomerGuard(JdbcTemplate jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        long timeoutMs = properties.transfer().t2LockTimeoutMs();
        if (timeoutMs <= 0) throw new IllegalArgumentException("Transfer lock timeout must be positive");
        this.defaultLockTimeout = timeoutMs + "ms";
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long customerId) {
        requireWritableReadCommitted();
        jdbc.queryForObject("""
                SELECT set_config('lock_timeout',
                    CASE WHEN current_setting('lock_timeout') IN ('0', '0ms') THEN ?
                         ELSE current_setting('lock_timeout') END, true)
                """, String.class, defaultLockTimeout);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> null, "limit:customer:" + customerId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryLock(long customerId) {
        requireWritableReadCommitted();
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                Boolean.class, "limit:customer:" + customerId));
    }

    private void requireWritableReadCommitted() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException("Limit customer guard requires a writable transaction");
        }
        if (!"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation", String.class))) {
            throw new IllegalArgumentException("Limit customer guard requires READ COMMITTED isolation");
        }
    }
}
