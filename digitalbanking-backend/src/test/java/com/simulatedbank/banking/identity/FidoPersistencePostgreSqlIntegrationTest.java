package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.simulatedbank.banking.identity.adapter.out.FidoCeremonyEntity;
import com.simulatedbank.banking.identity.adapter.out.FidoDeviceEntity;
import com.simulatedbank.banking.identity.adapter.out.JpaFidoCredentialStore;
import com.simulatedbank.banking.identity.adapter.out.UserCredentialEntity;
import com.simulatedbank.banking.identity.application.port.FidoCredentialStore;
import com.simulatedbank.banking.identity.application.port.FidoCredentialStore.Ceremony;
import com.simulatedbank.banking.identity.application.port.FidoCredentialStore.Credential;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real V1/V2/V3 and independent adapter instances; provisioned data is never an authentication fallback. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class FidoPersistencePostgreSqlIntegrationTest {
    private static final String PUBLIC_KEY = "ZXh0ZXJuYWxseS1wcm92aXNpb25lZC1jb3Nl";
    private static final String HANDLE = "dXNlci1oYW5kbGU";
    private static final String NONCE = "bm9uY2UtZm9yLXBlcnNpc3RlbmNlLXRlc3Q";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fido_persistence").withUsername("postgres").withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        registry.add("app.identity.otp-expiry-sweep-delay", () -> "PT24H");
    }

    @Autowired private FidoCredentialStore store;
    @Autowired private AutowireCapableBeanFactory beans;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    private TransactionTemplate tx;
    private FidoCredentialStore otherInstance;
    private long userId;
    private long deviceId;
    private String deviceRef;
    private String credentialId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        otherInstance = beans.createBean(JpaFidoCredentialStore.class);
        deviceRef = UUID.randomUUID().toString();
        credentialId = "credential-" + UUID.randomUUID();
        userId = tx.execute(status -> jdbc.queryForObject("""
                INSERT INTO banking.app_user (username, password_hash, user_type)
                VALUES (?, 'externally-managed-password-hash', 'CUSTOMER') RETURNING user_id
                """, Long.class, "fido-store-" + UUID.randomUUID()));
        deviceId = tx.execute(status -> jdbc.queryForObject("""
                INSERT INTO banking.device (device_ref, user_id, device_name, device_fingerprint_hash,
                    fido_credential_id, fido_public_key_cose, fido_user_handle, trusted)
                VALUES (?::uuid, ?, 'Provisioned test device', ?, ?, ?, ?, true) RETURNING device_id
                """, Long.class, deviceRef, userId, "a".repeat(64), credentialId, PUBLIC_KEY, HANDLE));
    }

    @Test
    void forwardMigrationPreservesV2RowsWithoutInventingVerificationMaterial() {
        String database = "fido_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.execute("CREATE DATABASE " + database);
        try {
            String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                    + "/" + database;
            var source = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).schemas("banking").defaultSchema("banking")
                    .target("2").load().migrate();
            var legacy = new JdbcTemplate(source);
            long legacyUser = legacy.queryForObject("""
                    INSERT INTO banking.app_user (username, password_hash, user_type)
                    VALUES ('legacy-upgrade', 'external-password', 'CUSTOMER') RETURNING user_id
                    """, Long.class);
            long legacyDevice = legacy.queryForObject("""
                    INSERT INTO banking.device (device_ref, user_id, device_name, device_fingerprint_hash,
                        fido_credential_id, trusted)
                    VALUES (?::uuid, ?, 'Legacy device', ?, 'existing-credential', true) RETURNING device_id
                    """, Long.class, UUID.randomUUID().toString(), legacyUser, "c".repeat(64));
            long legacyChallenge = legacy.queryForObject("""
                    INSERT INTO banking.auth_challenge (challenge_ref, user_id, device_id,
                        fido_credential_id, purpose, method, expires_at, correlation_id)
                    VALUES (?::uuid, ?, ?, 'existing-credential', 'LOGIN_NEW_DEVICE', 'FIDO',
                        clock_timestamp() + interval '30 seconds', 'upgrade') RETURNING challenge_id
                    """, Long.class, UUID.randomUUID().toString(), legacyUser, legacyDevice);
            var migrated = Flyway.configure().dataSource(source).schemas("banking").defaultSchema("banking")
                    .target("3").load().migrate();
            assertThat(migrated.migrationsExecuted).isEqualTo(1);
            var device = legacy.queryForMap("""
                    SELECT fido_credential_id, fido_public_key_cose, fido_user_handle, fido_sign_count,
                           trusted, status FROM banking.device WHERE device_id = ?
                    """, legacyDevice);
            assertThat(device.get("fido_credential_id")).isEqualTo("existing-credential");
            assertThat(device.get("fido_public_key_cose")).isNull();
            assertThat(device.get("fido_user_handle")).isNull();
            assertThat(device.get("fido_sign_count")).isEqualTo(0L);
            assertThat(device.get("trusted")).isEqualTo(true);
            assertThat(device.get("status")).isEqualTo("ACTIVE");
            var challenge = legacy.queryForMap("""
                    SELECT fido_nonce, status, attempt_count FROM banking.auth_challenge WHERE challenge_id = ?
                    """, legacyChallenge);
            assertThat(challenge.get("fido_nonce")).isNull();
            assertThat(challenge.get("status")).isEqualTo("CREATED");
            assertThat(((Number) challenge.get("attempt_count")).intValue()).isZero();
        } finally {
            admin.execute("DROP DATABASE " + database);
        }
    }

    @Test
    void mapsProvisionedMaterialAndLifecycleWithoutTreatingMetadataAsProof() {
        Credential credential = tx.execute(status -> store.findCredential(deviceRef, userId).orElseThrow());
        assertThat(credential).isEqualTo(new Credential(deviceId, userId, deviceRef, credentialId,
                PUBLIC_KEY, HANDLE, 0, true, "ACTIVE"));
        tx.executeWithoutResult(status -> jdbc.update("""
                UPDATE banking.device SET trusted = false, status = 'REVOKED' WHERE device_id = ?
                """, deviceId));
        Credential revoked = tx.execute(status -> store.lockCredential(deviceId, userId).orElseThrow());
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.trusted()).isFalse();
        assertThat(revoked.publicKeyCose()).isEqualTo(PUBLIC_KEY);
        assertThat(credential.toString()).isEqualTo("Credential[redacted]");
    }

    @Test
    void legacyDeviceWithCredentialIdentifierDoesNotGainVerificationMaterial() {
        long legacy = tx.execute(status -> jdbc.queryForObject("""
                INSERT INTO banking.device (device_ref, user_id, device_name, device_fingerprint_hash,
                    fido_credential_id, trusted)
                VALUES (?::uuid, ?, 'Legacy device', ?, 'legacy-id-' || ?, true) RETURNING device_id
                """, Long.class, UUID.randomUUID().toString(), userId, "b".repeat(64), userId));
        Credential credential = tx.execute(status -> store.lockCredential(legacy, userId).orElseThrow());
        assertThat(credential.publicKeyCose()).isNull();
        assertThat(credential.userHandle()).isNull();
        assertThat(credential.signCount()).isZero();
        assertThat(credential.trusted()).isTrue();
        tx.executeWithoutResult(status -> {
            assertThat(store.findCredential(deviceRef, userId + 1)).isEmpty();
            assertThat(store.lockCredential(deviceId, userId + 1)).isEmpty();
        });
        for (String ref : List.of("not-a-uuid", deviceRef + " ", "1-1-1-1-1")) {
            tx.executeWithoutResult(status -> {
                assertThat(store.findCredential(ref, userId)).isEmpty();
                assertThat(store.lockCeremony(ref)).isEmpty();
                // Malformed public references must not poison the PostgreSQL transaction.
                assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            });
        }
    }

    @Test
    void createsRandomBoundLoginCeremoniesWithNullableLegacyNonce() {
        Ceremony first = ceremony(3, Duration.ofMinutes(1));
        Ceremony second = ceremony(3, Duration.ofMinutes(1));
        assertThat(UUID.fromString(first.challengeRef())).isNotEqualTo(UUID.fromString(second.challengeRef()));
        assertThat(first.userId()).isEqualTo(userId);
        assertThat(first.deviceId()).isEqualTo(deviceId);
        assertThat(first.credentialId()).isEqualTo(credentialId);
        assertThat(first.nonce()).isEqualTo(NONCE);
        assertThat(first.status()).isEqualTo("CREATED");
        assertThat(first.attemptCount()).isZero();
        assertThat(first.maxAttempts()).isEqualTo(3);
        assertThat(first.consumedAt()).isNull();
        assertThat(first.purpose()).isEqualTo("LOGIN_NEW_DEVICE");
        assertThat(first.method()).isEqualTo("FIDO");
        assertThat(first.toString()).isEqualTo("Ceremony[redacted]");
        tx.executeWithoutResult(status -> jdbc.update("""
                UPDATE banking.auth_challenge SET fido_nonce = NULL WHERE challenge_id = ?
                """, first.challengeId()));
        assertThat(tx.execute(status -> store.lockCeremony(first.challengeRef()).orElseThrow()).nonce()).isNull();
    }

    @Test
    void verificationAndCounterAreDurableAcrossIndependentAdaptersAndRollBackTogether() {
        Ceremony first = ceremony(3, Duration.ofMinutes(1));
        tx.executeWithoutResult(status -> {
            store.lockCeremony(first.challengeRef()).orElseThrow();
            store.lockUser(userId);
            store.lockCredential(deviceId, userId).orElseThrow();
            assertThat(store.verifyCeremony(first.challengeId())).isTrue();
            store.advanceCounter(deviceId, 7);
        });
        tx.executeWithoutResult(status -> {
            assertThat(otherInstance.lockCredential(deviceId, userId).orElseThrow().signCount()).isEqualTo(7);
            Ceremony verified = otherInstance.lockCeremony(first.challengeRef()).orElseThrow();
            assertThat(verified.status()).isEqualTo("VERIFIED");
            assertThat(verified.attemptCount()).isEqualTo(1);
            assertThat(otherInstance.isLive(first.challengeId())).isFalse();
        });
        assertThat(tx.<Boolean>execute(status -> jdbc.queryForObject("""
                SELECT verified_at IS NOT NULL FROM banking.auth_challenge WHERE challenge_id = ?
                """, Boolean.class, first.challengeId()))).isTrue();
        Ceremony next = ceremony(3, Duration.ofMinutes(1));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            store.lockCeremony(next.challengeRef()).orElseThrow();
            store.lockCredential(deviceId, userId).orElseThrow();
            assertThat(store.verifyCeremony(next.challengeId())).isTrue();
            store.advanceCounter(deviceId, 8);
            throw new IllegalStateException("session creation rolled back");
        })).isInstanceOf(IllegalStateException.class);
        tx.executeWithoutResult(status -> {
            assertThat(otherInstance.lockCredential(deviceId, userId).orElseThrow().signCount()).isEqualTo(7);
            Ceremony unchanged = otherInstance.lockCeremony(next.challengeRef()).orElseThrow();
            assertThat(unchanged.status()).isEqualTo("CREATED");
            assertThat(unchanged.attemptCount()).isZero();
        });
    }

    @Test
    void unsignedCounterBoundsAreEnforcedByDatabaseAndZeroRemainsValid() {
        tx.executeWithoutResult(status -> {
            store.lockCredential(deviceId, userId).orElseThrow();
            store.advanceCounter(deviceId, 0);
            store.advanceCounter(deviceId, 0xffff_ffffL);
        });
        assertThat(tx.execute(status -> store.lockCredential(deviceId, userId).orElseThrow()).signCount())
                .isEqualTo(0xffff_ffffL);
        for (long invalid : new long[] {-1, 0x1_0000_0000L}) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                    UPDATE banking.device SET fido_sign_count = ? WHERE device_id = ?
                    """, invalid, deviceId))).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void maxAttemptsAtomicallyLocksWithoutOverflowOrLaterVerification() {
        Ceremony ceremony = ceremony(2, Duration.ofMinutes(1));
        tx.executeWithoutResult(status -> {
            store.failCeremony(ceremony.challengeId());
            assertThat(store.lockCeremony(ceremony.challengeRef()).orElseThrow().attemptCount()).isEqualTo(1);
            assertThat(store.isLive(ceremony.challengeId())).isTrue();
            store.failCeremony(ceremony.challengeId());
            store.failCeremony(ceremony.challengeId());
            assertThat(store.verifyCeremony(ceremony.challengeId())).isFalse();
            Ceremony locked = store.lockCeremony(ceremony.challengeRef()).orElseThrow();
            assertThat(locked.status()).isEqualTo("LOCKED");
            assertThat(locked.attemptCount()).isEqualTo(2);
            assertThat(store.isLive(ceremony.challengeId())).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"SENT", "VERIFIED", "LOCKED", "EXPIRED", "CANCELLED"})
    void nonCreatedStatesCannotBeVerifiedOrChargedAnotherAttempt(String state) {
        Ceremony ceremony = ceremony(2, Duration.ofMinutes(1));
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE banking.auth_challenge SET status = ? WHERE challenge_id = ?",
                    state, ceremony.challengeId());
            assertThat(store.verifyCeremony(ceremony.challengeId())).isFalse();
            store.failCeremony(ceremony.challengeId());
            store.expireCeremony(ceremony.challengeId());
            Ceremony unchanged = store.lockCeremony(ceremony.challengeRef()).orElseThrow();
            assertThat(unchanged.status()).isEqualTo(state);
            assertThat(unchanged.attemptCount()).isZero();
            assertThat(store.isLive(ceremony.challengeId())).isFalse();
        });
    }

    @Test
    void consumptionRemainsExistingRepositoryConcernAndCannotBeExpiredOrReverified() {
        Ceremony ceremony = ceremony(2, Duration.ofMinutes(1));
        tx.executeWithoutResult(status -> {
            assertThat(store.verifyCeremony(ceremony.challengeId())).isTrue();
            jdbc.update("""
                    UPDATE banking.auth_challenge SET consumed_at = clock_timestamp(),
                        expires_at = created_at + interval '1 microsecond' WHERE challenge_id = ?
                    """, ceremony.challengeId());
            store.expireCeremony(ceremony.challengeId());
            store.failCeremony(ceremony.challengeId());
            assertThat(store.verifyCeremony(ceremony.challengeId())).isFalse();
            assertThat(store.isLive(ceremony.challengeId())).isFalse();
            Ceremony consumed = store.lockCeremony(ceremony.challengeRef()).orElseThrow();
            assertThat(consumed.status()).isEqualTo("VERIFIED");
            assertThat(consumed.consumedAt()).isNotNull();
            assertThat(consumed.attemptCount()).isEqualTo(1);
        });
    }

    @Test
    void rowLocksRefreshStaleManagedDeviceCeremonyAndUserProjections() throws Exception {
        Ceremony ceremony = ceremony(3, Duration.ofMinutes(1));
        var pool = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                entityManager.find(FidoDeviceEntity.class, deviceId);
                entityManager.find(FidoCeremonyEntity.class, ceremony.challengeId());
                var user = entityManager.find(UserCredentialEntity.class, userId);
                var changed = pool.submit(() -> tx.executeWithoutResult(other -> {
                    jdbc.update("""
                            UPDATE banking.device SET fido_sign_count = 9, status = 'REVOKED', trusted = false
                             WHERE device_id = ?
                            """, deviceId);
                    jdbc.update("""
                            UPDATE banking.auth_challenge SET attempt_count = 1, status = 'LOCKED'
                             WHERE challenge_id = ?
                            """, ceremony.challengeId());
                    jdbc.update("UPDATE banking.app_user SET status = 'DISABLED' WHERE user_id = ?", userId);
                }));
                try {
                    changed.get(15, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                Ceremony refreshed = store.lockCeremony(ceremony.challengeRef()).orElseThrow();
                assertThat(refreshed.status()).isEqualTo("LOCKED");
                assertThat(refreshed.attemptCount()).isEqualTo(1);
                store.lockUser(userId);
                assertThat(ReflectionTestUtils.getField(user, "status")).isEqualTo("DISABLED");
                Credential credential = store.lockCredential(deviceId, userId).orElseThrow();
                assertThat(credential.signCount()).isEqualTo(9);
                assertThat(credential.status()).isEqualTo("REVOKED");
                assertThat(credential.trusted()).isFalse();
            });
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentIndependentAdaptersVerifySameCeremonyOnlyOnce() throws Exception {
        Ceremony ceremony = ceremony(3, Duration.ofMinutes(1));
        var pool = Executors.newFixedThreadPool(2);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        String application = "fido-replay-" + userId;
        try {
            var first = pool.submit(() -> tx.execute(status -> {
                store.lockCeremony(ceremony.challengeRef()).orElseThrow();
                store.lockUser(userId);
                store.lockCredential(deviceId, userId).orElseThrow();
                boolean verified = store.verifyCeremony(ceremony.challengeId());
                store.advanceCounter(deviceId, 1);
                held.countDown();
                awaitLatch(release);
                return verified;
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> tx.execute(status -> {
                nameTransaction(application);
                Ceremony refreshed = otherInstance.lockCeremony(ceremony.challengeRef()).orElseThrow();
                assertThat(refreshed.status()).isEqualTo("VERIFIED");
                return otherInstance.verifyCeremony(ceremony.challengeId());
            }));
            awaitBlocked(application);
            release.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(15, TimeUnit.SECONDS)).isFalse();
            tx.executeWithoutResult(status -> {
                assertThat(store.lockCeremony(ceremony.challengeRef()).orElseThrow().attemptCount()).isEqualTo(1);
                assertThat(store.lockCredential(deviceId, userId).orElseThrow().signCount()).isEqualTo(1);
            });
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentFailuresNeverExceedMaximumAttempts() throws Exception {
        Ceremony ceremony = ceremony(2, Duration.ofMinutes(1));
        var pool = Executors.newFixedThreadPool(4);
        var start = new CyclicBarrier(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 4; index++) {
                FidoCredentialStore instance = index % 2 == 0 ? store : otherInstance;
                futures.add(pool.submit(() -> {
                    start.await(15, TimeUnit.SECONDS);
                    tx.executeWithoutResult(status -> instance.failCeremony(ceremony.challengeId()));
                    return null;
                }));
            }
            for (var future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
            Ceremony locked = tx.execute(status -> store.lockCeremony(ceremony.challengeRef()).orElseThrow());
            assertThat(locked.status()).isEqualTo("LOCKED");
            assertThat(locked.attemptCount()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void separateCeremoniesSerializeOnDeviceCounterAcrossInstances() throws Exception {
        Ceremony firstCeremony = ceremony(3, Duration.ofMinutes(1));
        Ceremony secondCeremony = ceremony(3, Duration.ofMinutes(1));
        var pool = Executors.newFixedThreadPool(2);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        String application = "fido-counter-" + userId;
        try {
            var first = pool.submit(() -> tx.executeWithoutResult(status -> {
                store.lockCeremony(firstCeremony.challengeRef()).orElseThrow();
                store.lockCredential(deviceId, userId).orElseThrow();
                assertThat(store.verifyCeremony(firstCeremony.challengeId())).isTrue();
                store.advanceCounter(deviceId, 1);
                held.countDown();
                awaitLatch(release);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> tx.executeWithoutResult(status -> {
                nameTransaction(application);
                // Preload before waiting: lockCredential must refresh this persistence context.
                otherInstance.findCredential(deviceRef, userId).orElseThrow();
                otherInstance.lockCeremony(secondCeremony.challengeRef()).orElseThrow();
                Credential refreshed = otherInstance.lockCredential(deviceId, userId).orElseThrow();
                assertThat(refreshed.signCount()).isEqualTo(1);
                assertThat(otherInstance.verifyCeremony(secondCeremony.challengeId())).isTrue();
                otherInstance.advanceCounter(deviceId, 2);
            }));
            awaitBlocked(application);
            release.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
            assertThat(tx.execute(status -> store.lockCredential(deviceId, userId).orElseThrow()).signCount())
                    .isEqualTo(2);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ceremony", "user", "device"})
    void expiryUsesDatabaseWallClockAfterEveryLockWaitEvenWhenHolderRollsBack(String lockedRow) throws Exception {
        Ceremony ceremony = ceremony(3, Duration.ofSeconds(3));
        var pool = Executors.newFixedThreadPool(2);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        String application = "fido-expiry-" + lockedRow + "-" + userId;
        try {
            var holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                switch (lockedRow) {
                    case "ceremony" -> store.lockCeremony(ceremony.challengeRef()).orElseThrow();
                    case "user" -> store.lockUser(userId);
                    case "device" -> store.lockCredential(deviceId, userId).orElseThrow();
                    default -> throw new IllegalArgumentException(lockedRow);
                }
                held.countDown();
                awaitLatch(release);
                status.setRollbackOnly();
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
            var waiter = pool.submit(() -> tx.executeWithoutResult(status -> {
                nameTransaction(application);
                otherInstance.lockCeremony(ceremony.challengeRef()).orElseThrow();
                otherInstance.lockUser(userId);
                otherInstance.lockCredential(deviceId, userId).orElseThrow();
                assertThat(otherInstance.isLive(ceremony.challengeId())).isFalse();
                assertThat(otherInstance.verifyCeremony(ceremony.challengeId())).isFalse();
                otherInstance.failCeremony(ceremony.challengeId());
                otherInstance.expireCeremony(ceremony.challengeId());
            }));
            awaitBlocked(application);
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25)).until(() ->
                    tx.execute(status -> jdbc.queryForObject("""
                            SELECT expires_at <= clock_timestamp() FROM banking.auth_challenge
                             WHERE challenge_id = ?
                            """, Boolean.class, ceremony.challengeId())));
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            waiter.get(15, TimeUnit.SECONDS);
            Ceremony expired = tx.execute(status -> store.lockCeremony(ceremony.challengeRef()).orElseThrow());
            assertThat(expired.status()).isEqualTo("EXPIRED");
            assertThat(expired.attemptCount()).isZero();
            assertThat(tx.execute(status -> store.lockCredential(deviceId, userId).orElseThrow()).signCount()).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void allAdapterMethodsRequireCallerTransaction() {
        List<Runnable> operations = List.of(
                () -> store.findCredential(deviceRef, userId),
                () -> store.lockCredential(deviceId, userId),
                () -> store.lockUser(userId),
                () -> store.lockCeremony(UUID.randomUUID().toString()),
                () -> store.createCeremony(userId, deviceId, credentialId, NONCE, Instant.now().plusSeconds(30), 3, "test"),
                () -> store.advanceCounter(deviceId, 1),
                () -> store.verifyCeremony(1),
                () -> store.failCeremony(1),
                () -> store.expireCeremony(1),
                () -> store.isLive(1));
        for (Runnable operation : operations) {
            assertThatThrownBy(operation::run).isInstanceOf(IllegalTransactionStateException.class);
        }
    }

    private Ceremony ceremony(int attempts, Duration ttl) {
        return tx.execute(status -> store.createCeremony(userId, deviceId, credentialId, NONCE,
                Instant.now().plus(ttl), attempts, "fido-persistence-test"));
    }

    private void nameTransaction(String application) {
        jdbc.queryForObject("SELECT set_config('application_name', ?, true)", String.class, application);
    }

    private void awaitBlocked(String application) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25)).until(() ->
                tx.execute(status -> {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    return Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                             WHERE application_name = ? AND cardinality(pg_blocking_pids(pid)) > 0)
                            """, Boolean.class, application));
                }));
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Lock holder timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
