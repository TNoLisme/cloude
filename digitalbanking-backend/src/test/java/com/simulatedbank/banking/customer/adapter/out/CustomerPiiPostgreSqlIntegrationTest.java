package com.simulatedbank.banking.customer.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.crypto.PiiCryptoService;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.application.CustomerPiiService;
import com.simulatedbank.banking.customer.application.OwnerCustomerPiiView;
import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class CustomerPiiPostgreSqlIntegrationTest {

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
    private CustomerPiiService service;

    @Autowired
    private PiiCryptoService crypto;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clearDatabase() {
        transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM banking.customer");
            jdbc.update("DELETE FROM banking.app_user");
        });
    }

    @Test
    @DisplayName("AC-023 database: protected Customer columns contain ciphertext, never plaintext")
    void creationPersistsOnlyAesGcmCiphertextAndSerializesOwnerSafeFields() throws Exception {
        long userId = seedUser("encrypted-create");
        OwnerCustomerPiiView view = service.create(
                userId, "Nguyễn An", "+84900000123", "n.synthetic@example.com", CustomerStatus.ACTIVE);
        Map<String, byte[]> stored = encryptedColumns(view.getCustomerId());

        assertThat(stored.get("full_name_enc")).isNotEqualTo("Nguyễn An".getBytes(StandardCharsets.UTF_8));
        assertThat(stored.get("phone_enc")).isNotEqualTo("+84900000123".getBytes(StandardCharsets.UTF_8));
        assertThat(stored.get("email_enc")).isNotEqualTo("n.synthetic@example.com".getBytes(StandardCharsets.UTF_8));
        assertThat(containsSubsequence(
                stored.get("full_name_enc"), "Nguyễn An".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(containsSubsequence(
                stored.get("phone_enc"), "+84900000123".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(containsSubsequence(
                stored.get("email_enc"), "n.synthetic@example.com".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(crypto.decrypt(stored.get("full_name_enc"))).isEqualTo("Nguyễn An");
        assertThat(crypto.decrypt(stored.get("phone_enc"))).isEqualTo("+84900000123");
        assertThat(crypto.decrypt(stored.get("email_enc"))).isEqualTo("n.synthetic@example.com");

        String json = objectMapper.writeValueAsString(view);
        assertThat(json).contains("Nguyễn An", "********123", "n***@example.com");
        assertThat(json).doesNotContain("+84900000123", "n.synthetic@example.com", "Encrypted", "ciphertext");
        assertThat(view.toString()).isEqualTo("OwnerCustomerPiiView[redacted]");
    }

    @Test
    void identicalPiiAcrossCustomersUsesDifferentIvForEveryField() {
        long firstUser = seedUser("fresh-iv-one");
        long secondUser = seedUser("fresh-iv-two");
        OwnerCustomerPiiView first = service.create(
                firstUser, "Same Name", "0900000123", "same@example.com", CustomerStatus.ACTIVE);
        OwnerCustomerPiiView second = service.create(
                secondUser, "Same Name", "0900000123", "same@example.com", CustomerStatus.ACTIVE);

        Map<String, byte[]> firstStored = encryptedColumns(first.getCustomerId());
        Map<String, byte[]> secondStored = encryptedColumns(second.getCustomerId());
        assertThat(firstStored.get("full_name_enc")).isNotEqualTo(secondStored.get("full_name_enc"));
        assertThat(firstStored.get("phone_enc")).isNotEqualTo(secondStored.get("phone_enc"));
        assertThat(firstStored.get("email_enc")).isNotEqualTo(secondStored.get("email_enc"));
    }

    @Test
    void ownerRetrievalMasksContactsAndUnauthorizedActorCannotTriggerDecryption() {
        long userId = seedUser("owner-read");
        long otherUserId = seedUser("other-actor");
        OwnerCustomerPiiView created = service.create(
                userId, "Owner Name", "0900000456", "owner@example.com", CustomerStatus.ACTIVE);

        byte[] fullName = encryptedColumns(created.getCustomerId()).get("full_name_enc");
        fullName[fullName.length - 1] ^= 1;
        transaction.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.customer SET full_name_enc = ? WHERE customer_id = ?",
                fullName,
                created.getCustomerId()));

        assertBusinessError(
                () -> service.getForOwner(created.getCustomerId(), otherUserId), ErrorCode.AUTH_FORBIDDEN);
        assertBusinessError(
                () -> service.getForOwner(created.getCustomerId(), userId), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void updateUsesFreshIvAndPreservesUnchangedCiphertext() {
        long userId = seedUser("encrypted-update");
        OwnerCustomerPiiView created = service.create(
                userId, "Owner Name", "0900000123", "owner@example.com", CustomerStatus.ACTIVE);
        Map<String, byte[]> before = encryptedColumns(created.getCustomerId());

        OwnerCustomerPiiView updated = service.replacePhoneForOwner(
                created.getCustomerId(), userId, "0900000123");
        Map<String, byte[]> after = encryptedColumns(created.getCustomerId());

        assertThat(after.get("phone_enc")).isNotEqualTo(before.get("phone_enc"));
        assertThat(after.get("full_name_enc")).containsExactly(before.get("full_name_enc"));
        assertThat(after.get("email_enc")).containsExactly(before.get("email_enc"));
        assertThat(updated.getPhoneMasked()).isEqualTo("********123");
        assertThat(crypto.decrypt(after.get("phone_enc"))).isEqualTo("0900000123");
    }

    @Test
    void authenticatedDecryptionFailureLeavesExistingCiphertextUntouched() {
        long userId = seedUser("tampered-update");
        OwnerCustomerPiiView created = service.create(
                userId, "Owner Name", "0900000123", "owner@example.com", CustomerStatus.ACTIVE);
        Map<String, byte[]> before = encryptedColumns(created.getCustomerId());
        byte[] tamperedEmail = before.get("email_enc").clone();
        tamperedEmail[tamperedEmail.length - 1] ^= 1;
        transaction.executeWithoutResult(ignored -> jdbc.update(
                "UPDATE banking.customer SET email_enc = ? WHERE customer_id = ?",
                tamperedEmail,
                created.getCustomerId()));

        assertBusinessError(
                () -> service.replacePhoneForOwner(created.getCustomerId(), userId, "0900000999"),
                ErrorCode.INTERNAL_ERROR);
        Map<String, byte[]> after = encryptedColumns(created.getCustomerId());
        assertThat(after.get("full_name_enc")).containsExactly(before.get("full_name_enc"));
        assertThat(after.get("phone_enc")).containsExactly(before.get("phone_enc"));
        assertThat(after.get("email_enc")).containsExactly(tamperedEmail);
    }

    @Test
    void validationFailureCannotPersistPartialCustomer() {
        long userId = seedUser("invalid-create");
        assertBusinessError(
                () -> service.create(userId, "Never Stored", "plaintext-phone", "safe@example.com",
                        CustomerStatus.ACTIVE),
                ErrorCode.VALIDATION_ERROR);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.customer WHERE user_id = ?", Long.class, userId)).isZero();
    }

    @Test
    void duplicateUserCreationIsRejectedWithoutReplacingExistingEncryptedProfile() {
        long userId = seedUser("duplicate-create");
        OwnerCustomerPiiView existing = service.create(
                userId, "Original Name", "0900000123", "original@example.com", CustomerStatus.ACTIVE);
        Map<String, byte[]> before = encryptedColumns(existing.getCustomerId());

        assertThatThrownBy(() -> service.create(
                userId, "Replacement Name", "0900000999", "replacement@example.com", CustomerStatus.INACTIVE))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM banking.customer WHERE user_id = ?", Long.class, userId)).isEqualTo(1L);
        Map<String, byte[]> after = encryptedColumns(existing.getCustomerId());
        assertThat(after.get("full_name_enc")).containsExactly(before.get("full_name_enc"));
        assertThat(after.get("phone_enc")).containsExactly(before.get("phone_enc"));
        assertThat(after.get("email_enc")).containsExactly(before.get("email_enc"));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM banking.customer WHERE customer_id = ?", String.class, existing.getCustomerId()))
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("BE-CUS-IMP-08 database: FK failure rolls back the encrypted Customer create atomically")
    void foreignKeyFailureRollsBackEncryptedCustomerCreation() {
        long rowsBefore = jdbc.queryForObject("SELECT count(*) FROM banking.customer", Long.class);

        assertThatThrownBy(() -> service.create(
                Long.MAX_VALUE,
                "Never Committed",
                "+84900000999",
                "rollback.synthetic@example.com",
                CustomerStatus.ACTIVE))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM banking.customer", Long.class))
                .isEqualTo(rowsBefore);
    }

    private static boolean containsSubsequence(byte[] value, byte[] candidate) {
        if (candidate.length == 0 || candidate.length > value.length) {
            return false;
        }
        for (int offset = 0; offset <= value.length - candidate.length; offset++) {
            int index = 0;
            while (index < candidate.length && value[offset + index] == candidate[index]) {
                index++;
            }
            if (index == candidate.length) {
                return true;
            }
        }
        return false;
    }

    private Map<String, byte[]> encryptedColumns(long customerId) {
        return jdbc.queryForObject(
                "SELECT full_name_enc, phone_enc, email_enc FROM banking.customer WHERE customer_id = ?",
                (result, row) -> Map.of(
                        "full_name_enc", result.getBytes("full_name_enc"),
                        "phone_enc", nullableBytes(result, "phone_enc"),
                        "email_enc", nullableBytes(result, "email_enc")),
                customerId);
    }

    private static byte[] nullableBytes(java.sql.ResultSet result, String column) throws java.sql.SQLException {
        byte[] value = result.getBytes(column);
        return value == null ? new byte[0] : value;
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

    private static void assertBusinessError(Runnable invocation, ErrorCode expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BankingBusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(expected);
                    assertThat(error.getMessage()).isEqualTo(expected.detail());
                    assertThat(error).hasNoCause();
                });
    }
}
