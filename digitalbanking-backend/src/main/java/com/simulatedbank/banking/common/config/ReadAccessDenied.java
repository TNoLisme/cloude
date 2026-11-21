package com.simulatedbank.banking.common.config;

/** Safe metadata for a denied, authenticated Customer or Account read. */
public record ReadAccessDenied(long actorUserId, String actorRoles, String entityType,
        String entityId, String correlationId) {}
