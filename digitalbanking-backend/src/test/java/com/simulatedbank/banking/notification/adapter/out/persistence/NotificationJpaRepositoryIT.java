package com.simulatedbank.banking.notification.adapter.out.persistence;

import com.simulatedbank.banking.notification.domain.NotificationChannel;
import com.simulatedbank.banking.notification.domain.NotificationItem;
import com.simulatedbank.banking.notification.domain.NotificationStatus;
import com.simulatedbank.banking.notification.domain.NotificationType;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class NotificationJpaRepositoryIT {

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
    private NotificationJpaRepository jpaRepository;

    @Autowired
    private NotificationMapper mapper;

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
            populator.addScript(new ClassPathResource("notification/notification-schema.sql"));
            populator.execute(migratorDs);
            try (var connection = migratorDs.getConnection()) {
                IntegratedProviderReferences.seed(connection);
            }
            schemaInitialized = true;
        }

        transactions.executeWithoutResult(status -> jdbc.execute("DELETE FROM banking.notification"));
    }

    @Test
    @DisplayName("JPA Entity correctly persists and maps JSONB, enums, timestamps, and lease fields on PostgreSQL")
    void persistAndFindNotificationEntity() {
        Instant now = Instant.parse("2026-11-15T12:00:00Z");
        Map<String, Object> payload = Map.of(
                "amount", "1500000.00",
                "currency", "VND",
                "maskedAccount", "0123***789",
                "reference", "TXN987654321"
        );

        NotificationEntity entity = NotificationEntity.builder()
                .customerId(5001L)
                .transferId(6001L)
                .authChallengeId(7001L)
                .notificationType(NotificationType.TRANSFER_RESULT)
                .channel(NotificationChannel.SMS)
                .templateCode("TPL_TXN_SUCCESS")
                .payloadMasked(payload)
                .status(NotificationStatus.PENDING)
                .retryCount(0)
                .nextRetryAt(now.plusSeconds(30))
                .sentAt(null)
                .failureCode(null)
                .correlationId("CORR-NOTI-001")
                .createdAt(now)
                .updatedAt(now)
                .claimToken("TOKEN-INITIAL")
                .lockedUntil(now.plusSeconds(30))
                .claimVersion(1L)
                .build();

        NotificationEntity saved = jpaRepository.save(entity);
        assertThat(saved.getNotificationId()).isNotNull();

        Optional<NotificationEntity> found = jpaRepository.findById(saved.getNotificationId());
        assertThat(found).isPresent();

        NotificationEntity actual = found.get();
        assertThat(actual.getCustomerId()).isEqualTo(5001L);
        assertThat(actual.getTransferId()).isEqualTo(6001L);
        assertThat(actual.getAuthChallengeId()).isEqualTo(7001L);
        assertThat(actual.getNotificationType()).isEqualTo(NotificationType.TRANSFER_RESULT);
        assertThat(actual.getChannel()).isEqualTo(NotificationChannel.SMS);
        assertThat(actual.getTemplateCode()).isEqualTo("TPL_TXN_SUCCESS");
        assertThat(actual.getPayloadMasked()).containsEntry("amount", "1500000.00");
        assertThat(actual.getPayloadMasked()).containsEntry("currency", "VND");
        assertThat(actual.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(actual.getRetryCount()).isEqualTo(0);
        assertThat(actual.getCorrelationId()).isEqualTo("CORR-NOTI-001");
        assertThat(actual.getClaimToken()).isEqualTo("TOKEN-INITIAL");
        assertThat(actual.getClaimVersion()).isEqualTo(1L);

        // Verify MapStruct domain conversion
        NotificationItem domainItem = mapper.toDomain(actual);
        assertThat(domainItem.notificationId()).isEqualTo(saved.getNotificationId());
        assertThat(domainItem.payloadMasked()).containsEntry("amount", "1500000.00");
        assertThat(domainItem.channel()).isEqualTo(NotificationChannel.SMS);
    }

    @Test
    @DisplayName("JPA query methods findByCustomerId, findByTransferId, and countByStatusIn operate correctly")
    void testQueryMethods() {
        Instant now = Instant.now();
        NotificationEntity e1 = NotificationEntity.builder()
                .customerId(5002L)
                .transferId(6002L)
                .notificationType(NotificationType.TRANSFER_RESULT)
                .channel(NotificationChannel.EMAIL)
                .templateCode("TPL_EMAIL")
                .payloadMasked(Map.of("amount", "200000.00"))
                .status(NotificationStatus.PENDING)
                .correlationId("CORR-01")
                .createdAt(now.minusSeconds(60))
                .updatedAt(now.minusSeconds(60))
                .claimVersion(0L)
                .build();

        NotificationEntity e2 = NotificationEntity.builder()
                .customerId(5002L)
                .transferId(6003L)
                .notificationType(NotificationType.OTP_DELIVERY)
                .channel(NotificationChannel.SMS)
                .templateCode("TPL_OTP")
                .payloadMasked(Map.of("purpose", "TRANSFER"))
                .status(NotificationStatus.RETRYING)
                .correlationId("CORR-02")
                .createdAt(now)
                .updatedAt(now)
                .claimVersion(0L)
                .build();

        jpaRepository.saveAll(List.of(e1, e2));

        List<NotificationEntity> customerNotifications = jpaRepository.findByCustomerIdOrderByCreatedAtDesc(5002L);
        assertThat(customerNotifications).hasSize(2);
        assertThat(customerNotifications.get(0).getCorrelationId()).isEqualTo("CORR-02");
        assertThat(customerNotifications.get(1).getCorrelationId()).isEqualTo("CORR-01");

        List<NotificationEntity> transferNotifications = jpaRepository.findByTransferId(6002L);
        assertThat(transferNotifications).hasSize(1);
        assertThat(transferNotifications.get(0).getTemplateCode()).isEqualTo("TPL_EMAIL");

        long activeCount = jpaRepository.countByStatusIn(List.of(NotificationStatus.PENDING, NotificationStatus.RETRYING));
        assertThat(activeCount).isEqualTo(2L);
    }

    @Test
    @DisplayName("JPA entity persists nullable fields and respects transaction rollback")
    void testNullableFieldsAndTransactionRollback() {
        Instant now = Instant.now();

        // 1. Persist entity with nullable fields set to null
        NotificationEntity nullableEntity = NotificationEntity.builder()
                .customerId(5003L)
                .transferId(null)
                .authChallengeId(null)
                .notificationType(NotificationType.SECURITY_ALERT)
                .channel(NotificationChannel.IN_APP)
                .templateCode("TPL_SEC_ALERT")
                .payloadMasked(Map.of("alertType", "NEW_DEVICE_LOGIN"))
                .status(NotificationStatus.PENDING)
                .retryCount(0)
                .nextRetryAt(now)
                .sentAt(null)
                .failureCode(null)
                .correlationId("CORR-NULLABLE-01")
                .createdAt(now)
                .updatedAt(now)
                .claimToken(null)
                .lockedUntil(null)
                .claimVersion(0L)
                .build();

        NotificationEntity saved = jpaRepository.save(nullableEntity);
        assertThat(saved.getNotificationId()).isNotNull();
        assertThat(saved.getTransferId()).isNull();
        assertThat(saved.getAuthChallengeId()).isNull();
        assertThat(saved.getSentAt()).isNull();
        assertThat(saved.getFailureCode()).isNull();
        assertThat(saved.getClaimToken()).isNull();
        assertThat(saved.getLockedUntil()).isNull();

        // 2. Transaction rollback discards JPA entity insert
        try {
            transactions.executeWithoutResult(status -> {
                NotificationEntity rollbackEntity = NotificationEntity.builder()
                        .customerId(5004L)
                        .notificationType(NotificationType.TRANSFER_RESULT)
                        .channel(NotificationChannel.SMS)
                        .templateCode("TPL_ROLLBACK")
                        .payloadMasked(Map.of("amount", "100"))
                        .status(NotificationStatus.PENDING)
                        .retryCount(0)
                        .nextRetryAt(now)
                        .correlationId("CORR-ROLLBACK-01")
                        .createdAt(now)
                        .updatedAt(now)
                        .claimVersion(0L)
                        .build();
                jpaRepository.save(rollbackEntity);
                throw new RuntimeException("Simulated transaction rollback");
            });
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).isEqualTo("Simulated transaction rollback");
        }

        List<NotificationEntity> rolledBack = jpaRepository.findByCustomerIdOrderByCreatedAtDesc(5004L);
        assertThat(rolledBack).isEmpty();
    }
}
