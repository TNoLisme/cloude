# API & Team Contract

> **Contract status:** MVP implementation baseline. `../../contracts/openapi.yaml` is normative for paths, schemas, status codes and security schemes. This guide adds FE call sequences, state behavior and implementation rules. If this guide and OpenAPI differ, update both in same change; do not guess in code.

## 1. Frontend integration summary

### Base URL

- Local backend: `http://localhost:8080/api/v1`
- FE reads base URL from `VITE_API_BASE_URL`; do not hard-code deployment host.
- Local dev and MVP demo use same-origin Vite proxy/reverse proxy at `/api`; production FE uses same-origin ingress by default.
- Local same-origin proxy is default; if different origin is required, set exact allowlist, credentials support and CSRF validation. Wildcard CORS with credentials is forbidden.
- All successful request/response bodies are JSON except `204 No Content`.

### Common request headers

| Header | Required | Meaning |
|---|---:|---|
| `Accept: application/json` | Yes | FE expects JSON response. |
| `Content-Type: application/json` | When body exists | JSON request payload. |
| `Authorization: Bearer <accessToken>` | Authenticated APIs | Access token issued by login/refresh. |
| `X-CSRF-Token: <csrfToken>` | Cookie-authenticated refresh/logout | CSRF token issued by auth bootstrap; required with refresh cookie. |
| `X-Correlation-Id: <uuid>` | Optional | FE may generate per user action; BE returns accepted value. FE retains it for support/debug display only. |
| `Idempotency-Key: <uuid>` | `POST /transfers`, `POST /operator/accounts/{accountId}/seed-balance` | Stable key per logical money mutation. Reuse same key after network timeout. |

Do not send actor ID, role, owner ID or balance in headers/body as proof of authority. BE derives actor and role from validated access token.

### Common response headers

- `X-Correlation-Id`: request trace identifier.
- `Cache-Control: no-store` for auth responses and private financial data unless caching policy is explicitly revised.
- `Retry-After`: optional on `429` or temporary `503` responses.

### FE API client contract

Create one typed client in `frontend/src/api/`:

- Reads `VITE_API_BASE_URL`.
- Adds bearer token for authenticated requests.
- Adds JSON headers only when sending JSON body.
- Adds or forwards correlation ID.
- Parses `Problem` response into one typed `ApiError` preserving `status`, `code`, `detail`, `fieldErrors`, and `correlationId`.
- On `401`, clear in-memory session and route to login. Do not retry unsafe mutations automatically.
- On `403`, show access denied; do not infer resource absence.
- On `409` transfer/seed idempotency replay, reconcile returned original result. On unknown network failure, retain key and exact payload for safe retry.
- On mutation `429/503`, honor `Retry-After`; only retry when endpoint semantics are safe and preserve idempotency key.
- FE never calculates authoritative balance or decides access rights.

## 2. Contract-first workflow

Contract-first development is mandatory:

1. `../../contracts/openapi.yaml` defines exact paths, schemas, required fields, headers and status codes.
2. FE generates types, client functions and MSW mock fixtures from `../../contracts/openapi.yaml`.
3. BE generates server stubs/validation models or implements strict schema tests against the same OpenAPI file.
4. Contract diffs must be approved before implementation PRs are merged.

`../../contracts/openapi.yaml` is committed with the repo.

## 3. API-wide conventions

- Prefix: `/api/v1`.
- IDs: UUID strings.
- Timestamp: RFC 3339 UTC ending in `Z`.
- Money: integer string, e.g. `"50000"` or `"2000"`; never JSON float or decimal fraction. MVP uses simulated VND only, scale 0. Transfer amount minimum `2,000` VNĐ, maximum `10,000,000` VNĐ; seed balance maximum `100,000,000` VNĐ (minimum > 0). Values are configurable only by backend environment, not client request.
- `currency` must be `VND` in MVP and match account currency. Contract versioning is required before adding another currency/scale.
- Primary Identifiers: `phone` (10 chữ số định dạng Việt Nam, duy nhất) và `email` (duy nhất). Cả hai đều bắt buộc khi tạo tài khoản.
- Login credential: Đăng nhập bằng `phone` + `password`.
- Transaction PIN: Mã PIN giao dịch 6 chữ số, bắt buộc thiết lập ở lần đầu đăng nhập (`isPinSet: false`) trước khi thực hiện chuyển tiền.
- Transfer Authentication:
  - Giao dịch $\le$ `5,000,000` VNĐ: Xác thực bằng Mã PIN.
  - Giao dịch $>$ `5,000,000` VNĐ: Xác thực bằng cả 2 yếu tố: Mã PIN và Mã OTP SMS gửi về SĐT đã đăng ký.
- Account Recovery: Khôi phục mật khẩu tự phục vụ qua mã OTP gửi tới 1 trong 2 kênh tùy chọn (`SMS` hoặc `EMAIL`).
- Enum values: uppercase snake case.
- Unknown response fields: FE must ignore. BE may add optional response fields without breaking clients.
- Request schema: BE rejects unknown or invalid required fields with `400 VALIDATION_ERROR`; OpenAPI `additionalProperties: false` for mutation request objects.
- Empty list: `200` with `items: []`, not `404`.
- List response uses `{ "items": [], "nextCursor": null }`; `limit` defaults to `20`, range `1..100`.
- Date filters are inclusive UTC instants: `from` <= event time < `to`.
- No credentials, hashes, internal keys or unmasked sensitive data in API responses.

## 4. Roles and access rules

| API group | Anonymous | Customer | Operator | Auditor | Admin |
|---|---:|---:|---:|---:|---:|
| Register/login/recover | Yes | Yes | Yes | Yes | Yes |
| `/customers/me`, PIN setup/change, own accounts/transfers | No | Own data | No | No | No by default |
| Operator counter customer creation, exact customer lookup | No | No | Yes | No | Yes |
| Operator account block/unblock | No | No | Yes | No | Yes |
| Operator seed balance | No | No | Yes | No | Yes |
| Audit query | No | No | No | Yes | Yes |
| Risk-flag query | No | No | Yes | Yes | Yes |
| Health | Deployment-configured | Deployment-configured | Deployment-configured | Deployment-configured | Deployment-configured |

Roles are not interchangeable. BE checks resource ownership for every Customer request. Admin does not bypass money invariants.

## 5. Endpoint catalog

| Method and path | Access | Purpose / success |
|---|---|---|
| `POST /auth/register/send-otp` | Anonymous | Dispatch 6-digit registration OTP to phone number; `200`. |
| `POST /auth/register` | Anonymous | Register with phone OTP; creates active customer & default checking account immediately; `201`. |
| `POST /auth/login` | Anonymous | Login with phone + password; returns tokens, user info & `isPinSet` flag; `200`. |
| `POST /auth/refresh` | Refresh token | Rotate refresh token cookie and return new access token plus `UserSummary`; `200`. |
| `POST /auth/logout` | Authenticated | Revoke refresh session and clear cookie; `204`. |
| `GET /auth/csrf` | Anonymous/session | Issue CSRF token for cookie-authenticated refresh/logout; `200`. |
| `POST /auth/recover/initiate` | Anonymous | Request password recovery OTP via chosen channel (`SMS` or `EMAIL`); always generic `200` (anti-enumeration). |
| `POST /auth/recover/verify` | Anonymous | Verify OTP; on success return a one-time reset token valid for 300 seconds. Unknown identifier and invalid OTP = `400 OTP_INVALID`. |
| `POST /auth/recover/confirm` | Anonymous | Accept reset token and new password; revoke all refresh sessions; `200`. Invalid/expired/used token = `400 RECOVERY_TOKEN_INVALID`. |
| `GET /customers/me` | Customer | Return own profile including phone, email, and `isPinSet`; `200`. |
| `POST /customers/me/pin/setup` | Customer | Mandatory first-login PIN configuration (6 digits); `200`. |
| `POST /customers/me/pin/change` | Customer | Change PIN by providing valid current PIN; `200`. |
| `POST /customers/me/pin/forgot/initiate` | Customer | Send reset OTP to registered phone number for forgotten PIN; `200`. |
| `POST /customers/me/pin/forgot/confirm` | Customer | Verify phone OTP and set new PIN; `200`. |
| `POST /operator/customers/send-otp` | Operator/Admin | Send verification OTP to customer present phone at counter; `200`. |
| `POST /operator/customers` | Operator/Admin | Provision customer and active account at counter with verified OTP and initial password; `201`. |
| `GET /operator/customers?phone=` or `?email=` | Operator/Admin | Exact-match lookup (exactly one filter, no listing); returns customer + accounts; audited; `200`. |
| `POST /operator/accounts/{accountId}/block` | Operator/Admin | `ACTIVE` → `BLOCKED` with reason; idempotent; audited; `200`. |
| `POST /operator/accounts/{accountId}/unblock` | Operator/Admin | `BLOCKED` → `ACTIVE` with reason; idempotent; audited; `200`. |
| `GET /accounts` | Customer | List own accounts/balances; `200`. |
| `GET /accounts/{accountId}` | Owner, Operator/Admin by policy | Read account details; `200`. |
| `POST /operator/accounts/{accountId}/seed-balance` | Operator/Admin | Credit demo opening funds exactly once per request key; `201`. |
| `POST /recipients/resolve` | Customer | Resolve full account number into masked recipient confirmation; `200`. |
| `POST /transfers` | Customer | Initiate transfer with PIN. If <= 5M, commits immediately (`201`). If > 5M, triggers phone OTP and challenge (`200 AWAITING_OTP`). |
| `POST /transfers/{transferId}/confirm-otp` | Customer (source owner) | Complete step-up transfer (> 5M) with phone OTP; `201` new, `200` replay. |
| `GET /transfers` | Customer | List transfers where caller owns source or destination account; filter by `status`; `200`. |
| `GET /transfers/{transferId}` | Participant; Operator/Auditor/Admin by policy | Read transfer details and current status (`AWAITING_OTP`, `COMPLETED`, `EXPIRED`, `FAILED`); `200`. |
| `GET /audit-events` | Auditor/Admin | Search allowed audit events; `200`. |
| `GET /operator/risk-flags` | Operator/Auditor/Admin | List read-only suspicious flags; `200`. |
| `GET /health` | Platform | Liveness/readiness; `200` or `503`. |

## 6. Endpoint details and FE call behavior

### 6.1 Self-service Registration via Phone OTP

#### Step 1: Send registration OTP
`POST /api/v1/auth/register/send-otp`

Request:
```json
{
  "phone": "0912345678",
  "purpose": "REGISTRATION"
}
```

Response `200`:
```json
{
  "phone": "0912345678",
  "expiresInSeconds": 120,
  "message": "OTP sent successfully"
}
```

#### Step 2: Submit registration with OTP
`POST /api/v1/auth/register`

Request:
```json
{
  "phone": "0912345678",
  "email": "alex@example.test",
  "password": "Example-only-password",
  "fullName": "Alex Example",
  "otp": "849201",
  "address": "123 Cau Giay, Hanoi"
}
```

Response `201`:
```json
{
  "customerId": "9bd332f8-a718-4ab9-9fde-21b1ff1fa781",
  "phone": "0912345678",
  "email": "alex@example.test",
  "fullName": "Alex Example",
  "account": {
    "accountId": "a452a8cf-59f6-46f2-85a6-f2b8fd207db9",
    "accountNumberMasked": "••••4821",
    "accountType": "CHECKING",
    "status": "ACTIVE",
    "balance": "0",
    "currency": "VND",
    "openedAt": "2026-09-28T10:00:00Z"
  },
  "createdAt": "2026-09-28T10:00:00Z"
}
```

FE behavior: Account is active immediately. Direct user to Login screen. Duplicate phone returns `409 PHONE_ALREADY_REGISTERED`; duplicate email returns `409 EMAIL_ALREADY_REGISTERED`; invalid/expired OTP returns `400 OTP_INVALID`.

**Accepted risk (registration enumeration):** `send-otp` and `register` intentionally return `409 PHONE_ALREADY_REGISTERED` / `EMAIL_ALREADY_REGISTERED` for clear registration UX, which allows checking whether a phone/email is registered. Mitigation: strict rate limit per phone and per IP on `send-otp`, and `register` is only reachable after a valid phone OTP. Recovery endpoints do **not** share this behavior. Post-MVP option: `send-otp` always `200` and send "already registered" SMS instead of OTP.

### 6.2 Login and session refresh

`POST /api/v1/auth/login`

Request:
```json
{
  "phone": "0912345678",
  "password": "Example-only-password"
}
```

Response `200`:
```json
{
  "accessToken": "<opaque-example-jwt>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": {
    "userId": "ad1f67a1-6a9f-4c11-a3c4-bc01f6ac4350",
    "customerId": "9bd332f8-a718-4ab9-9fde-21b1ff1fa781",
    "displayName": "Alex Example",
    "phone": "0912345678",
    "email": "alex@example.test",
    "roles": ["CUSTOMER"],
    "isPinSet": false
  }
}
```

FE behavior:
1. Store access token in memory.
2. If `user.isPinSet === false`, immediately route customer to **Mandatory PIN Setup** screen before enabling transfer functions.
3. If `user.isPinSet === true`, route to customer dashboard.

`POST /api/v1/auth/refresh`: sends refresh cookie and `X-CSRF-Token`; returns `200` with the same response shape as login (`accessToken`, `tokenType`, `expiresIn`, `user: UserSummary`) and rotates the HttpOnly refresh cookie. FE uses the returned `user` to restore identity, roles and workspace after a page reload; no JWT decoding or persisted access token is needed. On `401 SESSION_EXPIRED`, clear in-memory session state and require login. A rejected refresh must not rotate the cookie.
`POST /api/v1/auth/logout`: sends refresh cookie and bearer token; revokes session; `204`.

### 6.3 Account Recovery (Forgot Password via SMS or Email OTP)

#### Step 1: Request recovery OTP
`POST /api/v1/auth/recover/initiate`

Request:
```json
{
  "identifier": "0912345678",
  "channel": "SMS"
}
```
*(Hoặc `identifier`: `"alex@example.test"`, `channel`: `"EMAIL"`)*

Response `200`:
```json
{
  "identifier": "0912345678",
  "channel": "SMS",
  "expiresInSeconds": 120,
  "message": "If the information is registered, an OTP has been sent to the selected channel."
}
```

Anti-enumeration: status and body are identical whether or not the identifier is registered; no match → no OTP. Rate limit (`429`) applies equally to both cases. Uniform response timing and asynchronous dispatch remain a security acceptance target to verify separately; do not assume the current simulated sender proves them.

#### Step 2: Verify OTP
`POST /api/v1/auth/recover/verify`

Request:
```json
{
  "identifier": "0912345678",
  "channel": "SMS",
  "otp": "849201"
}
```

Response `200`:
```json
{
  "resetToken": "<opaque-one-time-token>",
  "expiresInSeconds": 300
}
```

Wrong/expired/used OTP or unknown identifier → `400 OTP_INVALID`. OTP attempts are persisted; the fifth wrong attempt invalidates the OTP. Independent IP and normalized-identifier buckets each allow 5 requests per 300 seconds (`429 RATE_LIMITED` if either is exhausted). A successful verify consumes the OTP and issues a new reset token; it does not change the password.

#### Step 3: Set new password
`POST /api/v1/auth/recover/confirm`

Request:
```json
{
  "resetToken": "<opaque-one-time-token>",
  "newPassword": "NewSecurePassword123!"
}
```

Response `200`:
```json
{
  "message": "Password reset successfully and active sessions revoked"
}
```

Recovery rules:
- `channel = SMS` looks up the registered phone; `channel = EMAIL` looks up the registered email. An identifier not registered on the selected channel receives the generic initiate response and `400 OTP_INVALID` at verify; malformed request syntax may return `400 VALIDATION_ERROR`.
- OTP: 6 digits, TTL `120` seconds, single use, bound to `identifier + channel + purpose=RECOVERY`. A new initiate invalidates the previous recovery OTP and any active reset token for that account. Initiate is rate-limited (`429 RATE_LIMITED`).
- Reset token: cryptographically random, stored only as a hash, bound to the user, valid for 300 seconds, one-time use. FE holds it only in memory; no URL, browser storage, analytics or logs. Reload after verify requires starting recovery again. A new verify invalidates prior active reset tokens for that user.
- On success, BE consumes the reset token, hashes and stores the new password, and **revokes all refresh tokens of the user** in one transaction. Existing access tokens expire naturally within their short TTL. The user returns to login.
- Errors: verify `400 OTP_INVALID` (wrong/expired/used OTP **or unregistered identifier**); confirm `400 RECOVERY_TOKEN_INVALID` (missing/wrong/expired/used token); `400 VALIDATION_ERROR` for request/password syntax; verify/initiate can return `429 RATE_LIMITED`. Recovery endpoints never return `404`.
- Audit records initiate, verify and confirm without OTP, password or reset token. Unknown identifiers never appear in public error responses.

FE behavior: "Quên mật khẩu" → choose channel (SMS/Email) and enter identifier → generic initiate response → OTP screen → call verify → only on `200` show new-password form (entered twice locally) → call confirm with reset token and newPassword → clear in-memory session and route to Login. Never treat six typed digits alone as verified.

### 6.4 Mandatory First-Login PIN Setup & PIN Management

#### First-login Setup PIN
`POST /api/v1/customers/me/pin/setup`

Request:
```json
{
  "pin": "123456",
  "confirmPin": "123456"
}
```
Response `200`: Transaction PIN configured. Updates `isPinSet` to `true`.

#### Change PIN (when current PIN is known)
`POST /api/v1/customers/me/pin/change`

Request:
```json
{
  "currentPin": "123456",
  "newPin": "654321",
  "confirmNewPin": "654321"
}
```
Response `200`: PIN changed successfully. Wrong current PIN returns `400 PIN_INVALID`. 5 consecutive failed attempts lock PIN temporarily for 15 minutes.

#### Forgot PIN
- `POST /api/v1/customers/me/pin/forgot/initiate`: Sends 6-digit OTP to registered phone number.
- `POST /api/v1/customers/me/pin/forgot/confirm`:
Request:
```json
{
  "otp": "849201",
  "newPin": "654321",
  "confirmNewPin": "654321"
}
```
Response `200`: PIN reset successfully.

### 6.5 Operator Counter Customer Creation

#### Step 1: Send OTP to customer phone at counter
`POST /api/v1/operator/customers/send-otp`

Request:
```json
{
  "phone": "0987654321"
}
```
Response `200`: OTP dispatched to customer phone.

#### Step 2: Provision customer & account with verified OTP
`POST /api/v1/operator/customers`

Request:
```json
{
  "phone": "0987654321",
  "email": "counter.customer@example.test",
  "fullName": "Counter Customer",
  "initialPassword": "InitialPassword123!",
  "otp": "123456",
  "address": "456 Ba Trieu, Hanoi"
}
```

Response `201`: Returns customer ID and active account details. Customer logs in using phone + initial password, and must configure transaction PIN on first login.

Counter rules: `phone` and `email` are both required and UNIQUE across customers — the counter screen must collect the customer's email in addition to phone. Duplicate phone returns `409 PHONE_ALREADY_REGISTERED`; duplicate email returns `409 EMAIL_ALREADY_REGISTERED`; wrong/expired OTP returns `400 OTP_INVALID`. The action is audited with the Operator as actor.

#### Exact customer lookup at counter
`GET /api/v1/operator/customers?phone=0987654321` (or `?email=counter.customer@example.test`)

- Exactly one of `phone` / `email` is required. No filter, both filters, partial match or wildcard → `400 VALIDATION_ERROR`. There is no customer listing endpoint.
- Not found → `404 CUSTOMER_NOT_FOUND` (acceptable: caller is an authenticated, audited Operator; not an anonymous endpoint).
- Every lookup writes an audit event (`CUSTOMER_LOOKUP`, actor = Operator, filter type only, not the raw value). Rate limited per Operator.

Response `200`:
```json
{
  "customerId": "9bd332f8-a718-4ab9-9fde-21b1ff1fa781",
  "fullName": "Counter Customer",
  "phone": "0987654321",
  "email": "counter.customer@example.test",
  "isPinSet": true,
  "createdAt": "2026-09-28T10:00:00Z",
  "accounts": [
    {
      "accountId": "a452a8cf-59f6-46f2-85a6-f2b8fd207db9",
      "accountNumberMasked": "••••4821",
      "accountType": "CHECKING",
      "status": "ACTIVE",
      "balance": "5000000",
      "currency": "VND",
      "openedAt": "2026-09-28T10:00:00Z"
    }
  ]
}
```

FE behavior: Operator uses returned `accountId` for seed balance and block/unblock.

### 6.6 Accounts and balances

`GET /api/v1/accounts`

Response `200`:
```json
{
  "items": [
    {
      "accountId": "a452a8cf-59f6-46f2-85a6-f2b8fd207db9",
      "accountNumberMasked": "••••4821",
      "accountType": "CHECKING",
      "status": "ACTIVE",
      "balance": "5000000",
      "currency": "VND",
      "openedAt": "2026-09-28T10:15:00Z"
    }
  ],
  "nextCursor": null
}
```

`GET /api/v1/accounts/{accountId}` returns the same account shape. Customer may only query own account; unknown and unauthorized accounts both return `404 ACCOUNT_NOT_FOUND` (concealment).

FE behavior: account list is server state. Refetch after seed/transfer/block events; never persist balance as authoritative state in Zustand.

#### Operator block / unblock account
`POST /api/v1/operator/accounts/{accountId}/block` and `POST /api/v1/operator/accounts/{accountId}/unblock`

Request:
```json
{ "reason": "Customer reported lost phone at counter" }
```

Response `200`: updated `Account` (`status: "BLOCKED"` or `"ACTIVE"`).

Rules:
- State machine: `ACTIVE ⇄ BLOCKED`; `CLOSED` is terminal → `409 ACCOUNT_NOT_ELIGIBLE`.
- Idempotent: blocking an already `BLOCKED` (or unblocking an already `ACTIVE`) account returns `200` with current state, no new mutation and no duplicate audit event.
- Status change takes the account row lock, so it serializes with in-flight transfers.
- Effect of `BLOCKED`: transfer from/to the account → `409 ACCOUNT_NOT_ELIGIBLE`; `POST /recipients/resolve` → `404 RECIPIENT_NOT_AVAILABLE`; seed balance → `409 ACCOUNT_NOT_ELIGIBLE`; pending `AWAITING_OTP` transfer involving it becomes `FAILED` (`failureCode: ACCOUNT_NOT_ELIGIBLE`) at confirm. Balance and history remain readable.
- Audited with actor, account, old/new status and reason.

### 6.7 Operator seed balance

`POST /api/v1/operator/accounts/{accountId}/seed-balance`

Headers: `Authorization`, `Idempotency-Key: <uuid>`.

Request:
```json
{
  "amount": "10000000",
  "currency": "VND",
  "reference": "DEMO-OPENING-BALANCE"
}
```

Response `201`:
```json
{
  "seedTransactionId": "cc168d4a-12cd-4f0f-8c63-55b90ad1e310",
  "accountId": "a452a8cf-59f6-46f2-85a6-f2b8fd207db9",
  "amount": "10000000",
  "currency": "VND",
  "balanceAfter": "10000000",
  "createdAt": "2026-09-28T10:20:00Z"
}
```

FE behavior: Operator confirms action in UI and labels it "Demo funds". Update displayed balance only from response/refetch. Retry after network timeout with same key. Seed follows the same idempotency rules as transfer (§6.9).

Errors: `404 ACCOUNT_NOT_FOUND`; `409 ACCOUNT_NOT_ELIGIBLE`, `IDEMPOTENCY_KEY_REUSED`; `400 AMOUNT_INVALID` or `CURRENCY_MISMATCH`.

### 6.8 Resolve recipient

`POST /api/v1/recipients/resolve`

Request:
```json
{ "accountNumber": "1002003004" }
```

Response `200`:
```json
{
  "accountId": "3285a3ab-4273-4ab2-987e-62bfc062a456",
  "accountNumberMasked": "••••3004",
  "recipientDisplayName": "Bob Counterparty",
  "currency": "VND"
}
```

### 6.9 Create internal transfer (Tiered 2FA)

`POST /api/v1/transfers`

Headers:

```http
Authorization: Bearer <accessToken>
Content-Type: application/json
Idempotency-Key: 3be4ec14-c219-4e50-a486-15dcc4d4a879
X-Correlation-Id: 47ec3533-9b7f-408c-a143-ab3918e56446
```

Request:
```json
{
  "sourceAccountId": "a452a8cf-59f6-46f2-85a6-f2b8fd207db9",
  "destinationAccountId": "3285a3ab-4273-4ab2-987e-62bfc062a456",
  "amount": "50000",
  "currency": "VND",
  "pin": "123456",
  "memo": "Shared lunch"
}
```

#### Case A: Amount <= 5,000,000 VNĐ
PIN verified. Transfer commits atomically.

Response `201`:
```json
{
  "transferId": "7a2327ae-b132-44a5-b682-982842717213",
  "status": "COMPLETED",
  "sourceAccountId": "a452a8cf-59f6-46f2-85a6-f2b8fd207db9",
  "destinationAccountId": "3285a3ab-4273-4ab2-987e-62bfc062a456",
  "amount": "50000",
  "currency": "VND",
  "memo": "Shared lunch",
  "createdAt": "2026-09-28T10:30:00Z",
  "completedAt": "2026-09-28T10:30:00Z"
}
```

#### Case B: Amount > 5,000,000 VNĐ (ví dụ 6,000,000 VNĐ)
PIN verified. Server dispatches SMS OTP to customer phone and returns challenge. Balances remain unchanged.

Commit challenge and transfer before dispatch. Dispatch failure/timeout returns `503 SERVICE_UNAVAILABLE`; a new compensating transaction changes only `AWAITING_OTP` to `FAILED` with `failureCode: OTP_DISPATCH_FAILED` and invalidates its challenge. Preserve terminal states if confirm/expiry won the race. Same-key replay returns current state without resending OTP; a new intentional transfer requires a new key. A crash or compensation failure may leave the transfer pending until expiry; timeout alone does not prove non-delivery or rollback.

Response `200`:
```json
{
  "transferId": "7a2327ae-b132-44a5-b682-982842717213",
  "status": "AWAITING_OTP",
  "expiresAt": "2026-09-28T10:32:00Z",
  "message": "Transfer exceeds 5,000,000 VND. OTP sent to registered phone number.",
  "expiresInSeconds": 120
}
```

`AWAITING_OTP` creates a transfer record but **does not debit, credit or reserve funds**. Balance is checked again at confirm time (§6.10).

`COMPLETED` response means source debit and destination credit committed. A rejected business operation (wrong PIN, insufficient funds, ineligible account, validation) returns Problem Details and no balance mutation.

FE behavior:

1. Validate required fields/format for UX; BE validation remains authoritative.
2. Generate idempotency key once when user confirms submission.
3. Disable duplicate submit while request is in flight, but do not rely on UI lock for correctness.
4. On `201`, show confirmation and refetch balances/history.
5. On `200` with `status: AWAITING_OTP`, render OTP modal with countdown from `expiresInSeconds`.
6. On timeout/network error, keep same key and exact request body. Retry or reconcile via `GET /transfers/{transferId}`; do not generate a new key automatically.
7. On `409 INSUFFICIENT_FUNDS`, show form error and refresh balance.
8. On `409 IDEMPOTENCY_KEY_REUSED`, do not retry with that key and changed body; start a new intentional operation with a new key only after user action.
9. On `400 PIN_INVALID` / `403 PIN_LOCKED`, show PIN error / lock duration; a corrected PIN is a new request with a new key.

Transfer and seed idempotency rules:

- Same actor + same operation + same key + same canonical payload: return original logical result; no second balance mutation. First request returns `201` (or `200` challenge); replay returns `200` with `Idempotency-Replayed: true` response header.
- Replay of a step-up transfer returns its **current state**: while `AWAITING_OTP`, the original challenge with remaining `expiresInSeconds` (**no new OTP is sent**); after that, the `Transfer` with `COMPLETED`, `EXPIRED` or `FAILED`.
- Same key but different payload: `409 IDEMPOTENCY_KEY_REUSED`.
- Concurrent duplicates: exactly one transfer/seed record and one balance mutation (enforced by DB unique constraint on actor + operation + key).
- Canonical payload hash excludes `pin` (secret); PIN is verified on every call, including replay.
- Retain keys for at least 24 hours; keep financial record under standard demo retention. Do not purge key while result may need reconciliation.
- Business rejection does not reserve a successful result; corrected request requires new key.
- Key length: 16..128 printable ASCII characters; generated UUID recommended.

### 6.10 Confirm Step-up Transfer with OTP

`POST /api/v1/transfers/{transferId}/confirm-otp`

Request:
```json
{
  "otp": "849201"
}
```

Response `201`:
```json
{
  "transferId": "7a2327ae-b132-44a5-b682-982842717213",
  "status": "COMPLETED",
  "sourceAccountId": "a452a8cf-59f6-46f2-85a6-f2b8fd207db9",
  "destinationAccountId": "3285a3ab-4273-4ab2-987e-62bfc062a456",
  "amount": "6000000",
  "currency": "VND",
  "memo": "Large purchase",
  "createdAt": "2026-09-28T10:30:00Z",
  "completedAt": "2026-09-28T10:31:00Z"
}
```

Step-up transfer state machine:

```text
POST /transfers (> 5M, PIN ok) ──► AWAITING_OTP ──valid OTP + re-validation ok──► COMPLETED
                                        │ 120s elapsed ─────────────────────────► EXPIRED
                                        │ 5th wrong OTP ────────────────────────► FAILED (OTP_ATTEMPTS_EXCEEDED)
                                        └ re-validation fails at confirm ───────► FAILED (INSUFFICIENT_FUNDS | ACCOUNT_NOT_ELIGIBLE)
```

Confirm rules:
- Only the source-account owner may confirm; others get `404 TRANSFER_NOT_FOUND`.
- In one DB transaction: lock both account rows (ascending ID), re-validate status/currency/available balance **under lock**, debit, credit, set `COMPLETED`, write audit. Risk rules run after commit, same as direct transfers.
- Idempotent by `transferId` (no `Idempotency-Key` needed): confirm after `COMPLETED` returns `200` with the same `Transfer` and `Idempotency-Replayed: true`, no second mutation. Safe to retry after client timeout.
- Wrong OTP → `400 OTP_INVALID` (status stays `AWAITING_OTP`); 5th wrong OTP → `409 STATE_CONFLICT`, status `FAILED`.
- Confirm on `EXPIRED` → `409 TRANSFER_EXPIRED`; on `FAILED` → `409 STATE_CONFLICT`. Customer must start a new transfer with a new key.
- Re-validation failure → `409 INSUFFICIENT_FUNDS` / `409 ACCOUNT_NOT_ELIGIBLE`, status `FAILED`, balances unchanged.
- Expiry is evaluated lazily on read/confirm (`now > expiresAt`) and may also be persisted by a scheduled job; either way no balance is touched.

### 6.11 Transfer history and detail

`GET /api/v1/transfers?limit=20&cursor=<opaque>&status=COMPLETED&from=<RFC3339>&to=<RFC3339>`

- All query parameters optional. `status`: `AWAITING_OTP | COMPLETED | EXPIRED | FAILED`.
- `from` inclusive; `to` exclusive; reject `from >= to` with `400 INVALID_DATE_RANGE`.
- Visibility: source-account owner sees own transfers in **every** status; destination-account owner sees only `COMPLETED` incoming transfers (a pending/expired/failed transfer never moved money to them).
- Stable order: `createdAt` descending, then `transferId` descending.
- `counterpartyDisplayName` is limited display data; never email, phone or address.

Response `200`:
```json
{
  "items": [
    {
      "transferId": "7a2327ae-b132-44a5-b682-982842717213",
      "direction": "OUTGOING",
      "status": "COMPLETED",
      "counterpartyAccountMasked": "••••7710",
      "counterpartyDisplayName": "Bob Counterparty",
      "amount": "50000",
      "currency": "VND",
      "memo": "Shared lunch",
      "createdAt": "2026-09-28T10:30:00Z"
    }
  ],
  "nextCursor": null
}
```

#### Transfer detail and status (Transaction status use case)

`GET /api/v1/transfers/{transferId}` returns the full `Transfer` with current `status`:

| `status` | Meaning | Balance moved? | Extra fields |
|---|---|---|---|
| `AWAITING_OTP` | Step-up transfer waiting for SMS OTP | No | `expiresAt` |
| `COMPLETED` | Debit + credit committed | Yes | `completedAt` |
| `EXPIRED` | OTP not confirmed within 120s | No | — |
| `FAILED` | 5 wrong OTP, re-validation failed at confirm, or OTP dispatch failed | No | `failureCode` |

Example (`FAILED`):
```json
{
  "transferId": "7a2327ae-b132-44a5-b682-982842717213",
  "status": "FAILED",
  "failureCode": "INSUFFICIENT_FUNDS",
  "sourceAccountId": "a452a8cf-59f6-46f2-85a6-f2b8fd207db9",
  "destinationAccountId": "3285a3ab-4273-4ab2-987e-62bfc062a456",
  "amount": "6000000",
  "currency": "VND",
  "createdAt": "2026-09-28T10:30:00Z"
}
```

Status is the committed DB state; `COMPLETED` is never shown before commit and terminal states (`COMPLETED`, `EXPIRED`, `FAILED`) never change. Destination owner gets `404 TRANSFER_NOT_FOUND` for non-`COMPLETED` transfers.

FE behavior: detail route loads directly by ID (no dependency on history cache). Use it to reconcile after a timeout and to refresh the OTP modal state. Handle missing/unauthorized as not found.

### 6.12 Audit events and risk flags

`GET /api/v1/audit-events?limit=20&cursor=<opaque>&eventType=TRANSFER_COMPLETED&actorId=<uuid>&from=<RFC3339>&to=<RFC3339>`

Auditor/Admin only. Each item includes `eventId`, `eventType`, `actorId` (may be system), `targetType`, `targetId`, `outcome`, `occurredAt`, `correlationId`, and redacted `summary`. No raw token, password, PIN, OTP, secret or full sensitive request payload. No edit/delete endpoint exists.

`GET /api/v1/operator/risk-flags?ruleId=<string>&transferId=<uuid>&from=<RFC3339>&to=<RFC3339>&limit=20&cursor=<opaque>`

Operator/Auditor/Admin only; read-only flags with `flagId`, `transferId`, `ruleId`, `ruleVersion`, `reason`, `detectedAt`. Review workflow is post-MVP.

## 7. Standard error contract

All errors use `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Business rule conflict",
  "status": 409,
  "detail": "Available balance is not sufficient for this transfer.",
  "instance": "/api/v1/transfers",
  "code": "INSUFFICIENT_FUNDS",
  "correlationId": "47ec3533-9b7f-408c-a143-ab3918e56446",
  "fieldErrors": []
}
```

`fieldErrors` is optional array `{ "field": "amount", "code": "MINIMUM", "message": "Amount must be greater than zero." }`. FE branches on `code`, not localized `title/detail`.

| HTTP | Stable codes (minimum) | FE behavior |
|---|---|---|
| `400` | `VALIDATION_ERROR`, `AMOUNT_INVALID`, `CURRENCY_MISMATCH`, `INVALID_DATE_RANGE`, `OTP_INVALID`, `PIN_INVALID` | Show field/form errors; do not retry unchanged request. |
| `401` | `AUTHENTICATION_REQUIRED`, `SESSION_EXPIRED`, `CREDENTIALS_INVALID` | Refresh once if eligible; otherwise clear session/login. |
| `403` | `FORBIDDEN`, `PIN_LOCKED` | Show access denied / lockout duration; do not retry. |
| `404` | `CUSTOMER_NOT_FOUND`, `ACCOUNT_NOT_FOUND`, `TRANSFER_NOT_FOUND`, `RECIPIENT_NOT_AVAILABLE` | Show not found; avoid exposing whether protected resource exists. |
| `409` | `PHONE_ALREADY_REGISTERED`, `EMAIL_ALREADY_REGISTERED`, `ACCOUNT_NOT_ELIGIBLE`, `INSUFFICIENT_FUNDS`, `IDEMPOTENCY_KEY_REUSED`, `TRANSFER_EXPIRED`, `STATE_CONFLICT` | Show business conflict; refresh affected server state. |
| `429` | `RATE_LIMITED` | Respect `Retry-After`; never rapid-loop. |
| `500` | `INTERNAL_ERROR` | Show generic message + correlation ID; no unsafe automatic mutation retry. |
| `503` | `SERVICE_UNAVAILABLE` | Show temporary unavailable; safe retry only; transfer retry must reuse key. |

Validation framework details and stack traces never reach FE. BE returns generic `500` with correlation ID and logs diagnostic details securely.

## 8. FE navigation and API call sequences

### Customer onboarding & first login

```text
Register screen
  POST /auth/register/send-otp
  POST /auth/register (phone, email, password, fullName, otp)
  201 -> Account created immediately -> Route to Login screen

Login screen
  POST /auth/login (phone, password)
  200 -> Save access token in memory
  if user.isPinSet === false -> Route to Mandatory PIN Setup screen
  if user.isPinSet === true -> Route to Dashboard

PIN Setup screen
  POST /customers/me/pin/setup (pin, confirmPin)
  200 -> user.isPinSet = true -> Route to Dashboard
```

### Customer transfer

```text
Transfer form
  Resolve recipient: POST /recipients/resolve
  Submit transfer: POST /transfers (Idempotency-Key, amount, pin, ...)
  if amount <= 5,000,000 VND:
    201 COMPLETED -> Refresh balances and transfer history
  if amount > 5,000,000 VND:
    200 AWAITING_OTP -> Display OTP verification modal (countdown)
    Submit OTP: POST /transfers/{transferId}/confirm-otp
    201 COMPLETED (or 200 replay) -> Refresh balances and transfer history
    400 OTP_INVALID -> Stay in modal, show remaining attempts
    409 TRANSFER_EXPIRED / STATE_CONFLICT -> Close modal, show status, offer new transfer
  network timeout -> retry same body/key (or same confirm call); reconcile via GET /transfers/{transferId}
```

### Operator counter operations

```text
Lookup customer
  GET /operator/customers?phone=...   (exact match, audited)
Create customer at counter
  POST /operator/customers/send-otp -> POST /operator/customers
Seed demo funds
  POST /operator/accounts/{accountId}/seed-balance (Idempotency-Key)
Block / unblock
  POST /operator/accounts/{accountId}/block | /unblock (reason)
```

### Forgot password

```text
Forgot password screen
  Choose channel SMS | EMAIL, enter phone | email
  POST /auth/recover/initiate -> always generic 200 -> OTP screen
  POST /auth/recover/verify (identifier, channel, otp) -> 200 + resetToken -> new password screen
  400 OTP_INVALID -> "Mã OTP không đúng hoặc đã hết hạn"
  POST /auth/recover/confirm (resetToken, newPassword) -> 200 -> Login screen
  400 RECOVERY_TOKEN_INVALID -> restart recovery
```

## 9. Zustand and server-state ownership

Use **TanStack Query** for server state and **Zustand** for client/UI state.
- Zustand stores UI navigation, modal visibility, and auth presentation state (`user`, token in memory).
- TanStack Query manages API responses, loading/error states, and cache invalidation.
- After transfer completion, seed or block/unblock, invalidate `accounts` and `transfers` queries.
- Zustand must not be the sole source of truth for balance, account status, transfer status/history, risk flag or audit record, nor for permission enforcement.
- Disable automatic retry for money mutations unless retry reuses the exact idempotency key and body. Optimistic balance updates are not allowed for MVP.

## 10. OpenAPI operation IDs and generated types

- Every operation has stable unique `operationId`, e.g. `registerCustomer`, `createTransfer`, `confirmTransferOtp`.
- FE API client/types derive from `../../contracts/openapi.yaml`.
- Do not hand-edit generated output; update source contract and regenerate.
- CI fails for invalid OpenAPI, duplicate operation IDs or generated diff.
- Use shared schema refs for `Problem`, `PageBase`, `MoneyAmount`, `TransferStatus`, `Account`, `Transfer`.
- Examples in this guide are illustrative; test fixtures must conform to OpenAPI validation.

## 11. Contract compatibility rules

Safe additive changes: optional response property, new enum value only when FE has unknown-value fallback, new endpoint. Breaking changes: remove/rename field/path, change type/meaning, make optional field required, alter status/error behavior or auth requirements.

Breaking change process:

1. Propose contract diff and affected FE screens.
2. Update contract and FE/BE together, or support old/new versions temporarily.
3. Add/adjust contract tests and mocks.
4. Merge coordinated change only after both builds pass.

No silent contract drift. Update OpenAPI, this guide and mock fixtures in same PR.

## 12. Team ownership and merge gates

- `../../contracts/openapi.yaml` is the single source of truth.
- FE generated types must strictly match `openapi.yaml`.
- FE can implement independently once path/schema/status/auth is approved and mocks cover success, empty, loading, validation, unauthorized, forbidden, conflict and unavailable states.
- BE can implement independently once contract tests assert status, headers, schema and error code, and ownership, roles, idempotency and money invariants have targeted tests.
- Integration merge gate: valid OpenAPI, current FE generated types, passing BE contract/integration tests, and E2E register → login → PIN setup → seed → transfer (≤ 5M and > 5M with OTP) → history/status.
