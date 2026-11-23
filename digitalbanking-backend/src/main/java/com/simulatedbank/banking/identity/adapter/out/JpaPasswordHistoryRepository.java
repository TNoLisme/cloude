package com.simulatedbank.banking.identity.adapter.out;

import com.simulatedbank.banking.identity.application.port.PasswordHistoryRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Spring Data JPA CRUD on V2; the application owns the transaction and user-row lock lifetime. */
@Repository
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
@Transactional(propagation = Propagation.MANDATORY)
public class JpaPasswordHistoryRepository implements PasswordHistoryRepository {
    private final UserCredentialJpaRepository users;
    private final PasswordHistoryJpaRepository history;
    private final Clock clock;
    private final EntityManager entityManager;

    public JpaPasswordHistoryRepository(UserCredentialJpaRepository users, PasswordHistoryJpaRepository history,
            Clock clock, EntityManager entityManager) {
        this.users = users;
        this.history = history;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<LockedCredential> lockCredential(long userId) {
        return users.lockById(userId).map(user -> {
            // A locking query can return an already-managed, stale entity from the caller's persistence context.
            // Refresh after acquiring the row lock before checking any credential/history policy.
            entityManager.refresh(user);
            return new LockedCredential(user.getUserId(), user.getPasswordHash(),
                    user.getStatus(), user.getLockedUntil());
        });
    }

    @Override
    public List<String> findRecentHashes(long userId, int count) {
        return history.findRecentHashes(userId, PageRequest.of(0, count));
    }

    @Override
    public void archiveAndReplace(LockedCredential credential, String newHash) {
        // lockCredential already loaded this managed entity under PESSIMISTIC_WRITE in this transaction.
        var user = users.findById(credential.userId()).orElseThrow();
        if (!"ACTIVE".equals(user.getStatus()) || !credential.passwordHash().equals(user.getPasswordHash())) {
            throw new IllegalStateException("Credential changed outside the locked transaction");
        }
        var changedAt = clock.instant();
        history.save(new PasswordHistoryEntity(credential.userId(), credential.passwordHash(), changedAt));
        user.replacePassword(newHash, changedAt);
        // Surface constraint failures inside the adapter, before returning to the caller/wiping the input.
        users.flush();
    }
}
