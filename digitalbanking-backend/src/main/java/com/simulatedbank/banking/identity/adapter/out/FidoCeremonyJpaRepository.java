package com.simulatedbank.banking.identity.adapter.out;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
interface FidoCeremonyJpaRepository extends JpaRepository<FidoCeremonyEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ceremony from FidoCeremonyEntity ceremony where ceremony.challengeRef = :challengeRef")
    Optional<FidoCeremonyEntity> lockByRef(@Param("challengeRef") UUID challengeRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ceremony from FidoCeremonyEntity ceremony where ceremony.challengeId = :challengeId")
    Optional<FidoCeremonyEntity> lockById(@Param("challengeId") long challengeId);

    // The adapter acquires the row lock in a separate statement BEFORE these clock predicates.
    // An UPDATE alone can evaluate its predicate before waiting on a transaction that rolls back.
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE banking.auth_challenge
               SET status = 'VERIFIED', attempt_count = attempt_count + 1,
                   verified_at = clock_timestamp()
             WHERE challenge_id = :challengeId AND method = 'FIDO' AND purpose = 'LOGIN_NEW_DEVICE'
               AND status = 'CREATED' AND consumed_at IS NULL
               AND attempt_count < max_attempts AND expires_at > clock_timestamp()
            """, nativeQuery = true)
    int verify(@Param("challengeId") long challengeId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE banking.auth_challenge
               SET attempt_count = attempt_count + 1,
                   status = CASE WHEN attempt_count + 1 >= max_attempts THEN 'LOCKED' ELSE 'CREATED' END
             WHERE challenge_id = :challengeId AND method = 'FIDO' AND purpose = 'LOGIN_NEW_DEVICE'
               AND status = 'CREATED' AND consumed_at IS NULL
               AND attempt_count < max_attempts AND expires_at > clock_timestamp()
            """, nativeQuery = true)
    int fail(@Param("challengeId") long challengeId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE banking.auth_challenge SET status = 'EXPIRED'
             WHERE challenge_id = :challengeId AND method = 'FIDO' AND purpose = 'LOGIN_NEW_DEVICE'
               AND status IN ('CREATED', 'SENT', 'VERIFIED') AND consumed_at IS NULL
               AND expires_at <= clock_timestamp()
            """, nativeQuery = true)
    int expire(@Param("challengeId") long challengeId);

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM banking.auth_challenge
             WHERE challenge_id = :challengeId AND method = 'FIDO' AND purpose = 'LOGIN_NEW_DEVICE'
               AND status = 'CREATED' AND consumed_at IS NULL
               AND attempt_count < max_attempts AND expires_at > clock_timestamp())
            """, nativeQuery = true)
    boolean isLive(@Param("challengeId") long challengeId);
}
