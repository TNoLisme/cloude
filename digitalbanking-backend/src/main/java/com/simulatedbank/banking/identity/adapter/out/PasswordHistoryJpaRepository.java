package com.simulatedbank.banking.identity.adapter.out;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
interface PasswordHistoryJpaRepository extends JpaRepository<PasswordHistoryEntity, Long> {
    @Query("""
            select history.passwordHash from PasswordHistoryEntity history where history.userId = :userId
             order by history.createdAt desc, history.historyId desc
            """)
    List<String> findRecentHashes(@Param("userId") long userId, Pageable page);
}
