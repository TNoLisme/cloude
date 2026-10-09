# Backend Acceptance Task Handover — Detailed Ownership and FE Contract

**Ngày:** 2026-10-09
**Mục đích:** Hướng dẫn giao việc P0 cho 4 BE và 1 FE/BE; định nghĩa output, test, acceptance, handover và merge gate.
**Nguồn:** `docs/projects/backend-mvp/09-acceptance-gap-and-team-plan.md`, `docs/baseline/mvp-requirements-and-architecture.md`, `docs/baseline/api-and-team-contract.md`, `docs/baseline/quality-security-and-cloud.md`, `contracts/openapi.yaml`.

## 1. Quy tắc chung cho mọi thành viên

### Không được làm

- Không sửa OpenAPI để làm FE pass nếu chưa có contract approval.
- Không sửa migration đã applied. Schema change phải dùng migration mới.
- Không thêm ML, ledger, Kafka, Outbox, Saga, microservice, cancel/resend transfer hoặc real SMS/Email vào MVP.
- Không commit `.env`, JWT, password, PIN, OTP, refresh token, account number đầy đủ hoặc dữ liệu người thật.
- Không test trên shared/retained database bằng lệnh phá hủy.
- Không tự commit/push khi chưa được owner cho phép.
- Không ghi `PASS` khi chưa có command/output hoặc evidence thật.

### Quy tắc mỗi task

Mỗi task phải có:

1. Mục tiêu và acceptance criteria.
2. Danh sách file owner sửa.
3. API/DB/security impact.
4. Test trước và sau thay đổi.
5. Actual output, kể cả skipped/blocked.
6. FE handoff note nếu response/status/header/error thay đổi.
7. Không chứa secret trong log/evidence.

### Mẫu handoff tối thiểu

```text
Task:
Owner:
Files changed:
API impact: none | listed contract change
DB impact: none | migration Vn
Security/role impact:
Commands:
Results:
Known limitations:
FE action:
Next owner:
```

## 2. Individual work orders

Detailed execution files:

- [BE-1 — Identity, Security, Onboarding](./11-be-1-identity-security-onboarding.md)
- [BE-2 — Account, Operator, DB Support](./12-be-2-account-operator-db-support.md)
- [BE-3 — Transfer, Financial Consistency](./13-be-3-transfer-financial-consistency.md)
- [BE-4 — Audit, Risk, Integration](./14-be-4-audit-risk-integration.md)
- [FE/BE — Contract, E2E, k6, CI](./15-fe-be-contract-e2e-load-ci.md)

Use individual files as execution source. This file remains shared handover protocol and merge gate.

## 3. BE-1 — Identity, Security, Onboarding

### Mục tiêu

Bảo đảm identity, authentication, session, OTP, PIN, recovery, registration và counter creation đúng contract, đúng role, không lộ secret.

### Phạm vi sở hữu

- `identity/**`
- `customer/**`
- Auth controllers/services/repositories/tests.
- Registration verification token V7.
- Recovery token V6.
- JWT/refresh/CSRF/rate-limit.
- PIN lockout và OTP attempt state.
- Counter customer creation.
- Auth audit facts.

### Không sở hữu

- Account balance mutation.
- Transfer debit/credit.
- Risk rule implementation.
- k6 scenario chính.
- Cloud provisioning.

### Task bắt buộc

#### BE-1.1 Security regression

Kiểm tra:

- Login bằng phone đúng.
- Email không được dùng làm login.
- Sai phone và sai password trả cùng `401 CREDENTIALS_INVALID`.
- JWT expired/tampered/wrong signature trả `401`.
- Role deny-by-default.
- Customer/ Auditor gọi Operator endpoint nhận `403`.
- Customer không truy cập account/customer khác.
- Refresh rotation chỉ dùng token cũ thành công tối đa một lần.
- Logout revoke session.
- Recovery revoke toàn bộ refresh sessions.
- CSRF thiếu/sai token bị chặn.
- Rate limit độc lập theo IP và identifier.

#### BE-1.2 OTP/PIN regression

Kiểm tra:

- OTP 6 chữ số có leading zero.
- OTP hash-only trong DB.
- OTP gắn đúng purpose/channel/identifier.
- OTP hết hạn, dùng lại, sai 1–4 lần, sai lần 5.
- PIN hash khác password hash.
- PIN sai 5 lần lock 15 phút.
- PIN failure state vẫn commit khi request business sau đó fail.
- Local mailbox không tồn tại ngoài local/demo.

#### BE-1.3 Registration/recovery atomicity

Kiểm tra:

- Registration proof đúng tạo đúng một user/customer/account.
- Proof sai, hết hạn, khác phone, dùng lại không tạo dữ liệu.
- Duplicate phone/email trả đúng 409.
- Concurrent registration cùng phone/email chỉ có một commit.
- Recovery verify đúng cấp reset token một lần.
- Recovery confirm sai token không đổi password.
- Password đổi thành công revoke refresh sessions.

### Test command đề nghị

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*Security*Test,*Onboarding*Test,*Recovery*Test,*Registration*Test,*Otp*Test,*Refresh*Test" test
mvn -q -DskipTests package
```

Nếu pattern Maven không chọn đúng test, chạy tên test cụ thể. Dùng Testcontainers PostgreSQL cho persistence/race; dùng mock cho external sender.

### Acceptance của BE-1

- Không có secret trong response/log/audit/fixture.
- Contract statuses/codes không drift.
- Staff `customerId=userId` alias không được dùng như Customer ownership.
- Registration và recovery flow FE có thể chạy bằng local mailbox.
- Evidence ghi rõ test count, database mode, profile và limitation.

### Bàn giao cho FE

BE-1 phải gửi:

1. Auth endpoint list từ OpenAPI.
2. Login/refresh/logout sequence.
3. Cookie và CSRF requirements.
4. `UserSummary` role/isPinSet behavior.
5. Route guard matrix.
6. OTP purpose/channel/TTL/attempt behavior.
7. Recovery anti-enumeration behavior.
8. Registration proof behavior:
   - token chỉ giữ memory;
   - token bound phone;
   - token one-use;
   - resend invalidates old proof;
   - không dùng token hết hạn.
9. Exact error matrix: `401`, `403`, `400 OTP_INVALID`, `400 RECOVERY_TOKEN_INVALID`, `403 PIN_LOCKED`, `429 RATE_LIMITED`.
10. Local mailbox command/guard, không ghi OTP thật vào docs.

### Handoff sang người khác

- Sang BE-2: customer ID/profile lookup contract; không chia sẻ repository trực tiếp.
- Sang BE-3: `IdentityModuleApi` cho PIN verification và OTP consume; nêu transaction boundary.
- Sang BE-4: auth audit events, rate-limit metrics, sensitive-log review.
- Sang FE/BE: auth browser evidence, cookie/refresh behavior, role matrix.

## 3. BE-2 — Account và Operator

### Mục tiêu

Bảo đảm account ownership, balance query, seed, block/unblock, exact lookup và account masking đúng role, transaction và idempotency.

### Phạm vi sở hữu

- `account/**`
- Account repositories/services/controllers.
- Operator lookup DTO/mapping phối hợp BE-1 nếu còn chung controller.
- Seed ledger.
- Account status transitions.
- Account masking.
- Account-level lock tests.

### Không sở hữu

- Transfer pair transaction orchestration.
- Identity credential/OTP implementation.
- Audit query/risk evaluation.

### Task bắt buộc

#### BE-2.1 Account read/IDOR

Kiểm tra:

- Customer chỉ thấy account của mình.
- Account ID đoán được không làm lộ dữ liệu.
- Unauthorized account trả đúng contract concealment.
- Account number luôn masked.
- Balance serialize integer string.
- Status/currency/accountType đúng schema.
- List ordering deterministic.

#### BE-2.2 Operator exact lookup

Kiểm tra:

- Exactly one `phone` hoặc `email`.
- None/both/blank/invalid/wildcard/partial trả `400 VALIDATION_ERROR`.
- Normalize phone/email đúng.
- Exact match only.
- Not found trả `404 CUSTOMER_NOT_FOUND`.
- Role customer/auditor bị `403`.
- Lookup audit không lưu raw search value.
- Response không chứa `accountNumber` đầy đủ.

#### BE-2.3 Seed idempotency

Kiểm tra:

- Amount `1..100000000`.
- Currency chỉ `VND`.
- Fraction/leading zero/negative/zero/over max bị reject.
- Blocked/closed account không seed.
- Same actor + operation + key + payload replay không credit lần hai.
- Same key khác payload trả `409 IDEMPOTENCY_KEY_REUSED`.
- Concurrent same-key seed tạo một ledger row và một balance mutation.
- Audit, ledger, balance, idempotency commit atomic.

#### BE-2.4 Block/unblock

Kiểm tra:

- `ACTIVE -> BLOCKED`.
- `BLOCKED -> ACTIVE`.
- Reason bắt buộc, trim, 1–500 ký tự.
- Repeated same state trả current state, không duplicate audit.
- `CLOSED` trả `409 ACCOUNT_NOT_ELIGIBLE`.
- Row lock serialize với transfer.
- Balance/history vẫn readable khi BLOCKED.

### Test command đề nghị

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*Account*Test,*Operator*Test,*OnboardingControllerMvcTest" test
```

Database lock, seed rollback và concurrent seed phải chạy Testcontainers PostgreSQL.

### Acceptance của BE-2

- Account response khớp `Account` schema.
- Operator lookup không lộ full account number.
- Seed replay không tạo duplicate balance mutation.
- Blocked account không nhận seed và không tham gia transfer.
- Mọi mutation có audit fact.

### Bàn giao cho FE

Gửi:

1. Account DTO field list và money string rule.
2. Account status matrix `ACTIVE/BLOCKED/CLOSED`.
3. Ownership/404 behavior.
4. Operator lookup query rule phone XOR email.
5. Seed request, key lifecycle và replay header.
6. Block/unblock reason validation.
7. UI refresh/invalidation requirements sau seed/status mutation.
8. Error codes: `ACCOUNT_NOT_FOUND`, `ACCOUNT_NOT_ELIGIBLE`, `IDEMPOTENCY_KEY_REUSED`, `VALIDATION_ERROR`, `RATE_LIMITED`.
9. Demo seed instructions qua environment, không gửi credential trong file.

### Handoff sang người khác

- Sang BE-3: `AccountModuleApi`, lock account pair, validate status/currency/balance, debit/credit primitives.
- Sang BE-4: account mutation audit facts và metrics.
- Sang FE/BE: Operator browser evidence, exact response shape, masked account assertions.

## 4. BE-3 — Transfer và Financial Consistency

### Mục tiêu

Chứng minh tiền không mất, không tạo thêm, không debit hai lần, không âm balance, không deadlock và retry không tạo duplicate mutation.

### Phạm vi sở hữu

- `transfer/**`
- Recipient resolve.
- Transfer create/confirm/list/detail.
- PIN-before-money boundary integration.
- Ordered pair lock.
- OTP step-up state machine.
- Transfer idempotency.
- Fault injection.
- PostgreSQL concurrency tests.

### Không sở hữu

- Account repository implementation.
- OTP sender implementation.
- Audit repository implementation.
- Risk query implementation.

### Task bắt buộc

#### BE-3.1 Failure rollback

Viết deterministic test hook chỉ active test profile:

1. Lock source/destination.
2. Debit source.
3. Inject exception before credit/commit.
4. Assert transaction rollback.

Assert:

- Source unchanged.
- Destination unchanged.
- No completed transfer.
- No successful idempotency result.
- No committed audit.
- No risk flag from rolled-back transfer.

Không dùng hook production profile.

#### BE-3.2 Timeout after commit

Mô phỏng response delay/loss sau commit:

1. Submit transfer with key K1.
2. Commit transaction.
3. Suppress/delay response.
4. Retry exact payload with K1.
5. Assert original transfer result.
6. Assert source debit once and destination credit once.

Không tạo K2 tự động.

#### BE-3.3 Concurrent overdraft

Dùng source balance 1,000,000 và hai requests 600,000:

- Exactly one commits.
- One returns `409 INSUFFICIENT_FUNDS`.
- Final source balance 400,000.
- Destination gets exactly 600,000.
- No negative balance.

#### BE-3.4 Concurrent duplicate idempotency

Gửi đồng thời N requests cùng actor/key/payload:

- One logical transfer.
- One debit and one credit.
- Replays return original result.
- Altered payload with same key returns `409 IDEMPOTENCY_KEY_REUSED`.

#### BE-3.5 Opposite-direction locking

Chạy A→B và B→A đồng thời nhiều lần:

- No persistent deadlock.
- Requests complete or fail with bounded retryable error.
- No negative balances.
- Lock order remains PostgreSQL `id ASC`.

#### BE-3.6 OTP confirm race

Chạy duplicate confirm concurrently:

- One completion.
- One debit/credit.
- Later call returns replay/state conflict.
- Wrong OTP attempts persist.
- Fifth wrong attempt commits `FAILED` and invalidation.
- Balance/status change before confirm causes `FAILED` without mutation.

#### BE-3.7 Block-vs-confirm race

Block destination/source while transfer awaits OTP:

- Confirm re-locks accounts.
- Revalidates status under lock.
- Transfer becomes `FAILED` with `ACCOUNT_NOT_ELIGIBLE`.
- No balance mutation.
- OTP invalidated per contract.

### Test command đề nghị

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*Transfer*Test,*Recipient*Test,*UiApiRegressionPostgresTest" test
```

Concurrency tests must use PostgreSQL/Testcontainers, not mocks or H2-only semantics.

### Acceptance của BE-3

- Every completed transfer has equal debit/credit.
- Rejected transfer changes no balance.
- Transfer amount exact integer VND string.
- Lock order deterministic.
- Same key replay safe.
- Unknown outcome reconciles by transfer ID/key.
- OTP waiting does not reserve balance.
- Only confirm transaction locks accounts for step-up.

### Bàn giao cho FE

Gửi:

1. Transfer status state machine.
2. Amount boundary behavior: `5,000,000` immediate, `5,000,001` OTP.
3. Request body and required headers.
4. Idempotency key rules.
5. Timeout rule: never infer failure from network timeout.
6. Reconciliation rule using `GET /transfers/{transferId}`.
7. OTP wrong attempts behavior.
8. Expiry behavior.
9. Blocked/insufficient/currency mismatch behavior.
10. Exact HTTP/code matrix:
    - `201 COMPLETED`.
    - `200 AWAITING_OTP`.
    - `200` replay with `Idempotency-Replayed: true`.
    - `400 OTP_INVALID`.
    - `409 STATE_CONFLICT`.
    - `409 TRANSFER_EXPIRED`.
    - `409 INSUFFICIENT_FUNDS`.
    - `409 ACCOUNT_NOT_ELIGIBLE`.
    - `503 SERVICE_UNAVAILABLE`.
11. No optimistic balance update.
12. No cancel/resend transfer UI.

### Handoff sang người khác

- Sang BE-2: account lock API and status validation assumptions.
- Sang BE-1: PIN verification and OTP consume transaction boundary.
- Sang BE-4: fault-injection switch, test dataset, transfer event/risk snapshot.
- Sang FE/BE: browser test script, timeout/reconciliation evidence, threshold screenshots.

## 5. BE-4 — Audit, Risk, Quality và Integration

### Mục tiêu

Xem chi tiết tại [BE-4 work order](./14-be-4-audit-risk-integration.md). BE-4 điều phối evidence và merge gate; k6/CI execution do FE/BE sở hữu, security scan do BE-1 sở hữu, DB metrics do BE-2 hỗ trợ.

### Phạm vi sở hữu

- `audit/**`
- `risk/**`
- Shared cursor/correlation/error review.
- Evidence index.
- Acceptance matrix.
- Merge gate.
- Review Docker/image/dependency scan.
- Documentation synchronization.

### Task bắt buộc

#### BE-4.1 Audit/risk integrity

Kiểm tra:

- Audit append-only application API.
- Required actions have actor/action/target/time/outcome/correlation.
- No password/PIN/OTP/token/hash/full account number/raw PII.
- Audit filters/cursor stable.
- Only Auditor/Admin query audit.
- Risk query role matrix correct.
- `LARGE_TRANSFER`: 5M no flag, 5M+1 flag.
- `HIGH_FREQUENCY`: 5 no flag, 6 outgoing completed flag.
- Pending/failed/incoming excluded correctly.
- AFTER_COMMIT risk failure does not rollback transfer.
- Duplicate evaluation does not create duplicate flag.

#### BE-4.2 Evidence format

Mỗi evidence file ghi:

- Date.
- Commit/version nếu có.
- Environment label.
- Database isolation.
- Exact command.
- Tool version.
- Expected result.
- Actual result.
- Pass/fail/skipped.
- Failure summary.
- Secret handling.
- Limitation.

Không ghi OTP/password/token/account full.

#### BE-4.3 k6/load coordination

FE/BE owns k6 script and execution. BE-4 reviews workload, result classification and evidence. Required report:

- `tests/load/` or agreed path.
- Synthetic users/accounts.
- Setup/teardown isolation.
- 50 VU, 20 RPS, 10 minutes.
- p50/p95/p99.
- Expected business 4xx versus unexpected 5xx.
- CPU/RAM/DB connections/lock waits.
- Final financial invariants.

#### BE-4.4 Security/dependency/image scan review

BE-1 owns dependency, secret and sensitive-log scan. BE-4 reviews result and records it in acceptance matrix. Image scan is coordinated with FE/BE.

Kiểm tra:

- Dependency vulnerability report.
- Container image vulnerability report.
- Secret scan.
- Log redaction scan.
- Local mailbox disabled outside local/demo.
- No wildcard CORS with credentials.
- Secure cookie in HTTPS profile.
- Actuator/local debug route not public.

#### BE-4.5 CI/merge gate review

FE/BE owns FE/OpenAPI workflow implementation. BE-4 reviews and merges gate definition. Pipeline minimum:

- OpenAPI validate.
- FE generated type drift.
- Backend compile/package.
- Targeted backend tests.
- FE typecheck/tests/build.
- Image build.
- Security scan.
- Evidence artifact upload.

### Test command đề nghị

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude
python be/scripts/validate-openapi.py

cd be
mvn -q -DskipTests package
mvn -q test

cd ..\frontend
npm run typecheck
npm test
npm run api:check
npm run build

cd ..
git diff --check
```

### Acceptance của BE-4

- Acceptance matrix có evidence link cho từng required demo.
- Planned/implemented/verified không bị trộn.
- Failure, concurrency, load result ghi actual numbers.
- Contract/DB/security impacts reviewed.
- Docs migration baseline V1–V7 đồng bộ.
- Final result chỉ ghi `ACCEPTED` khi critical P0 pass.

### Bàn giao cho FE

Gửi một package duy nhất gồm:

1. OpenAPI path/schema/version.
2. Error code matrix.
3. Role matrix.
4. Demo identities setup method.
5. Local mailbox setup/guard.
6. FE E2E test data.
7. Expected state transitions.
8. Known limitations.
9. Backend base URL/health/Swagger.
10. API smoke command.
11. Browser E2E command.
12. Evidence links.

### Handoff sang người khác

- Nhận input từ BE-1/2/3 bằng implementation note có test evidence.
- Gửi final contract/acceptance package cho FE/BE.
- Gửi gap list cho team lead.
- Không tự quyết định contract change; mở decision record nếu mismatch.

## 6. FE/BE — Contract Consumer và E2E

### Mục tiêu

Xác minh FE dùng đúng backend contract thật, không dùng mock để che backend failure, và cung cấp browser evidence cho ba role.

### Phạm vi sở hữu

- `frontend/src/api/**`
- Generated OpenAPI types.
- API smoke.
- Browser E2E.
- Role route guard.
- Responsive/accessibility evidence.
- Unknown outcome/reconciliation evidence.

### Task bắt buộc

#### FE/BE.1 Contract gate

Chạy:

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\frontend
npm run api:check
npm run typecheck
npm test
npm run build
```

Nếu backend đổi contract:

- Không sửa generated file bằng tay.
- Regenerate từ `contracts/openapi.yaml`.
- Ghi API impact.
- Báo FE screens affected.

#### FE/BE.2 Customer E2E

Chạy với synthetic QA data:

1. Registration send OTP.
2. Registration verify/proof.
3. Registration submit.
4. Login by phone.
5. PIN setup.
6. Account/balance read.
7. Recipient resolve.
8. Transfer `5,000,000`.
9. Transfer `5,000,001`.
10. Wrong OTP.
11. Correct OTP.
12. Confirm replay.
13. Expired transfer.
14. History/status.
15. Recovery SMS and Email.
16. PIN change/reset.

#### FE/BE.3 Operator E2E

1. Login Operator.
2. Exact lookup by phone.
3. Exact lookup by email.
4. Create counter customer with OTP.
5. Seed balance.
6. Seed same key replay.
7. Block account with reason.
8. Verify Customer transfer denied.
9. Unblock account.
10. Verify UI refetches server state.

#### FE/BE.4 Auditor E2E

1. Login Auditor.
2. Open audit.
3. Filter/cursor.
4. Verify seed/block/transfer/recovery events.
5. Open risk.
6. Verify `LARGE_TRANSFER` and `HIGH_FREQUENCY` flags.
7. Verify no mutation controls.
8. Verify Customer/Operator routes denied.

#### FE/BE.5 Unknown outcome

Verify:

- Network timeout does not show false failure.
- Same request key/body retained for retry.
- Transfer ID detail reconciliation works.
- No optimistic balance mutation.
- No duplicate transfer after retry.

### Acceptance của FE/BE

- Three role flows use real API.
- API smoke pass.
- No secret persisted in localStorage/sessionStorage/URL/log.
- Screenshots use synthetic data.
- Mobile page no horizontal overflow.
- Error code behavior matches contract.
- Evidence includes browser viewport and exact backend version.

### Bàn giao

Gửi BE-4:

- Browser E2E command/result.
- Screenshots/video paths.
- Role coverage.
- Failed/skipped flows.
- FE limitations.
- Contract mismatch list.
- Performance/accessibility notes.

## 7. Team handoff protocol

### Khi task hoàn tất

Owner gửi implementation note trước khi người khác bắt đầu phụ thuộc:

```markdown
## Status
Implemented | Verified | Blocked

## Files
- ...

## Contract impact
None | details

## DB impact
None | Vn migration

## Tests
- command: ...
- result: ...

## FE handoff
- endpoint:
- request:
- response:
- statuses:
- error codes:
- UI behavior:

## Known gaps
- ...

## Next owner
- ...
```

### Khi có failure

Không sửa chéo module ngay. Gửi:

1. Exact reproduction.
2. Correlation ID nếu có.
3. Request shape đã redact.
4. Response status/code.
5. Database state an toàn.
6. Expected/actual.
7. Owner module.

### Khi có contract conflict

Dừng implementation affected path. Ghi conflict vào spec. Đề xuất option. Chờ approval. Không tự sửa OpenAPI hoặc ép FE/BE theo assumption.

## 8. Merge order đề nghị

1. BE-1 security/onboarding regression.
2. BE-2 account/operator acceptance.
3. BE-3 failure/concurrency tests.
4. BE-4 audit/risk/evidence/load package.
5. FE/BE browser E2E.
6. Integration final gate.

Mỗi PR độc lập, ít file shared. `contracts/openapi.yaml`, migration numbering và shared configuration chỉ có một owner merge.

## 9. Definition of Done P0

P0 chỉ hoàn tất khi:

- Failure rollback evidence pass.
- Timeout-after-commit retry evidence pass.
- Restart recovery evidence pass.
- DB outage fail-closed evidence pass.
- Concurrent overdraft evidence pass.
- Concurrent idempotency evidence pass.
- Opposite-direction lock evidence pass.
- Block-vs-confirm race evidence pass.
- k6 50 VU/20 RPS/10 minutes report exists.
- Security/dependency/image/log scans reviewed.
- FE Customer/Operator/Auditor E2E evidence linked.
- OpenAPI/typegen/build/tests pass.
- No secrets or destructive DB operations.
- Acceptance report states actual result and remaining limitations.
