package com.simulatedbank.banking.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.limit.adapter.out.LimitJpaAdapter;
import com.simulatedbank.banking.limit.api.LimitConfiguration;
import com.simulatedbank.banking.limit.api.LimitConfiguration.Status;
import com.simulatedbank.banking.limit.api.LimitRules;
import com.simulatedbank.banking.limit.api.LimitUsage;
import com.simulatedbank.banking.support.CommonTestApplication;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import com.simulatedbank.banking.support.SqlFixture;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@org.springframework.context.annotation.Import(SqlFixture.class)
@ActiveProfiles("test")
@Testcontainers
class LimitRulesIT {
    private static final Instant START = Instant.parse("2026-11-15T00:00:00Z");
    private static final Instant END = START.plusSeconds(3600);
    private static final LocalDate DATE = LocalDate.of(2026, 11, 15);
    private static final java.util.concurrent.atomic.AtomicLong APPROVAL_IDS = new java.util.concurrent.atomic.AtomicLong(41);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("limit/init-roles.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "banking_app");
        registry.add("spring.datasource.password", () -> "test_app");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "banking_migrator");
        registry.add("spring.flyway.password", () -> "test_migrator");
    }

    @Autowired LimitJpaAdapter repository;
    @Autowired LimitRules rules;
    @Autowired TransactionTemplate transactions;
    @Autowired SqlFixture jdbc;
    @Autowired ApplicationContext context;

    @BeforeEach
    void reset() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "banking_migrator", "test_migrator");
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE banking.limit_config,banking.limit_usage RESTART IDENTITY");
        }
    }

    @Test
    void fullApplicationDoesNotDiscoverTheCommonOnlyTestHarness() {
        assertThat(context.getBeansOfType(CommonTestApplication.class)).isEmpty();
        assertThat(context.getBeansOfType(com.simulatedbank.banking.BackendApplication.class)).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "0,1", "1,1", "3599999999999,1", "3600000000000,0", "3600000000001,0"})
    void nanosecondAsOfHonorsDatabaseBoundaries(long nanosFromStart, int expectedMatches) {
        transactions.executeWithoutResult(tx -> repository.insertConfiguration(
                config(11, null, Status.ACTIVE, START, END, "100.00")));
        Instant asOf = START.plusNanos(nanosFromStart);
        var result = rules.resolve(11, 21, asOf);
        assertThat(result.items()).hasSize(expectedMatches);
        assertThat(result.asOf()).isEqualTo(asOf);
    }

    @Test
    void allSnapshotFieldsAndBothUsageScopesRoundTripWithDatabaseIdentitiesAndDefaults() {
        transactions.executeWithoutResult(tx -> {
            var input = config(11, 21L, Status.ACTIVE, START, END, "100.00");
            var saved = repository.insertConfiguration(input);
            assertThat(saved.limitId()).isPositive();
            assertThat(repository.findConfiguration(saved.limitId())).contains(saved);
            assertThat(saved).usingRecursiveComparison().ignoringFields("limitId", "createdAt", "updatedAt")
                    .isEqualTo(input);
            assertThat(saved.createdAt()).isNotNull();
            assertThat(saved.updatedAt()).isNotNull();
            for (Long account : new Long[]{null, 21L}) {
                var usage = repository.insertUsage(new LimitUsage(null, 11, account, DATE, "VND",
                        new BigDecimal("12.34"), 2, START));
                assertThat(usage.usageId()).isPositive();
                assertThat(repository.findUsage(11, account, DATE)).contains(usage);
                assertThat(usage.updatedAt()).isEqualTo(START);
                assertThat(repository.findUsage(12, account, DATE)).isEmpty();
                assertThat(repository.findUsage(11, account, DATE.plusDays(1))).isEmpty();
            }
            assertThat(repository.findConfiguration(Long.MAX_VALUE)).isEmpty();
        });
    }

    @Test
    void callerRollbackUndoesConfigurationAndUsageAndWritesRequireTransaction() {
        var pending = config(11, null, Status.PENDING_APPROVAL, START, null, "100.00");
        var usage = new LimitUsage(null, 11, null, DATE, "VND", BigDecimal.ZERO, 0, null);
        assertThatThrownBy(() -> repository.insertConfiguration(pending)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> repository.insertUsage(usage)).isInstanceOf(IllegalTransactionStateException.class);
        transactions.executeWithoutResult(tx -> {
            repository.insertConfiguration(pending);
            var saved = repository.insertUsage(usage);
            assertThat(saved.updatedAt()).isNotNull();
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_config", Integer.class)).isZero();
        assertThat(repository.findUsage(11, null, DATE)).isEmpty();
    }

    @Test
    void configurationVersionsCoexistAndWritingHistoryNeverResetsUsage() {
        transactions.executeWithoutResult(tx -> {
            var old = repository.insertConfiguration(config(11, null, Status.SUPERSEDED, START, END, "100.00"));
            repository.insertConfiguration(config(11, null, Status.PENDING_APPROVAL, END, null, "200.00"));
            repository.insertConfiguration(config(11, null, Status.REJECTED, END, null, "300.00"));
            var usage = repository.insertUsage(new LimitUsage(null, 11, null, DATE, "VND", new BigDecimal("12.34"), 1, START));
            repository.insertConfiguration(config(11, null, Status.ACTIVE, START, END, "50.00"));
            assertThat(repository.findConfiguration(old.limitId())).contains(old);
            assertThat(repository.findUsage(11, null, DATE)).contains(usage);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_config", Integer.class)).isEqualTo(4);
    }

    @Test
    void selectsOnlyActiveAndNeverFallsBackToHistoricalOrUnrelatedScopes() {
        transactions.executeWithoutResult(tx -> {
            repository.insertConfiguration(config(11, null, Status.ACTIVE, START, END, "100.00"));
            repository.insertConfiguration(config(11, 21L, Status.ACTIVE, START, END, "200.00"));
            repository.insertConfiguration(config(11, 22L, Status.ACTIVE, START, END, "50.00"));
            repository.insertConfiguration(config(12, null, Status.ACTIVE, START, END, "30.00"));
            for (Status status : List.of(Status.PENDING_APPROVAL, Status.REJECTED, Status.SUPERSEDED)) {
                repository.insertConfiguration(config(11, null, status, START.minusSeconds(3600), null, "10.00"));
            }
        });
        var selected = rules.resolve(11, 21, START);
        assertThat(selected.items()).extracting(LimitConfiguration::accountId).containsExactly(null, 21L);
        assertThat(selected.items()).allSatisfy(item -> assertThat(item.status()).isEqualTo(Status.ACTIVE));
        assertThat(rules.resolve(11, 21, START.minusNanos(1000)).items()).isEmpty();
        assertThat(rules.resolve(11, 21, END.minusNanos(1000)).items()).hasSize(2);
        assertThat(rules.resolve(11, 21, END).items()).isEmpty();
        assertThat(rules.resolve(11, 21, END.plusSeconds(1)).items()).isEmpty();
        assertThat(rules.resolve(11, 21, OffsetDateTime.parse("2026-11-15T07:00:00+07:00").toInstant()).items())
                .isEqualTo(selected.items());
    }

    @Test
    void openEndedActiveConfigHasNoArtificialExpiry() {
        transactions.executeWithoutResult(tx -> repository.insertConfiguration(
                config(11, null, Status.ACTIVE, START, null, "100.00")));
        assertThat(rules.resolve(11, 21, START.plusSeconds(86400 * 365)).items()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void allFourScopeCombinationsAndCheckOperationsAreSideEffectFree(int scopes) {
        transactions.executeWithoutResult(tx -> {
            if ((scopes & 1) != 0) repository.insertConfiguration(config(11, null, Status.ACTIVE, START, END, "100.00"));
            if ((scopes & 2) != 0) repository.insertConfiguration(config(11, 21L, Status.ACTIVE, START, END, "200.00"));
            repository.insertUsage(new LimitUsage(null, 11, null, DATE, "VND", new BigDecimal("999.00"), 3, START));
        });
        var configsBefore = jdbc.queryForList("SELECT * FROM banking.limit_config ORDER BY limit_id");
        var usageBefore = jdbc.queryForList("SELECT * FROM banking.limit_usage ORDER BY usage_id");
        assertThat(rules.resolve(11, 21, START).items()).hasSize(Integer.bitCount(scopes));
        assertThat(rules.checkPerTransaction(11, 21, new BigDecimal("100.00"), START))
                .isEqualTo(scopes == 0 ? LimitRules.CheckOutcome.NOT_CONFIGURED : LimitRules.CheckOutcome.PASS);
        if (scopes != 0) {
            assertThatThrownBy(() -> rules.checkPerTransaction(11, 21, new BigDecimal("200.01"), START))
                    .isInstanceOfSatisfying(BankingBusinessException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.LIMIT_EXCEEDED));
        }
        assertThat(jdbc.queryForList("SELECT * FROM banking.limit_config ORDER BY limit_id")).isEqualTo(configsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM banking.limit_usage ORDER BY usage_id")).isEqualTo(usageBefore);
    }

    @Test
    void repositoryRejectsExcessPrecisionRangeAndExistingIdentitiesBeforePersistence() {
        transactions.executeWithoutResult(tx -> {
            assertThatThrownBy(() -> repository.insertConfiguration(config(11, null, Status.ACTIVE, START, END, "1.001")))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.insertUsage(new LimitUsage(null, 11, null, DATE, "VND",
                    new BigDecimal("0.001"), 0, null))).hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.insertUsage(new LimitUsage(null, 11, null, DATE, "VND",
                    new BigDecimal("100000000000000000.00"), 0, null))).hasRootCauseInstanceOf(IllegalArgumentException.class);
            var saved = repository.insertConfiguration(config(11, null, Status.PENDING_APPROVAL, START, null, "100.00"));
            assertThatThrownBy(() -> repository.insertConfiguration(saved)).hasRootCauseInstanceOf(IllegalArgumentException.class);
            var usage = repository.insertUsage(new LimitUsage(null, 11, null, DATE, "VND", BigDecimal.ZERO, 0, null));
            assertThatThrownBy(() -> repository.insertUsage(usage)).hasRootCauseInstanceOf(IllegalArgumentException.class);
            tx.setRollbackOnly();
        });
    }

    @Test
    void proposalWithDatabaseGeneratedTimestampsRemainsAtomicOnApprovalConflict() {
        var proposal = new LimitConfiguration(null, 11, null, new BigDecimal("100.00"), new BigDecimal("1000.00"),
                "VND", START, null, Status.PENDING_APPROVAL, 1, 1, null, 81L, null, null, null);
        transactions.executeWithoutResult(tx -> {
            var saved = repository.insertProposal(proposal).orElseThrow();
            assertThat(saved.createdAt()).isNotNull();
            assertThat(saved.updatedAt()).isNotNull();
            assertThat(saved.effectiveTo()).isNull();
            assertThat(saved.approvedAt()).isNull();
            assertThat(repository.insertProposal(proposal)).isEmpty();
            assertThat(repository.findConfiguration(saved.limitId())).contains(saved);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_config", Integer.class)).isEqualTo(1);
    }

    @Test
    void nullCurrencyOrStatusNeverTurnIntoDatabaseDefaults() {
        var valid = config(11, null, Status.PENDING_APPROVAL, START, null, "100.00");
        var noCurrency = new LimitConfiguration(null, valid.customerId(), null, valid.perTransactionLimit(),
                valid.dailyLimit(), null, valid.effectiveFrom(), null, valid.status(), valid.version(),
                valid.createdBy(), null, null, null, null, null);
        var noStatus = new LimitConfiguration(null, valid.customerId(), null, valid.perTransactionLimit(),
                valid.dailyLimit(), "VND", valid.effectiveFrom(), null, null, valid.version(),
                valid.createdBy(), null, null, null, null, null);
        var usage = new LimitUsage(null, 11, null, DATE, null, BigDecimal.ZERO, 0, null);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> repository.insertConfiguration(noCurrency)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> repository.insertConfiguration(noStatus)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> repository.insertUsage(usage)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_config", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.limit_usage", Integer.class)).isZero();
    }

    private static LimitConfiguration config(long customer, Long account, Status status,
            Instant from, Instant to, String perTransaction) {
        boolean approved = status == Status.ACTIVE || status == Status.SUPERSEDED;
        return new LimitConfiguration(null, customer, account, new BigDecimal(perTransaction), new BigDecimal("1000.00"),
                "VND", from, to, status, status == Status.SUPERSEDED ? 1 : 2, 1, approved ? 2L : null,
                approved ? APPROVAL_IDS.getAndIncrement() : null, null, approved ? START.minusSeconds(10) : null, null);
    }
}
