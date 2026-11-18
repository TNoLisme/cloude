package com.simulatedbank.banking.fraud.adapter.out.persistence;

import com.simulatedbank.banking.fraud.adapter.out.FraudJdbcRepository;
import com.simulatedbank.banking.fraud.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.simulatedbank.banking.support.IntegratedProviderReferences;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class FraudJpaRepositoryIT {

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

    @Autowired
    private FraudRuleConfigJpaRepository ruleConfigJpaRepository;

    @Autowired
    private FraudRuleConfigMapper ruleConfigMapper;

    @Autowired
    private FraudFlagJpaRepository flagJpaRepository;

    @Autowired
    private FraudFlagMapper flagMapper;

    @Autowired
    private FraudJdbcRepository fraudJdbcRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() throws Exception {
        if (!schemaInitialized) {
            DriverManagerDataSource migratorDs = new DriverManagerDataSource();
            migratorDs.setUrl(postgres.getJdbcUrl());
            migratorDs.setUsername("banking_migrator");
            migratorDs.setPassword("test_migrator");
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("fraud/fraud-schema.sql"));
            populator.execute(migratorDs);
            try (var connection = migratorDs.getConnection()) {
                IntegratedProviderReferences.seed(connection);
            }
            schemaInitialized = true;
        }

        transactions.executeWithoutResult(status -> {
            jdbc.execute("DELETE FROM banking.fraud_flag");
            jdbc.execute("DELETE FROM banking.fraud_rule_config");
        });
    }

    @Test
    @DisplayName("FraudRuleConfig JPA entity correctly handles composite primary key and time-bound queries")
    void testRuleConfigCompositeKeyAndActiveQuery() {
        Instant now = Instant.parse("2026-11-15T12:00:00Z");

        FraudRuleConfigEntity v1 = FraudRuleConfigEntity.builder()
                .ruleCode("LARGE_VALUE")
                .version(1)
                .ruleName("Large Transfer Rule v1")
                .ruleType(RuleType.LARGE_VALUE)
                .thresholdAmount(new BigDecimal("10000000.00"))
                .thresholdCount(null)
                .windowSeconds(null)
                .severity(Severity.HIGH)
                .enabled(true)
                .effectiveFrom(now.minusSeconds(3600))
                .effectiveTo(now.minusSeconds(60)) // Expired
                .createdBy(101L)
                .approvedBy(102L)
                .createdAt(now.minusSeconds(3600))
                .updatedAt(now.minusSeconds(3600))
                .build();

        FraudRuleConfigEntity v2 = FraudRuleConfigEntity.builder()
                .ruleCode("LARGE_VALUE")
                .version(2)
                .ruleName("Large Transfer Rule v2")
                .ruleType(RuleType.LARGE_VALUE)
                .thresholdAmount(new BigDecimal("20000000.00"))
                .thresholdCount(null)
                .windowSeconds(null)
                .severity(Severity.CRITICAL)
                .enabled(true)
                .effectiveFrom(now.minusSeconds(60))
                .effectiveTo(null) // Active
                .createdBy(101L)
                .approvedBy(102L)
                .createdAt(now.minusSeconds(60))
                .updatedAt(now.minusSeconds(60))
                .build();

        ruleConfigJpaRepository.saveAll(List.of(v1, v2));

        // Test composite key lookup
        Optional<FraudRuleConfigEntity> foundV1 = ruleConfigJpaRepository.findById(new FraudRuleConfigId("LARGE_VALUE", 1));
        assertThat(foundV1).isPresent();
        assertThat(foundV1.get().getThresholdAmount()).isEqualByComparingTo("10000000.00");

        Optional<FraudRuleConfigEntity> foundV2 = ruleConfigJpaRepository.findById(new FraudRuleConfigId("LARGE_VALUE", 2));
        assertThat(foundV2).isPresent();
        assertThat(foundV2.get().getSeverity()).isEqualTo(Severity.CRITICAL);

        // Test active rule query at 'now'
        List<FraudRuleConfigEntity> activeRules = ruleConfigJpaRepository.findActiveRules(now);
        assertThat(activeRules).hasSize(1);
        assertThat(activeRules.get(0).getVersion()).isEqualTo(2);

        // Verify MapStruct domain conversion
        FraudRuleConfig domain = ruleConfigMapper.toDomain(activeRules.get(0));
        assertThat(domain.ruleCode()).isEqualTo("LARGE_VALUE");
        assertThat(domain.version()).isEqualTo(2);
        assertThat(domain.thresholdAmount()).isEqualByComparingTo("20000000.00");
    }

    @Test
    @DisplayName("FraudFlag JPA entity correctly maps JSONB matched_value, enums, nullable review fields")
    void testFraudFlagEntityLifecycleAndQuery() {
        Instant now = Instant.parse("2026-11-15T12:00:00Z");

        // Seed rule config to satisfy foreign key fk_fraud_flag_rule
        FraudRuleConfigEntity rule = FraudRuleConfigEntity.builder()
                .ruleCode("HIGH_FREQUENCY")
                .version(1)
                .ruleName("High frequency transfer rule")
                .ruleType(RuleType.HIGH_FREQUENCY)
                .thresholdAmount(null)
                .thresholdCount(5)
                .windowSeconds(600)
                .severity(Severity.MEDIUM)
                .enabled(true)
                .effectiveFrom(now.minusSeconds(3600))
                .effectiveTo(null)
                .createdBy(101L)
                .approvedBy(102L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        ruleConfigJpaRepository.save(rule);

        Map<String, Object> matchedValue = Map.of(
                "count", 6,
                "window", "600s",
                "threshold", 5
        );

        FraudFlagEntity flag = FraudFlagEntity.builder()
                .transferId(9001L)
                .ruleCode("HIGH_FREQUENCY")
                .ruleVersion(1)
                .severity(Severity.MEDIUM)
                .status(FlagStatus.OPEN)
                .matchedValue(matchedValue)
                .reviewedBy(null)
                .reviewedAt(null)
                .decision(null)
                .notes(null)
                .correlationId("CORR-FRAUD-001")
                .createdAt(now)
                .build();

        FraudFlagEntity saved = flagJpaRepository.save(flag);
        assertThat(saved.getFlagId()).isNotNull();

        // Find by ID and check initial fields
        Optional<FraudFlagEntity> found = flagJpaRepository.findById(saved.getFlagId());
        assertThat(found).isPresent();
        FraudFlagEntity initialFlag = found.get();
        assertThat(initialFlag.getStatus()).isEqualTo(FlagStatus.OPEN);
        assertThat(initialFlag.getMatchedValue()).containsEntry("count", 6);
        assertThat(initialFlag.getReviewedBy()).isNull();
        assertThat(initialFlag.getDecision()).isNull();

        // Perform review update
        initialFlag.setStatus(FlagStatus.ALLOWED);
        initialFlag.setDecision(ReviewDecision.ALLOW);
        initialFlag.setReviewedBy(888L);
        initialFlag.setReviewedAt(now.plusSeconds(120));
        initialFlag.setNotes("Legitimate transaction verified with user");
        flagJpaRepository.save(initialFlag);

        // Verify updated state
        FraudFlagEntity reviewed = flagJpaRepository.findById(saved.getFlagId()).orElseThrow();
        assertThat(reviewed.getStatus()).isEqualTo(FlagStatus.ALLOWED);
        assertThat(reviewed.getDecision()).isEqualTo(ReviewDecision.ALLOW);
        assertThat(reviewed.getReviewedBy()).isEqualTo(888L);
        assertThat(reviewed.getNotes()).isEqualTo("Legitimate transaction verified with user");

        // Verify findByTransferId
        List<FraudFlagEntity> flagsByTransfer = flagJpaRepository.findByTransferId(9001L);
        assertThat(flagsByTransfer).hasSize(1);
        assertThat(flagsByTransfer.get(0).getRuleCode()).isEqualTo("HIGH_FREQUENCY");

        // Verify MapStruct domain conversion
        FraudFlagItem domainItem = flagMapper.toDomain(reviewed);
        assertThat(domainItem.flagId()).isEqualTo(saved.getFlagId());
        assertThat(domainItem.status()).isEqualTo(FlagStatus.ALLOWED);
        assertThat(domainItem.decision()).isEqualTo(ReviewDecision.ALLOW);
    }

    @Test
    @DisplayName("Fraud entities persist nullable fields and respect transaction rollback")
    void testNullableFieldsAndTransactionRollback() {
        Instant now = Instant.parse("2026-11-15T12:00:00Z");

        // 1. Seed rule with nullable fields (thresholdCount, windowSeconds, effectiveTo are null)
        FraudRuleConfigEntity rule = FraudRuleConfigEntity.builder()
                .ruleCode("LARGE_VALUE_NULLABLE")
                .version(1)
                .ruleName("Large Transfer Nullable Test")
                .ruleType(RuleType.LARGE_VALUE)
                .thresholdAmount(new BigDecimal("50000000.00"))
                .thresholdCount(null)
                .windowSeconds(null)
                .severity(Severity.CRITICAL)
                .enabled(true)
                .effectiveFrom(now)
                .effectiveTo(null)
                .createdBy(101L)
                .approvedBy(null)
                .createdAt(now)
                .updatedAt(now)
                .build();
        ruleConfigJpaRepository.save(rule);

        // 2. Persist flag with nullable fields (reviewedBy, reviewedAt, decision, notes are null)
        FraudFlagEntity flag = FraudFlagEntity.builder()
                .transferId(9999L)
                .ruleCode("LARGE_VALUE_NULLABLE")
                .ruleVersion(1)
                .severity(Severity.CRITICAL)
                .status(FlagStatus.OPEN)
                .matchedValue(Map.of("amount", "60000000.00", "currency", "VND"))
                .reviewedBy(null)
                .reviewedAt(null)
                .decision(null)
                .notes(null)
                .correlationId("CORR-NULL-FLAG")
                .createdAt(now)
                .build();

        FraudFlagEntity savedFlag = flagJpaRepository.save(flag);
        assertThat(savedFlag.getFlagId()).isNotNull();
        assertThat(savedFlag.getReviewedBy()).isNull();
        assertThat(savedFlag.getReviewedAt()).isNull();
        assertThat(savedFlag.getDecision()).isNull();
        assertThat(savedFlag.getNotes()).isNull();

        // 3. Transaction rollback discards JPA entity insert
        try {
            transactions.executeWithoutResult(status -> {
                FraudFlagEntity rollbackFlag = FraudFlagEntity.builder()
                        .transferId(8888L)
                        .ruleCode("LARGE_VALUE_NULLABLE")
                        .ruleVersion(1)
                        .severity(Severity.CRITICAL)
                        .status(FlagStatus.OPEN)
                        .matchedValue(Map.of("amount", "70000000.00"))
                        .correlationId("CORR-ROLLBACK")
                        .createdAt(now)
                        .build();
                flagJpaRepository.save(rollbackFlag);
                throw new RuntimeException("Simulated transaction rollback");
            });
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).isEqualTo("Simulated transaction rollback");
        }

        List<FraudFlagEntity> rolledBackFlags = flagJpaRepository.findByTransferId(8888L);
        assertThat(rolledBackFlags).isEmpty();
    }

    @Test
    @DisplayName("findActiveRules preserves ordering: ruleCode ASC, version DESC")
    void findActiveRulesPreservesOrderByRuleCodeAscVersionDesc() {
        Instant now = Instant.parse("2026-11-15T12:00:00Z");

        // Insert rules in deliberate non-sorted order
        FraudRuleConfigEntity b1 = FraudRuleConfigEntity.builder()
                .ruleCode("RULE_B").version(1).ruleName("B1").ruleType(RuleType.LARGE_VALUE)
                .thresholdAmount(new BigDecimal("1000000.00"))
                .severity(Severity.LOW).enabled(true).effectiveFrom(now.minusSeconds(100)).effectiveTo(null)
                .createdBy(1L).createdAt(now).updatedAt(now).build();

        FraudRuleConfigEntity a1 = FraudRuleConfigEntity.builder()
                .ruleCode("RULE_A").version(1).ruleName("A1").ruleType(RuleType.LARGE_VALUE)
                .thresholdAmount(new BigDecimal("1000000.00"))
                .severity(Severity.LOW).enabled(true).effectiveFrom(now.minusSeconds(100)).effectiveTo(null)
                .createdBy(1L).createdAt(now).updatedAt(now).build();

        FraudRuleConfigEntity b2 = FraudRuleConfigEntity.builder()
                .ruleCode("RULE_B").version(2).ruleName("B2").ruleType(RuleType.LARGE_VALUE)
                .thresholdAmount(new BigDecimal("2000000.00"))
                .severity(Severity.HIGH).enabled(true).effectiveFrom(now.minusSeconds(50)).effectiveTo(null)
                .createdBy(1L).createdAt(now).updatedAt(now).build();

        FraudRuleConfigEntity a2 = FraudRuleConfigEntity.builder()
                .ruleCode("RULE_A").version(2).ruleName("A2").ruleType(RuleType.LARGE_VALUE)
                .thresholdAmount(new BigDecimal("2000000.00"))
                .severity(Severity.HIGH).enabled(true).effectiveFrom(now.minusSeconds(50)).effectiveTo(null)
                .createdBy(1L).createdAt(now).updatedAt(now).build();

        FraudRuleConfigEntity m1 = FraudRuleConfigEntity.builder()
                .ruleCode("RULE_M").version(1).ruleName("M1").ruleType(RuleType.LARGE_VALUE)
                .thresholdAmount(new BigDecimal("1500000.00"))
                .severity(Severity.MEDIUM).enabled(true).effectiveFrom(now.minusSeconds(50)).effectiveTo(null)
                .createdBy(1L).createdAt(now).updatedAt(now).build();

        ruleConfigJpaRepository.saveAll(List.of(b1, a1, b2, a2, m1));

        List<FraudRuleConfigEntity> active = ruleConfigJpaRepository.findActiveRules(now);
        assertThat(active).hasSize(5);

        List<String> orderedKeys = active.stream()
                .map(r -> r.getRuleCode() + ":" + r.getVersion())
                .toList();

        assertThat(orderedKeys).containsExactly(
                "RULE_A:2",
                "RULE_A:1",
                "RULE_B:2",
                "RULE_B:1",
                "RULE_M:1"
        );
    }

    @Test
    @DisplayName("insertRuleConfig preserves INSERT semantics: duplicate (ruleCode, version) throws DataIntegrityViolationException and leaves initial data unchanged")
    void insertRuleConfigPreservesInsertSemanticsRejectingDuplicateKey() {
        Instant now = Instant.parse("2026-11-15T12:00:00Z");

        FraudRuleConfig initial = new FraudRuleConfig(
                "DUP_RULE", 1, "Initial Rule", RuleType.LARGE_VALUE,
                new BigDecimal("10000000.00"), null, null, Severity.HIGH, true,
                now, null, 101L, 102L, now, now
        );

        transactions.executeWithoutResult(status -> fraudJdbcRepository.insertRuleConfig(initial));

        FraudRuleConfig duplicate = new FraudRuleConfig(
                "DUP_RULE", 1, "Overwriting Rule", RuleType.LARGE_VALUE,
                new BigDecimal("99999999.00"), null, null, Severity.CRITICAL, false,
                now, null, 999L, 999L, now, now
        );

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> fraudJdbcRepository.insertRuleConfig(duplicate)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Verify initial row in database is completely untouched
        FraudRuleConfigEntity current = ruleConfigJpaRepository.findById(new FraudRuleConfigId("DUP_RULE", 1)).orElseThrow();
        assertThat(current.getRuleName()).isEqualTo("Initial Rule");
        assertThat(current.getThresholdAmount()).isEqualByComparingTo("10000000.00");
        assertThat(current.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(current.isEnabled()).isTrue();
    }
}
