package com.simulatedbank.banking.customer.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class CustomerJpaPostgreSqlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("digital_banking")
            .withUsername("postgres")
            .withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }

    @Autowired
    private CustomerJpaRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clearDatabase() {
        transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM banking.customer");
            jdbc.update("DELETE FROM banking.app_user WHERE username <> '__fraud_rule_bootstrap__'");
        });
    }

    @Test
    void persistsAndRetrievesAllApprovedMappings() {
        long userId = seedUser("mapping-customer");
        byte[] fullName = {1, 2, 3, 4};
        byte[] phone = {5, 6, 7};
        byte[] email = {8, 9, 10};

        CustomerEntity saved = repository.saveAndFlush(new CustomerEntity(
                userId, fullName, phone, email, CustomerStatus.ACTIVE));

        CustomerEntity reloaded = repository.findById(saved.getCustomerId()).orElseThrow();
        assertThat(reloaded.getCustomerId()).isPositive();
        assertThat(reloaded.getUserId()).isEqualTo(userId);
        assertThat(reloaded.getFullNameEncrypted()).containsExactly(fullName);
        assertThat(reloaded.getPhoneEncrypted()).containsExactly(phone);
        assertThat(reloaded.getEmailEncrypted()).containsExactly(email);
        assertThat(reloaded.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull().isAfterOrEqualTo(reloaded.getCreatedAt());
        assertThat(reloaded.toString()).isEqualTo("CustomerEntity[redacted]");
        assertThat(repository.findByUserId(userId))
                .get()
                .extracting(CustomerEntity::getCustomerId)
                .isEqualTo(reloaded.getCustomerId());
        assertThat(repository.existsByUserId(userId)).isTrue();
        assertThat(repository.existsByCustomerIdAndStatus(
                reloaded.getCustomerId(), CustomerStatus.ACTIVE)).isTrue();
    }

    @Test
    void permitsNullableContactCiphertextAndRejectsMissingRequiredName() {
        long nullableUserId = seedUser("nullable-contact");
        CustomerEntity saved = repository.saveAndFlush(new CustomerEntity(
                nullableUserId, new byte[]{11}, null, null, CustomerStatus.ACTIVE));

        CustomerEntity reloaded = repository.findById(saved.getCustomerId()).orElseThrow();
        assertThat(reloaded.getPhoneEncrypted()).isNull();
        assertThat(reloaded.getEmailEncrypted()).isNull();

        long invalidUserId = seedUser("missing-name");
        assertThatThrownBy(() -> repository.saveAndFlush(new CustomerEntity(
                invalidUserId, null, null, null, CustomerStatus.ACTIVE)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.findByUserId(invalidUserId)).isEmpty();
    }

    @Test
    void returnsEmptyForMissingCustomerByIdAndUserId() {
        assertThat(repository.findById(Long.MAX_VALUE)).isEmpty();
        assertThat(repository.findByUserId(Long.MAX_VALUE)).isEmpty();
        assertThat(repository.existsByUserId(Long.MAX_VALUE)).isFalse();
    }

    @Test
    void preservesInactiveStatusWithoutTreatingCustomerAsMissingOrActive() {
        long userId = seedUser("inactive-customer");
        CustomerEntity saved = repository.saveAndFlush(new CustomerEntity(
                userId, new byte[]{21, 22}, null, null, CustomerStatus.INACTIVE));

        CustomerEntity reloaded = repository.findByUserId(userId).orElseThrow();
        assertThat(reloaded.getCustomerId()).isEqualTo(saved.getCustomerId());
        assertThat(reloaded.getStatus()).isEqualTo(CustomerStatus.INACTIVE);
        assertThat(repository.existsByCustomerIdAndStatus(
                reloaded.getCustomerId(), CustomerStatus.INACTIVE)).isTrue();
        assertThat(repository.existsByCustomerIdAndStatus(
                reloaded.getCustomerId(), CustomerStatus.ACTIVE)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM banking.customer WHERE customer_id = ?",
                String.class,
                reloaded.getCustomerId())).isEqualTo("INACTIVE");
    }

    @Test
    void duplicateUserFailsOnFlushAndRollsBackSecondInsert() {
        long userId = seedUser("duplicate-user");
        CustomerEntity first = repository.saveAndFlush(new CustomerEntity(
                userId, new byte[]{31}, null, null, CustomerStatus.ACTIVE));

        assertThatThrownBy(() -> repository.saveAndFlush(new CustomerEntity(
                userId, new byte[]{32}, null, null, CustomerStatus.INACTIVE)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByUserId(userId))
                .get()
                .extracting(CustomerEntity::getCustomerId)
                .isEqualTo(first.getCustomerId());
    }

    @Test
    void databaseAllowsOnlyOneOfTwoConcurrentCustomersForSameUser() throws Exception {
        long userId = seedUser("concurrent-duplicate");
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> first = duplicateInsertAttempt(start, userId, (byte) 41);
            Callable<Boolean> second = duplicateInsertAttempt(start, userId, (byte) 42);
            List<Future<Boolean>> results = executor.invokeAll(
                    List.of(first, second), Duration.ofSeconds(20).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

            assertThat(results).allMatch(result -> !result.isCancelled());
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(repository.count()).isEqualTo(1);
            assertThat(repository.findByUserId(userId)).isPresent();
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<Boolean> duplicateInsertAttempt(CyclicBarrier start, long userId, byte marker) {
        return () -> {
            start.await();
            try {
                repository.saveAndFlush(new CustomerEntity(
                        userId, new byte[]{marker}, null, null, CustomerStatus.ACTIVE));
                return true;
            } catch (DataIntegrityViolationException duplicate) {
                return false;
            }
        };
    }

    private long seedUser(String username) {
        return transaction.execute(ignored -> jdbc.queryForObject(
                """
                INSERT INTO banking.app_user (username, password_hash, user_type, status)
                VALUES (?, 'synthetic-password-hash', 'CUSTOMER', 'ACTIVE')
                RETURNING user_id
                """,
                Long.class,
                username));
    }
}
