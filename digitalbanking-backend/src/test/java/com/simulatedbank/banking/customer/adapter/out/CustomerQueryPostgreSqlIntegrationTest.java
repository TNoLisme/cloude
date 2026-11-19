package com.simulatedbank.banking.customer.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import com.simulatedbank.banking.customer.application.CustomerPiiService;
import com.simulatedbank.banking.customer.application.OwnerCustomerPiiView;
import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-CUS-IMP-04: public {@link CustomerQuery} contract against the real PostgreSQL schema
 * (Flyway V1–V5, real JPA/Hibernate), proving persisted {@code user_id -> customer_id} mapping,
 * status handling and read-only semantics without touching Customer persistence internals.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class CustomerQueryPostgreSqlIntegrationTest {

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
    private CustomerQuery customerQuery;

    @Autowired
    private CustomerPiiService piiService;

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
    void resolvesPersistedUserIdMappingThroughPublicApiBean() {
        long userId = seedUser("query-active");
        OwnerCustomerPiiView created = piiService.create(
                userId, "Nguyễn An", "+84900000123", "n.synthetic@example.com", CustomerStatus.ACTIVE);

        Optional<CustomerQuery.CustomerIdentity> resolved = customerQuery.findByUserId(userId);

        assertThat(resolved).get().satisfies(snapshot -> {
            assertThat(snapshot.customerId()).isEqualTo(created.getCustomerId());
            assertThat(snapshot.userId()).isEqualTo(userId);
            assertThat(snapshot.status()).isEqualTo(CustomerQuery.Status.ACTIVE);
        });
        assertThat(resolved.orElseThrow().toString()).doesNotContain("Nguyễn An", "84900000123", "example.com");
    }

    @Test
    void missingMappingReturnsEmptyForUnknownUserAndUserWithoutCustomerRow() {
        assertThat(customerQuery.findByUserId(Long.MAX_VALUE)).isEmpty();

        long userWithoutCustomer = seedUser("no-customer-row");
        assertThat(customerQuery.findByUserId(userWithoutCustomer)).isEmpty();
        assertThat(countCustomers()).isZero();
    }

    @Test
    void inactiveCustomerIsReturnedWithItsStatusInsteadOfBeingHidden() {
        long userId = seedUser("query-inactive");
        OwnerCustomerPiiView created = piiService.create(
                userId, "Trần Bình", null, null, CustomerStatus.INACTIVE);

        Optional<CustomerQuery.CustomerIdentity> resolved = customerQuery.findByUserId(userId);

        assertThat(resolved).get().satisfies(snapshot -> {
            assertThat(snapshot.customerId()).isEqualTo(created.getCustomerId());
            assertThat(snapshot.status()).isEqualTo(CustomerQuery.Status.INACTIVE);
        });
        assertThat(jdbc.queryForObject(
                "SELECT status FROM banking.customer WHERE customer_id = ?",
                String.class,
                created.getCustomerId())).isEqualTo("INACTIVE");
    }

    @Test
    void readOnlyLookupNeverWritesOrMutatesPersistedState() {
        long userId = seedUser("query-readonly");
        OwnerCustomerPiiView created = piiService.create(
                userId, "Lê Cường", "+84900000456", "l.synthetic@example.com", CustomerStatus.ACTIVE);
        Instant updatedAtBefore = jdbc.queryForObject(
                "SELECT updated_at FROM banking.customer WHERE customer_id = ?",
                Instant.class,
                created.getCustomerId());
        long customerCountBefore = countCustomers();

        for (int i = 0; i < 3; i++) {
            assertThat(customerQuery.findByUserId(userId))
                    .get()
                    .extracting(CustomerQuery.CustomerIdentity::customerId)
                    .isEqualTo(created.getCustomerId());
        }

        Instant updatedAtAfter = jdbc.queryForObject(
                "SELECT updated_at FROM banking.customer WHERE customer_id = ?",
                Instant.class,
                created.getCustomerId());
        assertThat(updatedAtAfter).isEqualTo(updatedAtBefore);
        assertThat(countCustomers()).isEqualTo(customerCountBefore);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM banking.customer WHERE customer_id = ?",
                String.class,
                created.getCustomerId())).isEqualTo("ACTIVE");
    }

    @Test
    void rejectsInvalidUserIdWithValidationError() {
        assertThatThrownBy(() -> customerQuery.findByUserId(0))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> customerQuery.findByUserId(-1))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void resolvesCustomerIdToTheSamePersistedMinimalSnapshot() {
        long userId = seedUser("query-by-customer-id");
        OwnerCustomerPiiView created = piiService.create(
                userId, "Vũ Dũng", "+84900000789", "v.synthetic@example.com", CustomerStatus.ACTIVE);
        Instant updatedAtBefore = jdbc.queryForObject(
                "SELECT updated_at FROM banking.customer WHERE customer_id = ?",
                Instant.class, created.getCustomerId());

        Optional<CustomerQuery.CustomerIdentity> resolved =
                customerQuery.findByCustomerId(created.getCustomerId());

        assertThat(resolved).contains(new CustomerQuery.CustomerIdentity(
                created.getCustomerId(), userId, CustomerQuery.Status.ACTIVE));
        assertThat(customerQuery.findByCustomerId(created.getCustomerId() + 1000)).isEmpty();
        assertThatThrownBy(() -> customerQuery.findByCustomerId(0))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        // Read-only: no customer row was created or touched by the exact-ID lookup.
        assertThat(countCustomers()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT updated_at FROM banking.customer WHERE customer_id = ?",
                Instant.class, created.getCustomerId())).isEqualTo(updatedAtBefore);
    }

    @Test
    void neverLeaksAnotherCustomersMapping() {
        long userA = seedUser("query-owner-a");
        long userB = seedUser("query-owner-b");
        OwnerCustomerPiiView createdA = piiService.create(
                userA, "Phạm An", null, null, CustomerStatus.ACTIVE);
        OwnerCustomerPiiView createdB = piiService.create(
                userB, "Hoàng Bảo", null, null, CustomerStatus.ACTIVE);

        assertThat(customerQuery.findByUserId(userA))
                .get()
                .extracting(CustomerQuery.CustomerIdentity::customerId)
                .isEqualTo(createdA.getCustomerId())
                .isNotEqualTo(createdB.getCustomerId());
        assertThat(customerQuery.findByUserId(userB))
                .get()
                .extracting(CustomerQuery.CustomerIdentity::customerId)
                .isEqualTo(createdB.getCustomerId());
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

    private long countCustomers() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM banking.customer", Long.class);
        return count == null ? 0 : count;
    }
}
