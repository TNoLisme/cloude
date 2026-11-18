package com.simulatedbank.banking.audit.api;

/**
 * Criticality class of a catalog action (design/01 §5.2).
 *
 * <ul>
 *   <li>{@code C1}/{@code C2} — mandatory, joined to the business transaction; a write failure rolls the
 *       business change back ({@link AuditModuleApi#recordInTransaction}).</li>
 *   <li>{@code C3} — security best-effort, own transaction; a write failure never changes the denial response
 *       ({@link AuditModuleApi#recordStandalone}).</li>
 *   <li>{@code C4} — operational job telemetry, own transaction of the job.</li>
 * </ul>
 */
public enum AuditClass {
    C1,
    C2,
    C3,
    C4
}
