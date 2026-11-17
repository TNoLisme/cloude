package com.simulatedbank.banking.identity.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Identity-only port; all operations JOIN the credential-change transaction. */
public interface PasswordHistoryRepository {
    record LockedCredential(long userId, String passwordHash, String status, Instant lockedUntil) {
        @Override
        public String toString() {
            return "LockedCredential[redacted]";
        }
    }

    Optional<LockedCredential> lockCredential(long userId);

    List<String> findRecentHashes(long userId, int count);

    void archiveAndReplace(LockedCredential credential, String newHash);
}
