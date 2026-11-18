package com.simulatedbank.banking.identity.api;

import java.util.Optional;

/** Read-only candidate contract. The provider returns only an authorized, safe display label. */
public interface StaffDirectory {
    Optional<ActorSummary> findActor(long userId);

    record ActorSummary(long userId, String displayName) { }
}
