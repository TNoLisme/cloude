package com.simulatedbank.banking.notification.application;

import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.notification.adapter.out.FakeSimulatedNotificationGateway;
import com.simulatedbank.banking.notification.adapter.out.NotificationJdbcRepository;
import com.simulatedbank.banking.notification.api.NotificationOutboxPort;
import com.simulatedbank.banking.notification.domain.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.simulatedbank.banking.support.IntegratedProviderReferences;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@Testcontainers
class NotificationTransactionalOutboxIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("audit/init-roles.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "banking_app");
        registry.add("spring.datasource.password", () -> "test_app");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "banking_migrator");
        registry.add("spring.flyway.password", () -> "test_migrator");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    private static boolean schemaInitialized = false;

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired NotificationOutboxPort outboxPort;
    @Autowired NotificationJdbcRepository repository;

    private FakeSimulatedNotificationGateway fakeGateway;
    private NotificationRetryPolicy retryPolicy;
    private NotificationOutboxWorker worker;
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry registry;
    private NotificationOutboxMetrics metrics;

    @BeforeEach
    void setUp() throws Exception {
        if (!schemaInitialized) {
            DriverManagerDataSource migratorDs = new DriverManagerDataSource();
            migratorDs.setUrl(postgres.getJdbcUrl());
            migratorDs.setUsername("banking_migrator");
            migratorDs.setPassword("test_migrator");
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("notification/notification-schema.sql"));
            populator.execute(migratorDs);
            try (var connection = migratorDs.getConnection()) {
                IntegratedProviderReferences.seed(connection);
            }
            schemaInitialized = true;
        }

        transactions.executeWithoutResult(status -> jdbc.execute("DELETE FROM banking.notification"));

        fakeGateway = new FakeSimulatedNotificationGateway();
        retryPolicy = new NotificationRetryPolicy(3, Duration.ofSeconds(5), Duration.ofSeconds(30));
        registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        metrics = new NotificationOutboxMetrics(registry);
        worker = new NotificationOutboxWorker(repository, fakeGateway, retryPolicy, transactions, Clock.systemUTC(), metrics);
    }

    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(status -> jdbc.execute("DELETE FROM banking.notification"));
    }

    @Test
    @DisplayName("Worker delivery does not inherit request context from caller thread")
    void workerDoesNotInheritCallerRequestContext() {
        TransferNotice notice = createNotice(1001L, 9911L, "TXN-CONTEXT-1");
        transactions.executeWithoutResult(status -> outboxPort.enqueueTransferNotificationsInT2(List.of(notice)));

        RequestContext caller = new RequestContext("corr-caller", java.util.UUID.randomUUID().toString(), null, null);
        try (RequestContextHolder.Scope ignored = RequestContextHolder.bind(caller)) {
            assertThat(worker.processBatch(1)).isEqualTo(1);
        } finally {
            RequestContextHolder.clear();
        }

        assertThat(RequestContextHolder.current()).isEmpty();
    }

    @Test
    @DisplayName("Worker records fixed-cardinality delivery metrics")
    void workerRecordsMetricsWithoutIdentifiersAsTags() {
        TransferNotice notice = createNotice(1001L, 9922L, "TXN-METRICS-1");
        transactions.executeWithoutResult(status -> outboxPort.enqueueTransferNotificationsInT2(List.of(notice)));
        worker.processBatch(1);
        assertThat(registry.get("banking.notification.outbox.claimed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("banking.notification.outbox.delivery")
                .tag("outcome", "sent").counter().count()).isEqualTo(1.0);
    }

    void t2RollbackLeavesZeroNotificationRows() {
        TransferNotice notice = createNotice(1001L, 9001L, "TXN-ROLLBACK-1");

        try {
            transactions.executeWithoutResult(status -> {
                outboxPort.enqueueTransferNotificationsInT2(List.of(notice));
                // Simulate failure in T2 before commit
                throw new RuntimeException("Simulated T2 business or audit failure");
            });
        } catch (RuntimeException ignored) {
            // Expected rollback
        }

        Integer count = jdbc.queryForObject("SELECT count(*) FROM banking.notification WHERE transfer_id = 9001", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("2. Two PostgreSQL connections cannot claim same active row (FOR UPDATE SKIP LOCKED)")
    void twoConnectionsCannotClaimSameActiveRow() throws Exception {
        TransferNotice notice = createNotice(1001L, 9002L, "TXN-CONCURRENT-CLAIM");
        transactions.executeWithoutResult(status -> outboxPort.enqueueTransferNotificationsInT2(List.of(notice)));

        CountDownLatch connection1ClaimedLatch = new CountDownLatch(1);
        CountDownLatch connection2DoneLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<List<NotificationItem>> worker1Future = executor.submit(() ->
                transactions.execute(status -> {
                    List<NotificationItem> claimed = repository.claimBatch(10, Duration.ofSeconds(30), "token-worker-1");
                    connection1ClaimedLatch.countDown();
                    try {
                        connection2DoneLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return claimed;
                })
        );

        Future<List<NotificationItem>> worker2Future = executor.submit(() -> {
            try {
                connection1ClaimedLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            List<NotificationItem> claimed = transactions.execute(status ->
                    repository.claimBatch(10, Duration.ofSeconds(30), "token-worker-2")
            );
            connection2DoneLatch.countDown();
            return claimed;
        });

        List<NotificationItem> claimed1 = worker1Future.get(10, TimeUnit.SECONDS);
        List<NotificationItem> claimed2 = worker2Future.get(10, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(claimed1).hasSize(1);
        assertThat(claimed2).isEmpty();
    }

    @Test
    @DisplayName("3. Lease expiry allows reclaim by another worker")
    void leaseExpiryAllowsReclaim() {
        TransferNotice notice = createNotice(1001L, 9003L, "TXN-LEASE-EXPIRE");
        transactions.executeWithoutResult(status -> outboxPort.enqueueTransferNotificationsInT2(List.of(notice)));

        // Worker 1 claims with expired lease in the past
        transactions.executeWithoutResult(status -> {
            repository.claimBatch(1, Duration.ofSeconds(30), "old-token");
            jdbc.execute("UPDATE banking.notification SET locked_until = clock_timestamp() - interval '5 seconds', next_retry_at = clock_timestamp() - interval '5 seconds' WHERE transfer_id = 9003");
        });

        // Worker 2 should now be able to reclaim the expired row
        List<NotificationItem> reclaimed = transactions.execute(status ->
                repository.claimBatch(1, Duration.ofSeconds(30), "new-token")
        );

        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).claimToken()).isEqualTo("new-token");
        assertThat(reclaimed.get(0).retryCount()).isEqualTo(2); // incremented from 1 to 2
    }

    @Test
    @DisplayName("4. Stale worker completion rejected")
    void staleWorkerCompletionRejected() {
        TransferNotice notice = createNotice(1001L, 9004L, "TXN-STALE-COMPLETION");
        transactions.executeWithoutResult(status -> outboxPort.enqueueTransferNotificationsInT2(List.of(notice)));

        // Worker 1 claims with token-1
        List<NotificationItem> claimed1 = transactions.execute(status ->
                repository.claimBatch(1, Duration.ofSeconds(30), "token-1")
        );
        long notifId = claimed1.get(0).notificationId();

        // Worker 1 lease expires and Worker 2 claims with token-2
        transactions.executeWithoutResult(status ->
                jdbc.execute("UPDATE banking.notification SET locked_until = clock_timestamp() - interval '1 second', next_retry_at = clock_timestamp() - interval '1 second' WHERE notification_id = " + notifId)
        );
        boolean acceptedAfterExpiry = Boolean.TRUE.equals(transactions.execute(status ->
                repository.markSent(notifId, "token-1", Instant.now())));
        assertThat(acceptedAfterExpiry).isFalse();

        transactions.execute(status -> repository.claimBatch(1, Duration.ofSeconds(30), "token-2"));

        // Now Worker 1 attempts to complete using stale token-1
        boolean updated = Boolean.TRUE.equals(transactions.execute(status ->
                repository.markSent(notifId, "token-1", Instant.now())
        ));

        assertThat(updated).isFalse();

        // Verify status in DB is still RETRYING with token-2, not SENT
        var row = jdbc.queryForMap("SELECT status, claim_token FROM banking.notification WHERE notification_id = ?", notifId);
        assertThat(row.get("status")).isEqualTo("RETRYING");
        assertThat(row.get("claim_token")).isEqualTo("token-2");
    }

    @Test
    @DisplayName("5. Gateway success marks SENT with sent_at and clears lease")
    void gatewaySuccessMarksSent() {
        TransferNotice notice = createNotice(1001L, 9005L, "TXN-SUCCESS");
        transactions.executeWithoutResult(status -> outboxPort.enqueueTransferNotificationsInT2(List.of(notice)));

        fakeGateway.setBehavior(FakeSimulatedNotificationGateway.Behavior.SUCCESS);
        int processed = worker.processBatch(10);

        assertThat(processed).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT status, sent_at, claim_token, locked_until FROM banking.notification WHERE transfer_id = 9005");
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("locked_until")).isNull();
    }

    @Test
    @DisplayName("6. Gateway failure schedules retry with safe failure code")
    void gatewayFailureSchedulesRetry() {
        TransferNotice notice = createNotice(1001L, 9006L, "TXN-FAIL-RETRY");
        transactions.executeWithoutResult(status -> outboxPort.enqueueTransferNotificationsInT2(List.of(notice)));

        fakeGateway.setBehavior(FakeSimulatedNotificationGateway.Behavior.TIMEOUT);
        worker.processBatch(10);

        var row = jdbc.queryForMap("SELECT status, retry_count, next_retry_at, failure_code FROM banking.notification WHERE transfer_id = 9006");
        assertThat(row.get("status")).isEqualTo("RETRYING");
        assertThat(((Number) row.get("retry_count")).intValue()).isEqualTo(1);
        assertThat(row.get("next_retry_at")).isNotNull();
        assertThat(row.get("failure_code")).isEqualTo("GATEWAY_TIMEOUT");
    }

    @Test
    @DisplayName("7. Retry reaches FAILED at configured max attempts")
    void retryReachesFailedAtMaxAttempts() {
        TransferNotice notice = createNotice(1001L, 9007L, "TXN-EXHAUST-RETRY");
        transactions.executeWithoutResult(status -> outboxPort.enqueueTransferNotificationsInT2(List.of(notice)));

        fakeGateway.setBehavior(FakeSimulatedNotificationGateway.Behavior.FAIL_TEMPORARY);

        // Attempt 1: retryCount becomes 1, RETRYING
        worker.processBatch(10);
        // Force lease/next_retry_at to past within a transaction
        transactions.executeWithoutResult(status ->
                jdbc.execute("UPDATE banking.notification SET next_retry_at = clock_timestamp() - interval '1 second', locked_until = NULL WHERE transfer_id = 9007")
        );

        // Attempt 2: retryCount becomes 2, RETRYING
        worker.processBatch(10);
        transactions.executeWithoutResult(status ->
                jdbc.execute("UPDATE banking.notification SET next_retry_at = clock_timestamp() - interval '1 second', locked_until = NULL WHERE transfer_id = 9007")
        );

        // Attempt 3: retryCount reaches 3 (maxAttempts), status becomes FAILED
        worker.processBatch(10);

        var row = jdbc.queryForMap("SELECT status, retry_count, failure_code FROM banking.notification WHERE transfer_id = 9007");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("retry_count")).intValue()).isEqualTo(3);
        assertThat(row.get("failure_code")).isEqualTo("DELIVERY_EXHAUSTED");
    }

    @Test
    @DisplayName("8. Success then crash before SENT can duplicate; proves at-least-once duplicate window")
    void successThenCrashBeforeSentCanDuplicate() {
        TransferNotice notice = createNotice(1001L, 9008L, "TXN-CRASH-DUP");
        transactions.executeWithoutResult(status -> outboxPort.enqueueTransferNotificationsInT2(List.of(notice)));

        // Gateway sends to destination, but simulation crashes before DB markSent executes
        fakeGateway.setBehavior(FakeSimulatedNotificationGateway.Behavior.SUCCESS_THEN_CRASH);
        try {
            worker.processBatch(10);
        } catch (FakeSimulatedNotificationGateway.SimulatedCrashException ignored) {
            // Process crashed
        }

        assertThat(fakeGateway.getDeliveryCount("TXN-CRASH-DUP")).isEqualTo(1);

        // Row is still RETRYING in DB because worker crashed before updating DB
        var rowBefore = jdbc.queryForMap("SELECT status FROM banking.notification WHERE transfer_id = 9008");
        assertThat(rowBefore.get("status")).isEqualTo("RETRYING");

        // Expire lease within a transaction
        transactions.executeWithoutResult(status ->
                jdbc.execute("UPDATE banking.notification SET next_retry_at = clock_timestamp() - interval '1 second', locked_until = clock_timestamp() - interval '1 second' WHERE transfer_id = 9008")
        );

        // Second worker now reclaims and delivers successfully
        fakeGateway.setBehavior(FakeSimulatedNotificationGateway.Behavior.SUCCESS);
        worker.processBatch(10);

        assertThat(fakeGateway.getDeliveryCount("TXN-CRASH-DUP")).isEqualTo(2); // Sent twice!
        var rowAfter = jdbc.queryForMap("SELECT status FROM banking.notification WHERE transfer_id = 9008");
        assertThat(rowAfter.get("status")).isEqualTo("SENT");
    }

    @Test
    @DisplayName("9. Batch bound is strictly respected")
    void batchBoundRespected() {
        for (int i = 1; i <= 5; i++) {
            TransferNotice notice = createNotice(1001L, (long) (9010 + i), "TXN-BATCH-" + i);
            transactions.executeWithoutResult(status -> outboxPort.enqueueTransferNotificationsInT2(List.of(notice)));
        }

        List<NotificationItem> claimed = transactions.execute(status ->
                repository.claimBatch(2, Duration.ofSeconds(30), "batch-token")
        );

        assertThat(claimed).hasSize(2);
    }

    @Test
    @DisplayName("10. No transfer or ledger mutation on T3 failure")
    void noTransferOrLedgerMutationOnT3Failure() {
        long probeId = System.nanoTime();
        // T2: Money path commits successfully
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO audit_test.business_probe (id) VALUES (?)", probeId);
            TransferNotice notice = createNotice(1001L, 9009L, "TXN-ISOLATION-1");
            outboxPort.enqueueTransferNotificationsInT2(List.of(notice));
        });

        // Verify T2 business state committed
        Integer probeCount = jdbc.queryForObject("SELECT count(*) FROM audit_test.business_probe WHERE id = ?", Integer.class, probeId);
        assertThat(probeCount).isEqualTo(1);

        // T3: Notification fails completely
        fakeGateway.setBehavior(FakeSimulatedNotificationGateway.Behavior.TIMEOUT);
        worker.processBatch(10);

        // Notification failed in T3, but business state remains intact!
        Integer probeCountAfter = jdbc.queryForObject("SELECT count(*) FROM audit_test.business_probe WHERE id = ?", Integer.class, probeId);
        assertThat(probeCountAfter).isEqualTo(1);
    }

    @Test
    @DisplayName("11. No OTP plaintext or raw credentials in payload, log or DB")
    void noOtpPlaintextInPayloadOrDb() {
        TransferNotice notice = createNotice(1001L, 9099L, "TXN-PII-CHECK");
        transactions.executeWithoutResult(status -> outboxPort.enqueueTransferNotificationsInT2(List.of(notice)));

        String payloadJson = jdbc.queryForObject("SELECT payload_masked::text FROM banking.notification WHERE transfer_id = 9099", String.class);
        assertThat(payloadJson).doesNotContain("otp", "password", "pin", "token", "secret");
        assertThat(payloadJson).contains("**** **** 1234"); // Properly masked
    }

    @Test
    @DisplayName("12. Worker rejects invocation from an active caller transaction")
    void workerRejectsActiveCallerTransaction() {
        TransferNotice notice = createNotice(1001L, 9088L, "TXN-REJECT-TX");
        transactions.executeWithoutResult(status -> outboxPort.enqueueTransferNotificationsInT2(List.of(notice)));

        // Invoking worker from inside an active transaction is rejected immediately
        RuntimeException failure = catchThrowableOfType(
                () -> transactions.executeWithoutResult(outerTxStatus -> worker.processBatch(10)),
                RuntimeException.class
        );
        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not run inside an active transaction");

        // Outbox row remains untouched in PENDING state
        var rowBefore = jdbc.queryForMap("SELECT status, retry_count, claim_token FROM banking.notification WHERE transfer_id = 9088");
        assertThat(rowBefore.get("status")).isEqualTo("PENDING");
        assertThat(((Number) rowBefore.get("retry_count")).intValue()).isZero();
        assertThat(rowBefore.get("claim_token")).isNull();

        // Normal background call outside any transaction succeeds
        fakeGateway.setBehavior(FakeSimulatedNotificationGateway.Behavior.SUCCESS);
        int processed = worker.processBatch(10);
        assertThat(processed).isEqualTo(1);

        var rowAfter = jdbc.queryForMap("SELECT status, claim_token FROM banking.notification WHERE transfer_id = 9088");
        assertThat(rowAfter.get("status")).isEqualTo("SENT");
        assertThat(rowAfter.get("claim_token")).isNull();
    }

    @Test
    @DisplayName("13. Repeated worker crash and lease expiry exceeding maxAttempts marks row FAILED and stops re-delivery")
    void repeatedCrashReclaimExceedingMaxAttemptsReachesTerminalFailed() {
        TransferNotice notice = createNotice(1001L, 9077L, "TXN-CRASH-EXHAUST");
        transactions.executeWithoutResult(status -> outboxPort.enqueueTransferNotificationsInT2(List.of(notice)));

        // Cycle 1: Worker 1 claims (retry_count becomes 1), then crashes (simulated by not delivering)
        transactions.execute(status -> repository.claimBatch(1, Duration.ofSeconds(30), "crash-token-1"));
        // Lease expires
        transactions.executeWithoutResult(status ->
                jdbc.execute("UPDATE banking.notification SET locked_until = clock_timestamp() - interval '1 second', next_retry_at = clock_timestamp() - interval '1 second' WHERE transfer_id = 9077")
        );

        // Cycle 2: Worker 2 claims (retry_count becomes 2), then crashes
        transactions.execute(status -> repository.claimBatch(1, Duration.ofSeconds(30), "crash-token-2"));
        // Lease expires
        transactions.executeWithoutResult(status ->
                jdbc.execute("UPDATE banking.notification SET locked_until = clock_timestamp() - interval '1 second', next_retry_at = clock_timestamp() - interval '1 second' WHERE transfer_id = 9077")
        );

        // Cycle 3: Worker 3 claims (retry_count becomes 3), then crashes
        transactions.execute(status -> repository.claimBatch(1, Duration.ofSeconds(30), "crash-token-3"));
        // Lease expires
        transactions.executeWithoutResult(status ->
                jdbc.execute("UPDATE banking.notification SET locked_until = clock_timestamp() - interval '1 second', next_retry_at = clock_timestamp() - interval '1 second' WHERE transfer_id = 9077")
        );

        // Gateway is available and healthy
        fakeGateway.setBehavior(FakeSimulatedNotificationGateway.Behavior.SUCCESS);

        // Cycle 4: Worker 4 processes batch. Since maxAttempts=3 and retry_count reaches 4 > 3,
        // it must NOT deliver to gateway, but immediately transition to FAILED with DELIVERY_EXHAUSTED
        int processed = worker.processBatch(10);
        assertThat(processed).isEqualTo(1);

        // Verify gateway was NEVER called because attempts were exhausted by prior crashes
        assertThat(fakeGateway.getDeliveryCount("TXN-CRASH-EXHAUST")).isZero();

        // Verify row is in terminal FAILED state with lease cleared
        var row = jdbc.queryForMap("SELECT status, retry_count, failure_code, claim_token, locked_until FROM banking.notification WHERE transfer_id = 9077");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("retry_count")).intValue()).isEqualTo(4);
        assertThat(row.get("failure_code")).isEqualTo("DELIVERY_EXHAUSTED");
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("locked_until")).isNull();

        // Subsequent worker batches will never claim this row again
        int nextProcessed = worker.processBatch(10);
        assertThat(nextProcessed).isZero();
    }

    @Test
    @DisplayName("14. deliverItem rejects invocation when caller transaction is active and holding database lock")
    void deliverItemRejectsActiveCallerTransactionHoldingLock() {
        TransferNotice notice = createNotice(1001L, 9089L, "TXN-DIRECT-DELIVER-GUARD");
        transactions.executeWithoutResult(status -> outboxPort.enqueueTransferNotificationsInT2(List.of(notice)));

        // Claim row outside transaction first
        String token = "test-token-direct";
        List<NotificationItem> claimed = repository.claimBatch(1, Duration.ofSeconds(30), token);
        assertThat(claimed).hasSize(1);
        NotificationItem item = claimed.get(0);

        fakeGateway.setBehavior(FakeSimulatedNotificationGateway.Behavior.SUCCESS);

        // Caller attempts to call deliverItem from inside an active database transaction that holds a lock
        RuntimeException failure = catchThrowableOfType(
                () -> transactions.executeWithoutResult(status -> {
                    // Outer transaction acquires a lock on the notification row
                    jdbc.queryForList("SELECT * FROM banking.notification WHERE notification_id = ? FOR UPDATE", item.notificationId());

                    // Invoking deliverItem while holding database lock must fail-fast before calling gateway
                    worker.deliverItem(item, token);
                }),
                RuntimeException.class
        );

        assertThat(failure).isNotNull()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not run inside an active transaction");

        // Gateway was NEVER called while outer transaction held locks
        assertThat(fakeGateway.getDeliveryCount("TXN-DIRECT-DELIVER-GUARD")).isZero();
    }

    private TransferNotice createNotice(Long customerId, Long transferId, String reference) {
        return new TransferNotice(
                customerId,
                transferId,
                reference,
                NotificationChannel.IN_APP,
                "TRANSFER_DEBIT_ALERT",
                Map.of(
                        "reference", reference,
                        "amount", "100000",
                        "currency", "VND",
                        "maskedAccount", "**** **** 1234",
                        "balanceAfter", "900000",
                        "outcome", "COMPLETED",
                        "timestamp", "2026-11-15T08:00:00Z"
                ),
                "corr-" + reference
        );
    }
}
