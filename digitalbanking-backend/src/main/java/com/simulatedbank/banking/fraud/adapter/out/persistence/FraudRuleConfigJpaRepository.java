package com.simulatedbank.banking.fraud.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface FraudRuleConfigJpaRepository extends JpaRepository<FraudRuleConfigEntity, FraudRuleConfigId> {

    @Query("SELECT r FROM FraudRuleConfigEntity r WHERE r.enabled = true AND (r.effectiveTo IS NULL OR r.effectiveTo > :now) AND r.effectiveFrom <= :now ORDER BY r.ruleCode ASC, r.version DESC")
    List<FraudRuleConfigEntity> findActiveRules(@Param("now") Instant now);
}
