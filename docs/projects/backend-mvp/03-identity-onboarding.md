# Phase 03 — Identity and Onboarding

**Status:** Detailed design ready for phase review; implementation requires separate approval.  
**Depends on:** Phases 01–02.  
**API authority:** [`../../../contracts/openapi.yaml`](../../../contracts/openapi.yaml).  
**Shared design:** [`00-roadmap.md`](./00-roadmap.md), especially migration and staff `customerId` compatibility sections.

## Goal

Implement customer and staff login identities, self-service and counter onboarding, PIN lifecycle, password recovery, session revocation, and local demo principals without changing OpenAPI.

## API operations

Implement exact operations and DTOs from OpenAPI:

- `POST /auth/register/send-otp` → `SendOtpRequest/Response`, statuses 200/400/409/429.
- `POST /auth/register` → `RegisterRequest/RegistrationResponse`, statuses 201/400/409/429.
- `POST /auth/login` → `LoginRequest/LoginResponse`, statuses 200/400/401/429; refresh cookie in `Set-Cookie`.
- `POST /auth/refresh` → `AccessTokenResponse`, statuses 200/401/403, `X-CSRF-Token`, rotated `Set-Cookie`.
- `POST /auth/logout` → 204; authenticated bearer, refresh cookie and CSRF header.
- `GET /auth/csrf` → `CsrfTokenResponse`.
- `POST /auth/recover/initiate`, `/verify` and `/confirm`; generic anti-enumeration response, OTP via SMS/Email, single-use reset token after verification.
- `GET /customers/me` → own `CustomerProfile`.
- `/customers/me/pin/setup`, `/change`, `/forgot/initiate`, `/forgot/confirm` exact request/status schemas.
- `POST /operator/customers/send-otp`, `POST /operator/customers` for OPERATOR/ADMIN.

Do not add fields, customer DOB, routes, HTTP statuses, or alternate error properties.

## Module APIs and records

`customer.api.CustomerModuleApi` owns customer profile and lifecycle:

```java
public interface CustomerModuleApi {
    RegistrationResult register(RegistrationCommand command);
    RegistrationResult createAtCounter(CounterCustomerCommand command);
    CustomerProfileView getProfile(UUID customerId);
    CustomerIdentityView findByPhone(String normalizedPhone);
    CustomerIdentityView findByEmail(String normalizedEmail);
}
public record RegistrationCommand(String phone, String email, String fullName, String address) {}
public record CounterCustomerCommand(String phone, String email, String fullName, String address) {}
public record RegistrationResult(UUID customerId, UUID userId, UUID accountId, Instant createdAt) {}
public record CustomerProfileView(UUID customerId, UUID userId, String fullName, String phone, String email, boolean pinSet, Instant createdAt) {}
```

Passwords/OTP do not cross module APIs as persisted entities; pass credentials only into the identity application use case that coordinates the transaction. `account.api.AccountModuleApi` owns default account creation:

```java
public interface AccountModuleApi {
    UUID createDefaultAccount(UUID customerId);
    AccountView getDefaultAccountForRegistration(UUID accountId);
}
```

`identity.api.IdentityModuleApi` owns credential/OTP/session/PIN:

```java
public interface IdentityModuleApi {
    OtpDispatchResult issueOtp(OtpIssueCommand command);
    OtpVerificationResult verifyOtp(OtpConsumeCommand command);
    AuthenticatedLogin login(String phone, String rawPassword);
    boolean isPinSet(UUID customerId);
    void setupPin(UUID customerId, String pin);
    void changePin(UUID customerId, String currentPin, String newPin);
    void resetPin(UUID customerId, String otp, String newPin);
    void revokeAllSessions(UUID userId);
}
```

These are module boundaries, not exact controllers. Refine records with validation and avoid raw secrets in logs, audit or persisted request snapshots.

## Transaction flows

### Self-service registration

1. Controller validates exact OpenAPI DTO and extracts phone/email/fullName/address/OTP.
2. `RegistrationApplicationService.register` normalizes phone and email; validate phone regex and email trim/lower normalization.
3. In one PostgreSQL transaction, consume REGISTRATION OTP under row lock; create `users` with CUSTOMER role, password hash; invoke customer module internal create profile; create one ACTIVE CHECKING account through account module API; create unconfigured `customer_pins` row; append required audit fact where required.
4. Unique indexes enforce phone/email/default account constraints. Translate PostgreSQL unique violation for phone/email to `409 PHONE_ALREADY_REGISTERED` / `EMAIL_ALREADY_REGISTERED`, not pre-check only.
5. Commit; return RegistrationResponse; do not create session or auto-login.

### Counter creation

1. Operator/Admin `send-otp` validates target phone; rate-limit on operator ID; dispatch `OPERATOR_CREATE_CUSTOMER` challenge to local mailbox in local/demo.
2. Create request verifies OTP and performs same atomic user/customer/account creation, initial password hash, Customer role; Operator remains actor and Customer target in audit.
3. Operator cannot choose, inspect or set PIN. Customer must set PIN after login.

### Login/session

- Normalize phone only; email is never accepted as login identifier.
- Unknown phone and wrong password both return same 401 code/body/timing class `CREDENTIALS_INVALID`. Perform dummy password hash verification for unknown user to reduce timing difference.
- Read all assigned roles. For Customer user, `customerId` is actual customer UUID. For staff/non-customer, return `customerId=userId` compatibility alias per explicit user approval; do not create Customer or account row. FE must branch on `roles` before calling customer endpoints.
- Issue access JWT expiring in 900 seconds and opaque 7-day refresh cookie per Phase 02.

### Recovery/PIN

- Recovery initiate always returns the same OpenAPI generic 200 body for registered/unregistered identifier and channel. Do not reveal lookup result in response/timing; dispatch only for match.
- Recovery verify validates and consumes OTP bound to identifier/channel/RECOVERY, then issues a single-use reset token (TTL 5 minutes). Recovery confirm accepts only reset token + new password; transaction updates password hash, consumes token and revokes all refresh sessions for user. New initiate invalidates previous OTP/reset token. Old refresh tokens fail after commit.
- PIN setup allowed only when no PIN is configured; change requires valid current PIN; forgot-PIN initiate sends PHONE OTP and confirm resets PIN after OTP verification. Five consecutive incorrect PIN checks lock for 15 minutes. Reset flow updates failure/lock counters according to baseline; document test behavior.
- PIN not returned, serialized, audited, or logged.

## Demo principals

Local/demo seed creates exactly: one OPERATOR, one AUDITOR, two CUSTOMER users; each Customer has one ACTIVE CHECKING account and configured PIN. All identities have distinct phone/email; balances are set by separate idempotent Operator seed records in Phase 04, not by rerunnable user seeding. Staff have roles only and no customer/account rows. Passwords/PINs come from required local environment/secret source; refuse demo startup if secrets missing rather than use committed default passwords. Seed upsert must not reset existing passwords, PINs, or balances unless explicitly configured for disposable test fixture.

## Data and persistence

Use tables described in canonical V1/V2 DDL in [`00-roadmap.md`](./00-roadmap.md): `users`, `user_roles`, `otp_challenges`, `refresh_sessions`, `idempotency_records`, `customer_pins`, `customers`, `accounts`. Identity/customer/account module entities stay in owning module. `CustomerModuleApi` and `AccountModuleApi` calls join caller transaction using REQUIRED propagation; do not use separate transaction or repository leakage. If package-scoped interfaces cannot maintain atomicity, revise module API design, not transaction requirement.

Email normalization: trim and lowercase using `Locale.ROOT`; store normalized unique form and return the normalized email in response/profile. Phone remains ten-digit VN string. Do not add dob.

## Acceptance and tests

- Valid/invalid/expired/reused OTP registration; no partial user/customer/account on failure.
- Concurrent duplicate phone/email race gives exactly one committed registration; stable documented 409 for loser.
- Counter creation requires role and counter OTP; exact audit actor/target; cannot set customer PIN.
- Login by phone succeeds; email login rejected; wrong phone/password responses equivalent; roles list correct.
- Staff login compatibility alias is `customerId=userId`; no customer/account rows; user-role array supports multiple roles.
- Refresh rotation/cookie, CSRF, logout and old-token revocation.
- PIN setup only once, change, reset OTP, wrong attempt lock and unlock after fixed clock 15 minutes.
- Recovery SMS and EMAIL; anti-enumeration status/body; successful reset revokes all user sessions in same transaction; invalid/unknown verify returns `OTP_INVALID`, invalid/expired/used confirm token returns `RECOVERY_TOKEN_INVALID`.
- Local mailbox OTP usable by local E2E; no log output/shared route.
- API response and error bodies validate against OpenAPI. PostgreSQL Testcontainers cover atomicity and uniqueness races.

## Implementation loop

Inspect concrete V1/V2 DDL and Phase 02 security APIs. Ask only on actual contract conflict. After approval, implement identity schema/repositories, registration transactions, counter flow, login/session integration, PIN, recovery, demo seeding; add focused tests at each slice. Review PII/secrets, correlation/audit, transaction rollback and role aliases. Update this file with evidence; stop before Phase 04 pending its approval.

**Verification record:** pending implementation approval.
