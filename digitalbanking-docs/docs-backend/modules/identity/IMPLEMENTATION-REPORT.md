# Identity/Auth — implementation report

## Requirement status

| Requirement | State | Implementation / evidence |
|---|---|---|
| Account identity and Argon2id | IMPLEMENTED | `IdentityService`, `IdentityCryptoConfig`, `app_user`; provisioning rejects role/user-type mismatch. |
| Opaque session and CSRF | VERIFIED | Random opaque token and CSRF secret; only hashes persist. Session authentication checks user/device state and rotates CSRF. Configured cookie name is used by controller and common authentication filter. |
| Session/device revocation | VERIFIED | Logout, role mutation, and device revocation invalidate server-side sessions. Revoked devices cannot complete a pending login OTP. |
| Lockout | VERIFIED | Expired timed lock resets counter; permanent lock remains locked. C3 audit failure runs after transaction completion and cannot roll back the lockout update. Policy remains configuration, not a newly approved policy. |
| OTP lifecycle | VERIFIED | User, purpose, request hash, expiry, method, attempts, resend state, and single-use consumption are validated under row lock. Resend rotates OTP and expiry. |
| RBAC snapshot | VERIFIED | Authorities are loaded from current role/permission/privilege records on each session authentication; role changes revoke sessions. |
| Production security audit | BLOCKED | `IdentitySecurityAuditSink` is an identity-owned outbound SPI only. Thịnh must provide the production audit adapter; no fake adapter is registered. |
| Production OTP delivery | BLOCKED | `OtpDeliveryPort` has no production notification adapter; challenge creation fails closed when delivery is unavailable. |
| FIDO and public passcode enrollment/reset | BLOCKED | Provider and canonical lifecycle contract are not finalized. No fallback implementation was added. |

## Test evidence

PostgreSQL 16 Testcontainers:

```text
./gradlew test --tests 'com.simulatedbank.banking.assigned.AssignedModulesIntegrationTest.identity*'
BUILD SUCCESSFUL

./gradlew test
BUILD SUCCESSFUL
```

Covered: stale user/device state before OTP, configured cookie, session fixation token replacement, device/session revocation, expired lock counter reset, lockout persistence when audit fails, OTP resend rotation, old-code rejection, and PostgreSQL challenge state transitions.

## Handoff / known issues

- Thịnh: bind `IdentitySecurityAuditSink.recordStandalone(SecurityEvent)` to the canonical standalone C3 audit operation.
- Notification owner: bind `OtpDeliveryPort.deliver(OtpDelivery)` to a real delivery channel.
- Phương: the only common change is replacing hard-coded `DB_SESSION` in `SessionAuthenticationFilter` with `app.security.cookie.name`; no second `SecurityFilterChain` was created.
