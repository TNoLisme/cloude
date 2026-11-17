package com.simulatedbank.banking.transfer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simulatedbank.banking.transfer.domain.IdempotencyStatus;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferIdentity;
import com.simulatedbank.banking.transfer.domain.TransferStatus;
import com.simulatedbank.transfercandidate.persistence.IdempotencyRecordJpaRepository;
import com.simulatedbank.transfercandidate.persistence.TransferEntity;
import com.simulatedbank.transfercandidate.persistence.TransferJpaRepository;
import com.simulatedbank.transfercandidate.persistence.TransferTerminalJpaAdapter;
import com.simulatedbank.transfercandidate.persistence.TestProviderAccountEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Isolated JPA validation and JPA/JDBC transaction proof; Account remains a synthetic provider stand-in. */
@Testcontainers
class TransferJpaCandidatePostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transfer_jpa").withUsername("postgres").withPassword("test");
    private static final UUID CLAIM_TOKEN = UUID.fromString("00000000-0000-4000-8000-000000000123");
    private static DataSource testDataSource;
    private static AnnotationConfigApplicationContext context;
    private static EntityManager sharedEntityManager;
    private static TransferTerminalJpaAdapter terminalAdapter;
    private static TransactionTemplate transactions;
    private static JdbcTemplate jdbc;
    private static TransferJpaRepository transfers;
    private static IdempotencyRecordJpaRepository idempotency;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        Flyway.configure().dataSource(ds).schemas("banking").defaultSchema("banking")
                .locations("classpath:db/migration", "classpath:db/transfer-fixture", "classpath:db/transfer-candidate")
                .load().migrate();
        jdbc = new JdbcTemplate(ds);
        // Extend only the synthetic provider fixture used by this test, never a production table.
        jdbc.execute("ALTER TABLE banking.account ADD COLUMN test_marker VARCHAR(100)");
        testDataSource = ds;
        context = new AnnotationConfigApplicationContext(TestJpaConfiguration.class);
        EntityManagerFactory emf = context.getBean(EntityManagerFactory.class);
        sharedEntityManager = SharedEntityManagerCreator.createSharedEntityManager(emf);
        transactions = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        transfers = context.getBean(TransferJpaRepository.class);
        idempotency = context.getBean(IdempotencyRecordJpaRepository.class);
        terminalAdapter = context.getBean(TransferTerminalJpaAdapter.class);
    }

    @AfterAll
    static void close() { if (context != null) context.close(); }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableJpaRepositories(basePackageClasses = TransferJpaRepository.class)
    static class TestJpaConfiguration {
        @Bean DataSource dataSource() { return testDataSource; }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setPackagesToScan("com.simulatedbank.transfercandidate.persistence");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.default_schema", "banking",
                    "hibernate.jdbc.time_zone", "UTC"));
            return factory;
        }
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory emf, DataSource ds) {
            var manager = new JpaTransactionManager(emf);
            manager.setDataSource(ds);
            return manager;
        }
        @Bean TransferTerminalJpaAdapter transferTerminalJpaAdapter(EntityManagerFactory emf, TransferJpaRepository repository) {
            return new TransferTerminalJpaAdapter(SharedEntityManagerCreator.createSharedEntityManager(emf), repository);
        }
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE banking.idempotency_record, banking.transfer RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE banking.account SET test_marker=NULL");
    }

    @Test
    void entitySaveAndRepositoriesReadDatabaseGeneratedValuesAndDecimal() {
        Long id = transactions.execute(status -> {
            TransferEntity saved = transfers.saveAndFlush(TransferEntity.pending(
                    new TransferIdentity("TX-JPA-1", 1, 2, 3, 4, null),
                    new TransferAmount(new BigDecimal("123.45"), "VND"), null, "correlation"));
            assertThat(saved.id()).isPositive();
            assertThat(saved.createdAt()).isNotNull();
            assertThat(saved.updatedAt()).isNotNull();
            insertKey("key-one", saved.id(), saved.createdAt());
            return saved.id();
        });
        transactions.executeWithoutResult(status -> {
            TransferEntity saved = transfers.findByReference("TX-JPA-1").orElseThrow();
            assertThat(saved.id()).isEqualTo(id);
            assertThat(saved.amount()).isEqualByComparingTo("123.45");
            assertThat(saved.status()).isEqualTo(TransferStatus.PENDING);
            assertThat(saved.currency()).isEqualTo("VND");
            var record = idempotency.findById("key-one").orElseThrow();
            assertThat(record.transferId()).isEqualTo(id);
            assertThat(record.status()).isEqualTo(IdempotencyStatus.RECEIVED);
            assertThat(record.requestHash()).isEqualTo("a".repeat(64));
            assertThat(record.expiresAt()).isEqualTo(record.createdAt().plusSeconds(86400));
            assertThat(record.toString()).doesNotContain("key-one", "a".repeat(64));
        });
    }

    @Test
    void terminalJsonResponseRoundTripsAndCannotBeMutatedThroughReturnedNode() {
        transactions.executeWithoutResult(status -> {
            TransferEntity transfer = transfers.saveAndFlush(pending("TX-JPA-REPLAY"));
            insertKey("replay-key", transfer.id(), transfer.createdAt());
            jdbc.update("UPDATE banking.transfer SET status='COMPLETED', completed_at=created_at WHERE transfer_id=?", transfer.id());
            jdbc.update("""
                    UPDATE banking.idempotency_record SET status='COMPLETED', response_status=201,
                        response_body=CAST(? AS jsonb), completed_at=created_at,
                        ttl_terminal_seconds=86400, ttl_terminal_config_version='test-v1'
                    WHERE transfer_id=?
                    """, "{\"reference\":\"TX-JPA-REPLAY\",\"status\":\"COMPLETED\"}", transfer.id());
        });
        transactions.executeWithoutResult(status -> {
            var record = idempotency.findById("replay-key").orElseThrow();
            assertThat(record.status()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(record.responseStatus()).isEqualTo((short) 201);
            assertThat(record.responseBody().path("reference").asText()).isEqualTo("TX-JPA-REPLAY");
            ((ObjectNode) record.responseBody()).put("reference", "modified");
            assertThat(record.responseBody().path("reference").asText()).isEqualTo("TX-JPA-REPLAY");
        });
        assertThat(jdbc.queryForObject("SELECT response_body ->> 'reference' FROM banking.idempotency_record WHERE idempotency_key='replay-key'", String.class))
                .isEqualTo("TX-JPA-REPLAY");
    }

    @Test
    void repositoryAndTerminalAdapterRefuseWritesWithoutExistingTransaction() {
        assertThatThrownBy(() -> transfers.saveAndFlush(pending("TX-MUST-JOIN")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> terminalAdapter.completePending(1, 1, CLAIM_TOKEN, Instant.now()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.transfer", Integer.class)).isZero();
    }

    @Test
    void conditionalTerminalWritePreservesOtherProvidersManagedEntities() {
        Long id = transactions.execute(status -> transfers.saveAndFlush(pending("TX-PROVIDER-STATE")).id());
        claim(id);
        transactions.executeWithoutResult(status -> {
            TestProviderAccountEntity account = sharedEntityManager.find(TestProviderAccountEntity.class, 3L);
            TransferEntity transfer = transfers.findById(id).orElseThrow();
            assertThat(sharedEntityManager.contains(account)).isTrue();
            assertThat(terminalAdapter.completePending(id, 1, CLAIM_TOKEN, transfer.createdAt().plusSeconds(1))).isEqualTo(1);
            assertThat(sharedEntityManager.contains(account)).isTrue();
            assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
            account.setMarker("still-managed-after-terminal-update");
        });
        assertThat(jdbc.queryForObject("SELECT test_marker FROM banking.account WHERE account_id=3", String.class))
                .isEqualTo("still-managed-after-terminal-update");
    }

    @Test
    void staleFenceThrowsAndMarksTransactionRollbackOnlyEvenWhenCallerCatchesIt() {
        Long id = transactions.execute(status -> transfers.saveAndFlush(pending("TX-FENCE-FAIL")).id());
        claim(id);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            TestProviderAccountEntity account = sharedEntityManager.find(TestProviderAccountEntity.class, 3L);
            account.setMarker("must-be-rolled-back");
            Instant at = transfers.findById(id).orElseThrow().createdAt().plusSeconds(1);
            assertThatThrownBy(() -> terminalAdapter.completePending(id, 1, UUID.randomUUID(), at))
                    .isInstanceOf(TransferTerminalJpaAdapter.TerminalTransitionRejectedException.class);
            assertThat(status.isRollbackOnly()).isTrue();
            account.setMarker("also-rolled-back");
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("SELECT test_marker FROM banking.account WHERE account_id=3", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM banking.transfer WHERE transfer_id=?", String.class, id)).isEqualTo("PENDING");
    }

    @Test
    void conditionalUpdateRejectsClockRegressionAndRefreshesManagedTransfer() {
        Long id = transactions.execute(status -> transfers.saveAndFlush(pending("TX-JPA-STATE")).id());
        claim(id);
        transactions.executeWithoutResult(status -> {
            TransferEntity pending = transfers.findById(id).orElseThrow();
            Instant terminal = pending.createdAt().plusSeconds(1);
            assertThat(transfers.completePending(id, 1, CLAIM_TOKEN, pending.createdAt().minusSeconds(1))).isZero();
            assertThat(transfers.failPending(id, 1, CLAIM_TOKEN, terminal, " ")).isZero();
            assertThat(transfers.failPending(id, 1, CLAIM_TOKEN, terminal, null)).isZero();
            assertThat(transfers.completePending(id, 1, CLAIM_TOKEN, null)).isZero();
            assertThat(transfers.completePending(id, 1, null, terminal)).isZero();
            assertThat(transfers.completePending(id, 1, UUID.randomUUID(), terminal)).isZero();
            assertThat(transfers.completePending(id, 2, CLAIM_TOKEN, terminal)).isZero();
            assertThat(terminalAdapter.completePending(id, 1, CLAIM_TOKEN, terminal)).isEqualTo(1);
            TransferEntity completed = transfers.findById(id).orElseThrow();
            assertThat(completed.status()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(completed.updatedAt()).isEqualTo(terminal);
            assertThat(transfers.failPending(id, 1, CLAIM_TOKEN, terminal, "LATE_FAILURE")).isZero();
            assertThat(transfers.completePending(id, 1, CLAIM_TOKEN, terminal)).isZero();
        });
    }

    @Test
    void competingTerminalUpdatesAllowExactlyOneWinner() throws Exception {
        Long id = transactions.execute(status -> transfers.saveAndFlush(pending("TX-JPA-RACE")).id());
        claim(id);
        Instant terminal = transactions.execute(status -> transfers.findById(id).orElseThrow().createdAt().plusSeconds(1));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> complete = workers.submit(() -> terminalRace(id, terminal, ready, start, false));
            Future<Integer> fail = workers.submit(() -> terminalRace(id, terminal, ready, start, true));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(complete.get(10, TimeUnit.SECONDS) + fail.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            String state = jdbc.queryForObject("SELECT status FROM banking.transfer WHERE transfer_id=?", String.class, id);
            assertThat(state).isIn("COMPLETED", "FAILED");
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    private static int terminalRace(long id, Instant terminalAt, CountDownLatch ready, CountDownLatch start, boolean fail) {
        try {
            return transactions.execute(status -> {
                ready.countDown();
                await(start);
                return fail ? terminalAdapter.failPending(id, 1, CLAIM_TOKEN, terminalAt, "INSUFFICIENT_BALANCE")
                        : terminalAdapter.completePending(id, 1, CLAIM_TOKEN, terminalAt);
            });
        } catch (TransferTerminalJpaAdapter.TerminalTransitionRejectedException expected) {
            return 0;
        }
    }

    private static void claim(long transferId) {
        jdbc.update("""
                UPDATE banking.transfer SET attempt_epoch=1, attempt_token=?, attempt_owner='test',
                    attempt_lease_expires_at=clock_timestamp() + interval '5 minutes', attempt_state='CLAIMED'
                WHERE transfer_id=?
                """, CLAIM_TOKEN, transferId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", ex);
        }
    }

    @Test
    void keyConflictRollsBackJpaInsertInSameDatabaseTransaction() {
        transactions.executeWithoutResult(status -> {
            TransferEntity first = transfers.saveAndFlush(pending("TX-JPA-WINNER"));
            insertKey("shared-key", first.id(), first.createdAt());
        });
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            TransferEntity loser = transfers.saveAndFlush(pending("TX-JPA-LOSER"));
            if (insertKey("shared-key", loser.id(), loser.createdAt()) == 0) throw new KeyConflict();
        })).isInstanceOf(KeyConflict.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.transfer", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.idempotency_record", Integer.class)).isEqualTo(1);
        transactions.executeWithoutResult(status -> assertThat(transfers.findByReference("TX-JPA-LOSER")).isEmpty());
    }

    private static TransferEntity pending(String reference) {
        return TransferEntity.pending(new TransferIdentity(reference, 1, 2, 3, 4, null),
                new TransferAmount(new BigDecimal("1.00"), "VND"), null, "correlation");
    }

    private static int insertKey(String key, long id, Instant createdAt) {
        List<Long> ids = jdbc.query("""
                INSERT INTO banking.idempotency_record (idempotency_key, request_hash, transfer_id, created_at, expires_at,
                    ttl_t1_seconds, ttl_t1_config_version)
                VALUES (?, ?, ?, ?, ?, 86400, 'test-v1')
                ON CONFLICT (idempotency_key) DO NOTHING RETURNING transfer_id
                """, (rs, row) -> rs.getLong(1), key, "a".repeat(64), id, Timestamp.from(createdAt),
                Timestamp.from(createdAt.plusSeconds(86400)));
        return ids.size();
    }

    private static final class KeyConflict extends RuntimeException { }
}
