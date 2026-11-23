package com.simulatedbank.banking.transfer.application;

/** Consumer seam for the server-side staff scope policy (API contracts §3.6). */
@FunctionalInterface
public interface TransferStaffScope {
    /** True only if this staff user is allowed to read the transfer. Provider errors propagate. */
    boolean allowsRead(long staffUserId, long transferId);
}
