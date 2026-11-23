package com.simulatedbank.banking.identity.application;

import com.simulatedbank.banking.identity.api.IdentityUserQuery;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only implementation of {@link IdentityUserQuery}. It deliberately does not widen the customer/self read
 * paths and never returns password, lockout or role data.
 */
@Service
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class IdentityUserQueryService implements IdentityUserQuery {

    private final UserAccountRepository users;

    public IdentityUserQueryService(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findStaffDisplayName(long userId) {
        if (userId <= 0) {
            return Optional.empty();
        }
        return users.findById(userId)
                .filter(user -> "STAFF".equals(user.userType()))
                .map(UserAccountRepository.AppUserSnapshot::username);
    }
}
