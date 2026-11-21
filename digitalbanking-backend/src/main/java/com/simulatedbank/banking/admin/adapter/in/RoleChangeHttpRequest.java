package com.simulatedbank.banking.admin.adapter.in;

/** API-ROL-01 request body (07 §8.8.1). IDs/money stay strings on the wire per 07 §2. */
public record RoleChangeHttpRequest(String operation, String targetUserId, String roleCode,
        String privilegeCode, String reason) {
}
