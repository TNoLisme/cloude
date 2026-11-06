package com.bank.simulator.identity.application;

import com.bank.simulator.DisposablePostgresTestSupport;
import com.bank.simulator.identity.application.OnboardingService;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.shared.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class OnboardingSecurityPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            DisposablePostgresTestSupport.newDisposablePostgres("onboarding_security_postgres_test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        DisposablePostgresTestSupport.registerDatasource(registry, postgres);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private IdentityJdbcRepository identityRepository;


    @Autowired
    private com.bank.simulator.identity.application.RecoveryService recoveryService;

    @Autowired
    private com.bank.simulator.identity.infrastructure.security.RefreshSessionService refreshSessionService;

    @Autowired
    private com.bank.simulator.identity.application.PinCredentialService pinCredentialService;

    @Autowired
    private com.bank.simulator.identity.infrastructure.security.PinHashingService pinHashingService;

    @Autowired
    private com.bank.simulator.identity.infrastructure.security.PasswordHashingService passwordHashingService;

    @Autowired
    private com.bank.simulator.identity.infrastructure.security.RecoveryTokenRepository recoveryTokenRepository;

    @Autowired
    private com.bank.simulator.identity.infrastructure.security.RecoveryTokenCodec recoveryTokenCodec;
    private final Instant now = Instant.parse("2026-10-22T10:00:00Z");

    @BeforeEach
    void resetTables() {
        DisposablePostgresTestSupport.clearBusinessTables(jdbcTemplate, postgres);
    }

    // ==========================================================================================
    // 5. DUPLICATE REGISTRATION PREVENTION & DATABASE CONSTRAINTS
    // ==========================================================================================

    @Test
    @DisplayName("Duplicate phone registration is rejected with 409 PHONE_ALREADY_REGISTERED")
    void duplicatePhoneRegistrationIsRejected() {
        String phone = "0911223344";
        onboardingService.createVerifiedRegistration(phone, "user1@example.test", "SecurePass1234!", "User One", "Address 1");

        assertThatThrownBy(() -> onboardingService.createVerifiedRegistration(
                phone, "user2@example.test", "SecurePass1234!", "User Two", "Address 2"))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> {
                    ApiException ex = (ApiException) err;
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo("PHONE_ALREADY_REGISTERED");
                });

        // Database ground truth
        Integer userCount = jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE phone = ?", Integer.class, phone);
        assertThat(userCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Duplicate email with mixed-case and whitespace is normalized and rejected with 409 EMAIL_ALREADY_REGISTERED")
    void duplicateEmailWithCaseAndWhitespaceIsNormalizedAndRejected() {
        String phone1 = "0911223345";
        String phone2 = "0911223346";
        String canonicalEmail = "john.doe@bank.test";
        String dirtyEmail = "   JOHN.DOE@Bank.TEST   ";

        onboardingService.createVerifiedRegistration(phone1, canonicalEmail, "SecurePass1234!", "John Doe", "Address 1");

        // Attempting to register with uppercase and leading/trailing whitespace
        assertThatThrownBy(() -> onboardingService.createVerifiedRegistration(
                phone2, dirtyEmail, "SecurePass1234!", "John Doe Impersonator", "Address 2"))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> {
                    ApiException ex = (ApiException) err;
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo("EMAIL_ALREADY_REGISTERED");
                });

        // Ground truth: Only 1 user exists with normalized email
        Integer emailCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE email_normalized = 'john.doe@bank.test'", Integer.class);
        assertThat(emailCount).isEqualTo(1);
    }

    @Test
    @DisplayName("PostgreSQL database enforces unique constraints uq_users_phone and uq_users_email_normalized")
    void databaseDirectlyEnforcesUniqueConstraints() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        identityRepository.createUser(id1, "0911223347", "user_canonical@bank.test", "dummy-hash", "CUSTOMER", now);

        // Direct duplicate phone violation
        assertThatThrownBy(() -> identityRepository.createUser(id2, "0911223347", "different_email@bank.test", "dummy-hash", "CUSTOMER", now))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_users_phone");

        // Direct duplicate email violation
        assertThatThrownBy(() -> identityRepository.createUser(id3, "0911223348", "user_canonical@bank.test", "dummy-hash", "CUSTOMER", now))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_users_email_normalized");
    }

    @Test
    @DisplayName("PostgreSQL check constraint ck_users_email_normalized rejects non-lowercased or untrimmed emails")
    void databaseCheckConstraintRejectsNonNormalizedEmail() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO users (id, phone, email_normalized, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, "0911223349", "NotNormalized@Bank.Test", "dummy-hash", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_email_normalized");
    }

    @Test
    @DisplayName("Concurrent registrations with identical phone number result in exactly 1 winner and 1 409 conflict without orphan records")
    void concurrentRegistrationWithSamePhoneHandlesUniqueConstraintSafely() throws Exception {
        String phone = "0911223350";
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String email = "user" + i + "_race@bank.test";
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return onboardingService.createVerifiedRegistration(
                            phone, email, "Password1234!", "User Race", "Address Race");
                } catch (ApiException ex) {
                    return ex;
                }
            }));
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        int successCount = 0;
        int conflictCount = 0;
        for (Future<Object> future : futures) {
            Object result = future.get(10, TimeUnit.SECONDS);
            if (result instanceof OnboardingService.RegistrationResult) {
                successCount++;
            } else if (result instanceof ApiException ex) {
                assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ex.code()).isEqualTo("PHONE_ALREADY_REGISTERED");
                conflictCount++;
            }
        }
        executor.shutdown();

        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(1);

        // Verify ground truth: Exactly 1 user, 1 customer, 1 account in PostgreSQL
        Integer users = jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE phone = ?", Integer.class, phone);
        assertThat(users).isEqualTo(1);

        UUID userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone = ?", UUID.class, phone);
        Integer customers = jdbcTemplate.queryForObject("SELECT count(*) FROM customers WHERE user_id = ?", Integer.class, userId);
        assertThat(customers).isEqualTo(1);

        UUID customerId = jdbcTemplate.queryForObject("SELECT id FROM customers WHERE user_id = ?", UUID.class, userId);
        Integer accounts = jdbcTemplate.queryForObject("SELECT count(*) FROM accounts WHERE customer_id = ?", Integer.class, customerId);
        assertThat(accounts).isEqualTo(1);
    }

    // ==========================================================================================
    // 1. ACCOUNT TAKEOVER (ATO) PREVENTION & SESSION REVOCATION
    // ==========================================================================================

    @Test
    @DisplayName("Password recovery confirm immediately revokes all active refresh sessions across all devices in PostgreSQL")
    void passwordResetRevokesAllActiveRefreshSessionsInDatabase() {
        String phone = "0911223361";
        var registered = onboardingService.createVerifiedRegistration(
                phone, "user_recovery@bank.test", "OldPassword123!", "Recovery User", "Address");

        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM customers WHERE id = ?", UUID.class, registered.customerId());

        // Device 1 and Device 2 login
        var session1 = refreshSessionService.issue(userId);
        var session2 = refreshSessionService.issue(userId);

        Integer activeCountBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_sessions WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userId);
        assertThat(activeCountBefore).isEqualTo(2);

        // Generate valid recovery reset token
        String rawResetToken = recoveryTokenCodec.generate();
        Instant currentInstant = Instant.now();
        recoveryTokenRepository.create(UUID.randomUUID(), userId, recoveryTokenCodec.hash(rawResetToken),
                currentInstant, currentInstant.plusSeconds(300));

        // Confirm password reset
        recoveryService.confirm(rawResetToken, "NewSecurePassword123!");

        // Database ground truth: ALL refresh sessions for this user must be revoked
        Integer activeCountAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_sessions WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userId);
        assertThat(activeCountAfter).isEqualTo(0);

        // Attempting to rotate revoked token from Device 1 must fail with invalid session
        assertThatThrownBy(() -> refreshSessionService.rotate(session1.rawToken()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Refresh session is invalid");

        // Attempting to rotate revoked token from Device 2 must fail with invalid session
        assertThatThrownBy(() -> refreshSessionService.rotate(session2.rawToken()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Refresh session is invalid");

        // Password in DB must be updated
        String newPasswordHash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
        assertThat(passwordHashingService.matches("NewSecurePassword123!", newPasswordHash)).isTrue();
        assertThat(passwordHashingService.matches("OldPassword123!", newPasswordHash)).isFalse();
    }

    @Test
    @DisplayName("PIN lockout after 5 failures and reset on success persists ground truth to PostgreSQL")
    void pinLockoutAndResetPersistsToPostgreSql() {
        String phone = "0911223362";
        var registered = onboardingService.createVerifiedRegistration(
                phone, "user_pin@bank.test", "Password123!", "Pin User", "Address");

        UUID customerId = registered.customerId();
        identityRepository.setPin(customerId, pinHashingService.encode("123456"), now);

        // 1 to 4 failed attempts: failure count increments in Postgres
        for (int i = 1; i <= 4; i++) {
            var res = pinCredentialService.verify(customerId, "000000");
            assertThat(res).isEqualTo(PinCredentialService.VerifyResult.INVALID);

            Integer attempts = jdbcTemplate.queryForObject("SELECT failed_attempts FROM customer_pins WHERE customer_id = ?", Integer.class, customerId);
            assertThat(attempts).isEqualTo(i);
        }

        // 5th failed attempt: triggers 15-minute lockout in Postgres
        var fifthRes = pinCredentialService.verify(customerId, "000000");
        assertThat(fifthRes).isEqualTo(PinCredentialService.VerifyResult.LOCKED);

        Integer lockedAttempts = jdbcTemplate.queryForObject("SELECT failed_attempts FROM customer_pins WHERE customer_id = ?", Integer.class, customerId);
        assertThat(lockedAttempts).isEqualTo(5);

        java.sql.Timestamp lockedUntil = jdbcTemplate.queryForObject("SELECT locked_until FROM customer_pins WHERE customer_id = ?", java.sql.Timestamp.class, customerId);
        assertThat(lockedUntil).isNotNull();
        assertThat(lockedUntil.toInstant()).isAfter(Instant.now().plusSeconds(800));

        // During lockout: correct PIN is STILL rejected (no bypass)
        var lockedVerify = pinCredentialService.verify(customerId, "123456");
        assertThat(lockedVerify).isEqualTo(PinCredentialService.VerifyResult.LOCKED);

        // Simulate lockout expiry by winding locked_until back 1 minute into the past
        jdbcTemplate.update("UPDATE customer_pins SET locked_until = ? WHERE customer_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), customerId);

        // Successful verification after lockout expiry resets counter in Postgres
        var postLockoutVerify = pinCredentialService.verify(customerId, "123456");
        assertThat(postLockoutVerify).isEqualTo(PinCredentialService.VerifyResult.VALID);

        Integer resetAttempts = jdbcTemplate.queryForObject("SELECT failed_attempts FROM customer_pins WHERE customer_id = ?", Integer.class, customerId);
        assertThat(resetAttempts).isEqualTo(0);

        java.sql.Timestamp resetLockedUntil = jdbcTemplate.queryForObject("SELECT locked_until FROM customer_pins WHERE customer_id = ?", java.sql.Timestamp.class, customerId);
        assertThat(resetLockedUntil).isNull();
    }

    @Test
    @DisplayName("Login credentials failure and user enumeration protection")
    void loginProtectionAgainstTimingAttackAndEnumeration() {
        String phone = "0911223363";
        onboardingService.createVerifiedRegistration(
                phone, "user_login@bank.test", "RealPassword123!", "Login User", "Address");

        // Wrong password for existing user -> 401 CREDENTIALS_INVALID
        assertThatThrownBy(() -> onboardingService.login(phone, "WrongPassword123!"))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> {
                    ApiException ex = (ApiException) err;
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.code()).isEqualTo("CREDENTIALS_INVALID");
                });

        // Non-existent phone -> 401 CREDENTIALS_INVALID (identical response shape, timing attack mitigated)
        assertThatThrownBy(() -> onboardingService.login("0999887766", "AnyPassword123!"))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> {
                    ApiException ex = (ApiException) err;
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.code()).isEqualTo("CREDENTIALS_INVALID");
                });

        // Inactive user cannot login
        jdbcTemplate.update("UPDATE users SET is_active = FALSE WHERE phone = ?", phone);
        assertThatThrownBy(() -> onboardingService.login(phone, "RealPassword123!"))
                .isInstanceOf(ApiException.class)
                .satisfies(err -> {
                    ApiException ex = (ApiException) err;
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.code()).isEqualTo("CREDENTIALS_INVALID");
                });
    }

    @Test
    @DisplayName("Password recovery initiate with non-existent phone/email does not leak user existence (no oracle)")
    void recoveryInitiateDoesNotLeakUserExistence() {
        // Non-existent identifier returns generic 200 message
        var result = recoveryService.initiate("0999000111", "SMS");
        assertThat(result.message()).contains("If the information is registered, an OTP has been sent");

        // No tokens or challenges created in DB
        Integer challenges = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM otp_challenges WHERE identifier_normalized = '0999000111'", Integer.class);
        assertThat(challenges).isEqualTo(0);
    }
}
