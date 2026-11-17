package com.simulatedbank.banking.identity.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.application.port.PasswordHistoryRepository;
import com.simulatedbank.banking.identity.domain.PasswordPolicy;
import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credential-history component, NOT an authentication/authorization boundary or a public change-password API.
 * The caller must supply a server-authenticated user ID, apply personal-information/step-up policy,
 * and record the approved C2 credential-change audit in the SAME transaction. Those contracts remain open.
 */
@Service
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class PasswordHistoryService {
    private final PasswordHistoryRepository history;
    private final PasswordHasher hasher;
    private final PasswordPolicyProperties policy;

    public PasswordHistoryService(PasswordHistoryRepository history, PasswordHasher hasher,
            PasswordPolicyProperties policy) {
        this.history = history;
        this.hasher = hasher;
        this.policy = policy;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replacePassword(long authenticatedUserId, char[] newPassword) {
        try {
            if (!PasswordPolicy.accepts(newPassword, policy.minLength(), policy.maxLength())) {
                throw invalidPassword();
            }
            var credential = history.lockCredential(authenticatedUserId)
                    .orElseThrow(() -> new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN));
            if ("LOCKED".equals(credential.status())) {
                Map<String, Object> extensions = credential.lockedUntil() == null ? Map.of()
                        : Map.of("lockedUntil", credential.lockedUntil());
                throw new BankingBusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED,
                        ErrorCode.AUTH_ACCOUNT_LOCKED.detail(), extensions);
            }
            if (!"ACTIVE".equals(credential.status())) {
                throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
            }
            if (hasher.matches(credential.passwordHash(), newPassword)) {
                throw invalidPassword();
            }
            for (String oldHash : history.findRecentHashes(authenticatedUserId, policy.historySize())) {
                if (hasher.matches(oldHash, newPassword)) {
                    // No history index, matching hash, or password-specific detail leaves this component.
                    throw invalidPassword();
                }
            }
            history.archiveAndReplace(credential, hasher.hash(newPassword));
        } finally {
            if (newPassword != null) {
                Arrays.fill(newPassword, '\0');
            }
        }
    }

    private static BankingBusinessException invalidPassword() {
        return new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
    }
}
