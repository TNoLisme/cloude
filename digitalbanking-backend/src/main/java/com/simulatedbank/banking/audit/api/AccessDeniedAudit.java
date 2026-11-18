package com.simulatedbank.banking.audit.api;

/** Proposed C3 access-denial contract; the audit provider writes independently. */
public interface AccessDeniedAudit {
    void record(Event event);

    record Event(String actorId, String resourceType, String resourceId, String action, String outcome,
                 String correlationId, String requestId, String reason) { }
}
