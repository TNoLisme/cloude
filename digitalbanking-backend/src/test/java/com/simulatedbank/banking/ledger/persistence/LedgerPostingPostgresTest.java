package com.simulatedbank.banking.ledger.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.simulatedbank.banking.ledger.adapter.out.persistence.LedgerEntryEntity;
import com.simulatedbank.banking.ledger.adapter.out.persistence.LedgerEntryJpaRepository;
import com.simulatedbank.banking.ledger.adapter.out.persistence.LedgerEntryPersistenceAdapter;
import com.simulatedbank.banking.ledger.api.LedgerPosting;
import com.simulatedbank.banking.ledger.api.dto.PostingCommand;
import com.simulatedbank.banking.ledger.api.dto.PostingRef;
import com.simulatedbank.banking.ledger.application.LedgerPostingService;
import com.simulatedbank.banking.ledger.domain.EntrySide;
import com.simulatedbank.banking.ledger.domain.InvalidPostingCommandException;
import com.simulatedbank.banking.ledger.domain.LedgerInvariantException;
import com.simulatedbank.banking.ledger.domain.PostedEntry;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * BE-LED-03 provider evidence on PostgreSQL 16 (LED-POST-01..14). Isolated candidate harness:
 * migrator runs db/ledger-fixture + db/ledger-candidate; runtime posting uses banking_app; the
 * service is a Spring proxy with MANDATORY on the primary JpaTransactionManager. Final state is
 * read through a fresh connection after commit/rollback. Fixture FK Account/Transfer are test-only;
 * production chain and full T2 integration remain Blocked (BE-LED-02/04).
 */
@Testcontainers
class LedgerPostingPostgresTest {

    private static final String DB = "digital_banking";
    private static final String APP = "banking_app";
    private static final String MIGRATOR = "banking_migrator";
    private static final String APP_PASSWORD = UUID.randomUUID().toString();
    private static final String MIGRATOR_PASSWORD = UUID.randomUUID().toString();
    private static final long SOURCE = 1001;
    private static final long DESTINATION = 1002;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName(DB)
            .withUsername("postgres")
            .withEnv("BANKING_APP_PASSWORD", APP_PASSWORD)
            .withEnv("BANKING_MIGRATION_PASSWORD", MIGRATOR_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath(
                    Path.of("docker/postgres/init/01-create-roles.sql"), 0755),
                    "/docker-entrypoint-initdb.d/01-create-roles.sql");

    private static AnnotationConfigApplicationContext context;
    private static DataSource appDataSource;
    private static LedgerPosting posting;
    private static TransactionTemplate callerTx;
    private static TestLedgerEntryStore store;
    private static JdbcTemplate appJdbc;
    private static EntityManagerFactory emf;

    @BeforeAll
    static void initialize() {
        Flyway.configure().dataSource(url(), MIGRATOR, MIGRATOR_PASSWORD)
                .schemas("banking").defaultSchema("banking")
                .locations("classpath:db/ledger-fixture", "classpath:db/ledger-candidate")
                .load().migrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(url(), APP, APP_PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        appDataSource = ds;
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        posting = context.getBean(LedgerPosting.class);
        callerTx = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        store = context.getBean(TestLedgerEntryStore.class);
        appJdbc = new JdbcTemplate(appDataSource);
        emf = context.getBean(EntityManagerFactory.class);
    }

    @AfterAll
    static void close() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetFaults() {
        store.reset();
    }

    // ---------------------------------------------------------------- LED-POST-01
    @Test
    void validCommandCommitsFullPairWithGeneratedIdsAndRoundTrip() {
        long transfer = newTransfer();
        PostingCommand command = command(transfer, new BigDecimal("125.50"), BigDecimal.ZERO,
                new BigDecimal("325.50"), "transfer:" + transfer);
        PostingRef ref = callerTx.execute(status -> posting.postDoubleEntry(command));

        assertThat(ref).isNotNull();
        assertThat(ref.debitEntryId()).isPositive();
        assertThat(ref.creditEntryId()).isPositive().isNotEqualTo(ref.debitEntryId());
        List<Map<String, Object>> rows = rows(transfer);
        assertThat(rows).hasSize(2);
        Map<String, Object> debit = rows.get(0);
        Map<String, Object> credit = rows.get(1);
        assertThat(debit.get("entry_id")).isEqualTo(ref.debitEntryId());
        assertThat(credit.get("entry_id")).isEqualTo(ref.creditEntryId());
        assertThat(debit.get("account_id")).isEqualTo(SOURCE);
        assertThat(((Number) debit.get("entry_seq")).intValue()).isEqualTo(1);
        assertThat(debit.get("entry_type")).isEqualTo("DEBIT");
        assertThat(new BigDecimal(debit.get("amount").toString())).isEqualByComparingTo("125.50");
        assertThat(debit.get("currency").toString().trim()).isEqualTo("VND");
        assertThat(new BigDecimal(debit.get("balance_after").toString())).isEqualByComparingTo("0");
        assertThat(debit.get("correlation_id")).isEqualTo("transfer:" + transfer);
        assertThat(debit.get("created_at")).isNotNull();
        assertThat(credit.get("account_id")).isEqualTo(DESTINATION);
        assertThat(((Number) credit.get("entry_seq")).intValue()).isEqualTo(2);
        assertThat(credit.get("entry_type")).isEqualTo("CREDIT");
        assertThat(new BigDecimal(credit.get("balance_after").toString())).isEqualByComparingTo("325.50");
        assertThat(credit.get("created_at")).isNotNull();
    }

    // ---------------------------------------------------------------- LED-POST-02
    @Test
    void invalidIdsRejectBeforeInsertAndLeaveNoEntry() {
        long transfer = newTransfer();
        PostingCommand sameAccount = new PostingCommand(transfer, SOURCE, SOURCE, new BigDecimal("1.00"),
                "VND", BigDecimal.ZERO, BigDecimal.ONE, "c:" + transfer);
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(sameAccount)))
                .isInstanceOf(InvalidPostingCommandException.class);
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                new PostingCommand(0, SOURCE, DESTINATION, new BigDecimal("1.00"), "VND",
                        BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                .isInstanceOf(InvalidPostingCommandException.class);
        assertThat(count(transfer)).isZero();
    }

    // ---------------------------------------------------------------- LED-POST-03
    @Test
    void amountAndBalanceBoundariesRejectOrCommit() {
        long zeroAmount = newTransfer();
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                command(zeroAmount, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                .isInstanceOf(InvalidPostingCommandException.class);
        assertThat(count(zeroAmount)).isZero();

        long negativeBalance = newTransfer();
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                command(negativeBalance, new BigDecimal("1.00"), new BigDecimal("-0.01"), BigDecimal.ONE, "c"))))
                .isInstanceOf(InvalidPostingCommandException.class);
        assertThat(count(negativeBalance)).isZero();

        long overflow = newTransfer();
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                command(overflow, new BigDecimal("100000000000000000.00"), BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                .isInstanceOf(InvalidPostingCommandException.class);
        assertThat(count(overflow)).isZero();

        long minimum = newTransfer();
        callerTx.execute(s -> posting.postDoubleEntry(
                command(minimum, new BigDecimal("0.01"), BigDecimal.ZERO, new BigDecimal("0.01"), "c")));
        assertThat(count(minimum)).isEqualTo(2);

        long maximum = newTransfer();
        callerTx.execute(s -> posting.postDoubleEntry(command(maximum, new BigDecimal("99999999999999999.99"),
                BigDecimal.ZERO, new BigDecimal("99999999999999999.99"), "c")));
        assertThat(new BigDecimal(rows(maximum).get(0).get("amount").toString()))
                .isEqualByComparingTo("99999999999999999.99");
    }

    // ---------------------------------------------------------------- LED-POST-04
    @ParameterizedTest
    @ValueSource(strings = {"1", "1.0", "1.00"})
    void acceptsScaleUpToTwoAndStoresScaleTwo(String amount) {
        long transfer = newTransfer();
        callerTx.execute(s -> posting.postDoubleEntry(
                command(transfer, new BigDecimal(amount), BigDecimal.ZERO, BigDecimal.ONE, "c")));
        assertThat(new BigDecimal(rows(transfer).get(0).get("amount").toString())).isEqualByComparingTo("1.00");
        assertThat(rows(transfer).get(0).get("amount").toString()).isEqualTo("1.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.000", "1.235", "1E+17"})
    void rejectsExcessScaleOrOverflowBeforeWrite(String amount) {
        long transfer = newTransfer();
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                command(transfer, new BigDecimal(amount), BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                .isInstanceOf(InvalidPostingCommandException.class);
        assertThat(count(transfer)).isZero();
    }

    @Test
    void rejectsBalanceScaleAboveTwo() {
        long transfer = newTransfer();
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                command(transfer, new BigDecimal("1.00"), BigDecimal.ZERO, new BigDecimal("2.345"), "c"))))
                .isInstanceOf(InvalidPostingCommandException.class);
        assertThat(count(transfer)).isZero();
    }

    // ---------------------------------------------------------------- LED-POST-05
    @Test
    void currencyAndCorrelationBoundaries() {
        for (String currency : new String[] {null, "vnd", "USD", "VND "}) {
            long transfer = newTransfer();
            assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                    new PostingCommand(transfer, SOURCE, DESTINATION, new BigDecimal("1.00"), currency,
                            BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                    .isInstanceOf(InvalidPostingCommandException.class);
            assertThat(count(transfer)).isZero();
        }
        for (String correlation : new String[] {null, "", "bad correlation", "a".repeat(65), "bad#char"}) {
            long transfer = newTransfer();
            assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                    command(transfer, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, correlation))))
                    .isInstanceOf(InvalidPostingCommandException.class);
            assertThat(count(transfer)).isZero();
        }
        long ok = newTransfer();
        callerTx.execute(s -> posting.postDoubleEntry(
                command(ok, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "a".repeat(64))));
        assertThat(rows(ok).get(0).get("correlation_id").toString()).hasSize(64);
    }

    // ---------------------------------------------------------------- LED-POST-06
    @Test
    void failsWithoutTransactionAndLeavesNoEntry() {
        long transfer = newTransfer();
        assertThatThrownBy(() -> posting.postDoubleEntry(
                command(transfer, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count(transfer)).isZero();
    }

    @Test
    void readOnlyCallerTransactionDoesNotCommitEntries() {
        long transfer = newTransfer();
        TransactionTemplate readOnly = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.execute(s -> posting.postDoubleEntry(
                command(transfer, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                .isInstanceOf(RuntimeException.class);
        assertThat(count(transfer)).isZero();
    }

    @Test
    void wrongTransactionManagerIsNotAValidPostingContext() {
        long transfer = newTransfer();
        TransactionTemplate jdbcTx = new TransactionTemplate(context.getBean(JdbcTransactionManager.class));
        assertThatThrownBy(() -> jdbcTx.execute(s -> posting.postDoubleEntry(
                command(transfer, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count(transfer)).isZero();
    }

    // ---------------------------------------------------------------- LED-POST-07
    @Test
    void callerFailureAfterPostingRollsBackBothEntries() {
        long transfer = newTransfer();
        assertThatThrownBy(() -> callerTx.execute(s -> {
            posting.postDoubleEntry(command(transfer, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c"));
            throw new IllegalStateException("caller business failure after posting");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count(transfer)).isZero();
    }

    @Test
    void failureAfterFirstInsertRollsBackDebit() {
        long transfer = newTransfer();
        store.failCreditAppend = true;
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                command(transfer, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                .isInstanceOf(DataAccessException.class);
        assertThat(count(transfer)).isZero();
    }

    @Test
    void failureAfterSecondInsertRollsBackBoth() {
        long transfer = newTransfer();
        store.failFlush = true;
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                command(transfer, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                .isInstanceOf(DataAccessException.class);
        assertThat(count(transfer)).isZero();
    }

    // ---------------------------------------------------------------- LED-POST-08
    @Test
    void creditForeignKeyViolationPropagatesAndDebitIsNotCommitted() {
        long transfer = newTransfer();
        PostingCommand orphanDestination = new PostingCommand(transfer, SOURCE, 999999,
                new BigDecimal("1.00"), "VND", BigDecimal.ZERO, BigDecimal.ONE, "c");
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(orphanDestination)))
                .isInstanceOf(DataAccessException.class);
        assertThat(count(transfer)).isZero();
    }

    @Test
    void flushAndReadBackFailuresPropagateWithoutPostingRef() {
        long flushTransfer = newTransfer();
        store.failFlush = true;
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                command(flushTransfer, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                .isInstanceOf(DataAccessException.class);
        assertThat(count(flushTransfer)).isZero();

        store.reset();
        long readTransfer = newTransfer();
        store.failRead = true;
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                command(readTransfer, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                .isInstanceOf(DataAccessException.class);
        assertThat(count(readTransfer)).isZero();
    }

    // ---------------------------------------------------------------- LED-POST-09
    @Test
    void repostOverExistingPairFailsAndPreservesCommittedRows() {
        long transfer = newTransfer();
        seedPair(transfer, new BigDecimal("1.00"));
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                command(transfer, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                .isInstanceOf(DataAccessException.class);
        assertThat(count(transfer)).isEqualTo(2);
    }

    @Test
    void repostOverPartialSeqOneOrSeqTwoFailsWithoutRepair() {
        long onlyDebit = newTransfer();
        seedEntry(onlyDebit, SOURCE, 1, "DEBIT", new BigDecimal("1.00"), BigDecimal.ZERO);
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                command(onlyDebit, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                .isInstanceOf(DataAccessException.class);
        assertThat(count(onlyDebit)).isEqualTo(1);

        long onlyCredit = newTransfer();
        seedEntry(onlyCredit, DESTINATION, 2, "CREDIT", new BigDecimal("1.00"), BigDecimal.ONE);
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                command(onlyCredit, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                .isInstanceOf(DataAccessException.class);
        assertThat(count(onlyCredit)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- LED-POST-10
    @Test
    void readBackAmountMismatchIsDetectedAndRealInsertsRollBack() {
        // Append-only trigger + constraints forbid real DML corruption of ledger_entry, so the
        // projection is mocked; the two INSERTs and the PostgreSQL rollback remain real.
        long transfer = newTransfer();
        store.readMutator = rows -> List.of(rows.get(0), new PostedEntry(rows.get(1).entryId(),
                rows.get(1).transferId(), rows.get(1).accountId(), 2, EntrySide.CREDIT,
                rows.get(1).amount().add(BigDecimal.ONE), "VND", rows.get(1).balanceAfter(),
                rows.get(1).correlationId()));
        assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                command(transfer, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                .isInstanceOf(LedgerInvariantException.class);
        assertThat(count(transfer)).isZero();
    }

    @Test
    void schemaBlockedCorruptionsAreRejectedViaMockedProjectionAndRollBackRealInserts() {
        // The schema cannot represent these (CHECK seq/type, unique transfer_seq, append-only trigger),
        // so the projection is mocked; the two real INSERTs must still roll back (limitation disclosed).
        List<java.util.function.UnaryOperator<List<PostedEntry>>> mutations = List.of(
                rows -> List.of(rows.get(0)),                                   // partial
                rows -> List.of(rows.get(0), rows.get(1), rows.get(1)),         // extra/duplicate
                rows -> List.of(rows.get(1), rows.get(0)),                      // wrong order
                rows -> List.of(new PostedEntry(0, rows.get(0).transferId(), rows.get(0).accountId(), 1,
                        EntrySide.DEBIT, rows.get(0).amount(), "VND", rows.get(0).balanceAfter(),
                        rows.get(0).correlationId()), rows.get(1)),             // non-positive ID
                rows -> List.of(rows.get(0), new PostedEntry(rows.get(1).entryId(), rows.get(1).transferId(),
                        SOURCE, 2, EntrySide.CREDIT, rows.get(1).amount(), "VND", rows.get(1).balanceAfter(),
                        rows.get(1).correlationId())));                         // wrong account
        for (var mutation : mutations) {
            store.reset();
            long transfer = newTransfer();
            store.readMutator = mutation;
            assertThatThrownBy(() -> callerTx.execute(s -> posting.postDoubleEntry(
                    command(transfer, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c"))))
                    .isInstanceOf(LedgerInvariantException.class);
            assertThat(count(transfer)).isZero();
        }
    }

    // ---------------------------------------------------------------- LED-POST-11
    @Test
    void correlationMismatchWithValidCountAndSumRollsBackEvenIfCallerSwallowsAndCommits() {
        long transfer = newTransfer();
        // Count stays 2 and signed sum stays 0; only the correlation differs (mocked projection).
        store.readMutator = rows -> List.of(rows.get(0), new PostedEntry(rows.get(1).entryId(),
                rows.get(1).transferId(), rows.get(1).accountId(), 2, EntrySide.CREDIT,
                rows.get(1).amount(), "VND", rows.get(1).balanceAfter(), "other-correlation"));
        // Caller catches the invariant failure then tries to commit: TX is rollback-only, commit must fail.
        assertThatThrownBy(() -> callerTx.execute(s -> {
            try {
                posting.postDoubleEntry(command(transfer, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c"));
            } catch (LedgerInvariantException ignored) {
                // caller attempts to continue and commit
            }
            return null;
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(count(transfer)).isZero();
    }

    // ---------------------------------------------------------------- LED-POST-12
    @ParameterizedTest(name = "winner commits={0}")
    @ValueSource(booleans = {true, false})
    void concurrentSameTransferSerializesOnUnique(boolean winnerCommits) throws Exception {
        long transfer = newTransfer();
        PostingCommand command = command(transfer, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c");
        CountDownLatch winnerInserted = new CountDownLatch(1);
        CountDownLatch loserAboutToPost = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        AtomicLong winnerPid = new AtomicLong();
        AtomicLong loserPid = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PostingRef> winner = executor.submit(() -> callerTx.execute(s -> {
                PostingRef ref = posting.postDoubleEntry(command);
                winnerPid.set(appJdbc.queryForObject("SELECT pg_backend_pid()", Long.class));
                winnerInserted.countDown();
                awaitLatch(releaseWinner);
                if (!winnerCommits) {
                    throw new IllegalStateException("winner rolls back");
                }
                return ref;
            }));
            Future<PostingRef> loser = executor.submit(() -> {
                awaitLatch(winnerInserted);
                return callerTx.execute(s -> {
                    loserPid.set(appJdbc.queryForObject("SELECT pg_backend_pid()", Long.class));
                    loserAboutToPost.countDown();
                    return posting.postDoubleEntry(command);
                });
            });
            assertThat(winnerInserted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(loserAboutToPost.await(10, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                    assertThat(appJdbc.queryForObject(
                            "SELECT " + winnerPid.get() + " = ANY(pg_blocking_pids(" + loserPid.get() + "))",
                            Boolean.class)).isTrue());
            releaseWinner.countDown();

            if (winnerCommits) {
                assertThat(winner.get(10, TimeUnit.SECONDS)).isNotNull();
                assertThatThrownBy(() -> loser.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(DataAccessException.class);
            } else {
                assertThatThrownBy(() -> winner.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class);
                assertThat(loser.get(10, TimeUnit.SECONDS)).isNotNull();
            }
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
        }
        // Exactly one balanced pair regardless of winner outcome; never two pairs.
        assertThat(count(transfer)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- LED-POST-13
    @Test
    void hibernateValidatedRealLedgerMappingOnCandidateSchema() {
        assertThat(emf).isNotNull();
        assertThat(emf.getMetamodel().getEntities())
                .anyMatch(entity -> entity.getJavaType().equals(LedgerEntryEntity.class));
    }

    @Test
    void runtimeCanInsertSelectButNotMutateLedger() {
        long transfer = newTransfer();
        callerTx.execute(s -> posting.postDoubleEntry(
                command(transfer, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ONE, "c")));
        assertThat(count(transfer)).isEqualTo(2);
        assertThatThrownBy(() -> appJdbc.update(
                "UPDATE banking.ledger_entry SET amount = 2 WHERE transfer_id = " + transfer))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> appJdbc.update(
                "DELETE FROM banking.ledger_entry WHERE transfer_id = " + transfer))
                .isInstanceOf(DataAccessException.class);
        assertThat(count(transfer)).isEqualTo(2);
        assertThat(new BigDecimal(rows(transfer).get(0).get("amount").toString())).isEqualByComparingTo("1.00");
    }

    // ---------------------------------------------------------------- LED-POST-14
    @Test
    void consumerFixtureMapsIntentAndAccountFactsThenAuditFailureRollsBack() {
        long transfer = newTransfer();
        // Component fixture: Transfer intent facts + Account ledger snapshots -> PostingCommand.
        IntentFacts intent = new IntentFacts(transfer, SOURCE, DESTINATION, new BigDecimal("250.00"),
                "VND", "transfer:" + transfer);
        AccountResults accounts = new AccountResults(new BigDecimal("100.00"), new BigDecimal("600.00"));
        PostingCommand mapped = toCommand(intent, accounts);
        assertThat(mapped.sourceBalanceAfter()).isEqualByComparingTo("100.00");
        assertThat(mapped.destinationBalanceAfter()).isEqualByComparingTo("600.00");

        // Happy path: facts commit with the pair.
        long ok = newTransfer();
        PostingCommand okCommand = toCommand(new IntentFacts(ok, SOURCE, DESTINATION, new BigDecimal("250.00"),
                "VND", "transfer:" + ok), accounts);
        callerTx.execute(s -> posting.postDoubleEntry(okCommand));
        assertThat(new BigDecimal(rows(ok).get(0).get("balance_after").toString())).isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(rows(ok).get(1).get("balance_after").toString())).isEqualByComparingTo("600.00");

        // Audit-phase failure after posting (test-only fixture throw) rolls back the pair.
        assertThatThrownBy(() -> callerTx.execute(s -> {
            posting.postDoubleEntry(mapped);
            throw new IllegalStateException("audit C1 write failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count(transfer)).isZero();
    }

    private record IntentFacts(long transferId, long sourceId, long destinationId, BigDecimal amount,
                               String currency, String correlationId) {
    }

    private record AccountResults(BigDecimal sourceLedgerAfter, BigDecimal destinationLedgerAfter) {
    }

    private static PostingCommand toCommand(IntentFacts intent, AccountResults accounts) {
        return new PostingCommand(intent.transferId(), intent.sourceId(), intent.destinationId(), intent.amount(),
                intent.currency(), accounts.sourceLedgerAfter(), accounts.destinationLedgerAfter(),
                intent.correlationId());
    }

    // ---------------------------------------------------------------- helpers
    private static PostingCommand command(long transfer, BigDecimal amount, BigDecimal sourceAfter,
            BigDecimal destAfter, String correlation) {
        return new PostingCommand(transfer, SOURCE, DESTINATION, amount, "VND", sourceAfter, destAfter, correlation);
    }

    private static long newTransfer() {
        try (Connection owner = connect(MIGRATOR, MIGRATOR_PASSWORD)) {
            try (var statement = owner.createStatement();
                 var result = statement.executeQuery(
                         "INSERT INTO banking.transfer DEFAULT VALUES RETURNING transfer_id")) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot seed fixture transfer", failure);
        }
    }

    private static void seedPair(long transfer, BigDecimal amount) {
        seedEntry(transfer, SOURCE, 1, "DEBIT", amount, BigDecimal.ZERO);
        seedEntry(transfer, DESTINATION, 2, "CREDIT", amount, amount);
    }

    private static void seedEntry(long transfer, long account, int seq, String type, BigDecimal amount,
            BigDecimal balanceAfter) {
        try (Connection owner = connect(MIGRATOR, MIGRATOR_PASSWORD)) {
            try (var statement = owner.prepareStatement(
                    "INSERT INTO banking.ledger_entry (transfer_id, account_id, entry_seq, entry_type, amount, "
                            + "balance_after, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                statement.setLong(1, transfer);
                statement.setLong(2, account);
                statement.setInt(3, seq);
                statement.setString(4, type);
                statement.setBigDecimal(5, amount);
                statement.setBigDecimal(6, balanceAfter);
                statement.setString(7, "seed:" + transfer);
                statement.executeUpdate();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot seed fixture entry", failure);
        }
    }

    private static int count(long transfer) {
        Integer count = appJdbc.queryForObject(
                "SELECT count(*) FROM banking.ledger_entry WHERE transfer_id = " + transfer, Integer.class);
        return count == null ? 0 : count;
    }

    private static List<Map<String, Object>> rows(long transfer) {
        return appJdbc.queryForList("SELECT * FROM banking.ledger_entry WHERE transfer_id = " + transfer
                + " ORDER BY entry_seq, entry_id");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Latch timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static String url() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + DB;
    }

    private static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(url(), user, password);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableJpaRepositories(basePackageClasses = LedgerEntryJpaRepository.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return appDataSource;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setPackagesToScan("com.simulatedbank.banking.ledger.adapter.out.persistence");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto", "validate",
                    "hibernate.default_schema", "banking",
                    "hibernate.jdbc.time_zone", "UTC"));
            return factory;
        }

        @Bean
        @org.springframework.context.annotation.Primary
        JpaTransactionManager transactionManager(EntityManagerFactory emf, DataSource ds) {
            var manager = new JpaTransactionManager(emf);
            manager.setDataSource(ds);
            return manager;
        }

        @Bean
        JdbcTransactionManager jdbcTransactionManager(DataSource ds) {
            return new JdbcTransactionManager(ds);
        }

        @Bean
        LedgerEntryPersistenceAdapter ledgerEntryPersistenceAdapter(LedgerEntryJpaRepository repository) {
            return new LedgerEntryPersistenceAdapter(repository);
        }

        @Bean
        TestLedgerEntryStore testLedgerEntryStore(LedgerEntryPersistenceAdapter adapter) {
            return new TestLedgerEntryStore(adapter);
        }

        @Bean
        LedgerPostingService ledgerPostingService(TestLedgerEntryStore store) {
            return new LedgerPostingService(store);
        }
    }
}