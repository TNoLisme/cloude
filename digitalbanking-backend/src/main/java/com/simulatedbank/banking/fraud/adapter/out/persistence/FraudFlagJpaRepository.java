package com.simulatedbank.banking.fraud.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FraudFlagJpaRepository extends JpaRepository<FraudFlagEntity, Long> {

    List<FraudFlagEntity> findByTransferId(Long transferId);
}
