package com.simulatedbank.transfercandidate.persistence;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Lookup only: a JPA merge/save is not an atomic idempotency claim. */
public interface IdempotencyRecordJpaRepository extends Repository<IdempotencyRecordEntity, String> {
    Optional<IdempotencyRecordEntity> findById(String idempotencyKey);
}
