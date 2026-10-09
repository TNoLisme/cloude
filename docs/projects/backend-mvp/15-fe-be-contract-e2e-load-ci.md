# FE/BE — Kế hoạch Contract, Browser E2E, k6 và CI

**Người phụ trách:** Thành viên FE/BE integration  
**Reviewer:** BE-4 và module owner liên quan  
**Phối hợp:** BE-1 auth; BE-2 account/dataset/metrics; BE-3 transfer/invariant  
**Trạng thái:** Kế hoạch thực thi; dùng backend thật, không fallback mock khi backend lỗi.  
**Mục tiêu:** Bảo đảm FE dùng đúng contract thật, chạy E2E cho Customer/Operator/Auditor, tạo k6 load script, thêm CI FE/OpenAPI và kiểm tra same-origin routing.

---

## 1. Phạm vi công việc

### 1.1 Bao gồm

- OpenAPI type generation/drift check.
- FE typecheck/test/build.
- API smoke với backend thật.
- Browser E2E Customer/Operator/Auditor.
- Unknown outcome/reconciliation.
- Synthetic data setup.
- k6 script/report.
- GitHub Actions FE/OpenAPI jobs.
- Same-origin local/cloud routing validation.
- Responsive evidence.
- Handoff package cho BE-4.

### 1.2 Không bao gồm

- Sửa backend business để UI pass.
- Sửa Account/Transfer repository.
- Contract change chưa approved.
- Real customer data.
- Real SMS/Email.
- Cloud provisioning trước local acceptance.
- Lưu password/PIN/OTP/token để phục vụ test.

---

## 2. File và vùng code

```text
frontend/src/api/**
frontend/scripts/**
frontend/package.json
frontend/vite.config.ts
frontend/src/** tests
contracts/openapi.yaml (proposal only)
tests/load/banking-mvp.js
.github/workflows/**
```

Quy tắc:

- Generated OpenAPI output không sửa tay.
- Contract proposal gửi BE-4.
- FE không tự đổi status/error để khớp UI.
- Mock chỉ dùng unit/component test đã chủ động bật.
- Backend lỗi thật không được âm thầm fallback mock.

---

## 3. Kết quả phải bàn giao

```text
- FE contract gate output.
- Browser E2E matrix.
- k6 script và report.
- GitHub Actions workflow/run.
- Same-origin routing evidence.
- Screenshot/video synthetic data.
- Known FE limitations.
- Contract mismatch list.
```

Mẫu:

```text
Task:
Environment:
Backend revision:
Browser/tool version:
Command:
Expected:
Actual:
Status:
Evidence path:
Backend owner:
Next action:
```

---

## 4. Kế hoạch thực thi theo bước

### Bước 1 — Contract gate

Chạy:

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\frontend
npm run api:check
npm run typecheck
npm test
npm run build
npm run api:smoke
```

Kiểm tra:

- Generated types match OpenAPI.
- Base path không lặp `/api/v1`.
- `credentials: include` cho cookie flow.
- CSRF header đúng refresh/logout.
- Bearer token chỉ memory.
- Idempotency key giữ nguyên khi retry.
- Mutation không auto-retry unsafe.
- `Problem.code` quyết định UI behavior.
- 204 không parse JSON.
- 401/403/404/409/429/500/503 phân biệt đúng.

Nếu drift:

1. Dừng integration path.
2. Ghi operation/schema affected.
3. Báo BE-4 và BE owner.
4. Không sửa generated file tay.

### Bước 2 — Chuẩn bị synthetic data

Dùng:

- Disposable PostgreSQL.
- Local/demo profile được guard.
- Credentials qua environment variables.
- OTP mailbox local có guard.

Không ghi credential vào:

- Git.
- `.md`.
- Screenshot.
- Video.
- CI output.
- FE localStorage/sessionStorage.

Chuẩn bị dataset:

```text
Customer A: active account, known balance
Customer B: active account, known balance
Operator: lookup/seed/block permission
Auditor: audit/risk read-only permission
```

Ghi account IDs/full account numbers trong disposable env/secret mechanism, không ghi vào docs public.

### Bước 3 — Customer browser E2E

Chạy theo thứ tự:

1. Registration send OTP.
2. Verify registration OTP/proof.
3. Submit profile/register.
4. Login phone/password.
5. Setup PIN.
6. Read profile/accounts/balance.
7. Resolve recipient.
8. Transfer đúng `5,000,000`.
9. Kiểm tra `COMPLETED`, balance/history.
10. Transfer `5,000,001`.
11. Kiểm tra `AWAITING_OTP`, balance unchanged.
12. Wrong OTP lần đầu.
13. Valid OTP.
14. Confirm replay.
15. OTP expiry.
16. History/detail source.
17. Destination chỉ thấy completed.
18. Recovery SMS.
19. Recovery Email.
20. Change PIN/reset PIN.

Mỗi case ghi:

```text
Scenario
Input class, không ghi secret
Expected status/UI
Actual status/UI
API correlation ID nếu safe
Screenshot
Pass/fail/skipped
```

### Bước 4 — Operator browser E2E

1. Login Operator.
2. Lookup exact phone.
3. Lookup exact email.
4. No-filter bị chặn.
5. Both-filter bị chặn.
6. Tạo counter customer bằng OTP khách.
7. Seed balance.
8. Replay cùng seed key.
9. Block account với reason.
10. Xác minh Customer transfer bị backend reject.
11. Unblock.
12. Xác minh UI refetch status/balance từ server.
13. Kiểm tra masked account.
14. Kiểm tra seed cancel không tạo mutation.

### Bước 5 — Auditor browser E2E

1. Login Auditor.
2. Mở audit.
3. Filter event/time/actor.
4. Cursor load more.
5. Xem seed/block/transfer/recovery events.
6. Mở risk.
7. Kiểm tra `LARGE_TRANSFER`.
8. Kiểm tra `HIGH_FREQUENCY`.
9. Không có mutation control.
10. Customer/Operator route trực tiếp trả denied.
11. Logout clear session/cache.

### Bước 6 — Unknown outcome/reconciliation

Verify:

- Network timeout không hiển thị business failure giả.
- Exact body/key giữ trong memory để retry.
- Có transfer ID thì gọi detail.
- Không có transfer ID thì retry cùng key/body sau khi user action.
- Không optimistic balance.
- Retry không tạo duplicate.
- Confirm retry dùng transfer ID cũ.
- Completed replay không debit lần hai.

### Bước 7 — k6 load test

Tạo file:

```text
tests/load/banking-mvp.js
```

Nếu repo đã có thư mục test load khác, ghi rõ path và thống nhất với BE-4 trước khi tạo.

Baseline:

```text
50 VU
20 RPS steady target
10 phút
Transfers <= 5,000,000 VND
Disposable PostgreSQL
Synthetic identities/accounts
```

Scenario chính:

- Health check.
- Login/session setup.
- Account read.
- Recipient resolve.
- Small transfer.
- History read.
- Idempotency replay sample.

Scenario riêng tùy chọn:

- Transfer >5M.
- OTP mailbox.
- Confirm replay.

k6 phải ghi/kiểm tra:

- p50/p95/p99.
- throughput.
- expected business 4xx.
- unexpected 5xx.
- status/code distribution.
- correlation header.
- idempotency replay.
- final source/destination balance.
- duplicate transfer row/debit/credit.

Mục tiêu:

```text
Transfer p95 <= 500 ms
Balance p95 <= 500 ms
0 financial invariant violation
0 duplicate debit/credit
```

BE-2 cung cấp DB pool/lock metrics. BE-3 cung cấp invariant query. BE-4 review report.

Nếu k6 chưa cài:

- Ghi version/install method.
- Không commit binary.
- Không ghi token/credential.

### Bước 8 — GitHub Actions FE/OpenAPI

Tạo workflow sau khi BE-4 review tên file/path.

Jobs tối thiểu:

```yaml
openapi:
  - validate OpenAPI
  - npm run api:check

frontend:
  - npm ci
  - npm run typecheck
  - npm test
  - npm run build

backend:
  - gọi job/command do BE-4 duyệt
```

Quy tắc:

- Dùng lockfile.
- Pin Node/npm nếu repo yêu cầu.
- Dùng GitHub Secrets cho secret.
- Không hard-code JWT/DB/mailbox token.
- Upload test/build report không chứa secret.
- CI fail khi generated types drift.

### Bước 9 — Same-origin routing

Local:

```text
http://127.0.0.1:5173/api/* → http://localhost:8080/api/*
```

Cloud acceptance mặc định:

```text
https://bank.example.com/      → frontend
https://bank.example.com/api/* → backend
```

Kiểm tra browser thật:

- Refresh cookie được gửi.
- CSRF header được gửi/accept.
- Deep link SPA fallback hoạt động.
- `/api/v1/health` tới backend.
- Không wildcard CORS credential.
- Logout xóa state/cache.
- Refresh sau reload khôi phục session.

Cross-origin chỉ chấp nhận khi đã test:

- Exact CORS.
- `allowCredentials=true`.
- `SameSite=None; Secure`.
- HTTPS.
- Preflight.
- Chrome/Firefox browser behavior.

---

## 5. Lệnh kiểm thử

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\frontend
npm run api:check
npm run typecheck
npm test
npm run build
npm run api:smoke
```

k6:

```powershell
k6 run tests/load/banking-mvp.js
```

OpenAPI:

```powershell
cd ..
python be/scripts/validate-openapi.py
```

Diff:

```powershell
git diff --check
```

---

## 6. Tiêu chí nghiệm thu

### Contract/build

- `api:check` pass.
- Typecheck pass.
- FE tests pass.
- Production build pass.
- API smoke pass backend thật.

### Browser

- Customer E2E pass hoặc ghi explicit skipped reason.
- Operator E2E pass hoặc ghi explicit skipped reason.
- Auditor E2E pass hoặc ghi explicit skipped reason.
- Unknown outcome/reconciliation pass.
- Role direct-route denial pass.
- Responsive 360/768/1024/1440 evidence.

### k6

- Script tồn tại.
- Report có p50/p95/p99.
- Có throughput/error class/resource context.
- Có balance/idempotency invariant.
- Business 4xx tách unexpected 5xx.

### CI

- Workflow chạy pass.
- Contract drift làm CI fail.
- FE build/test chạy trên clean install.
- Artifact không có secret.

---

## 7. Handoff cho BE-4

Gửi:

- Commands và output thật.
- Browser scenario matrix.
- k6 path/version/report.
- CI workflow path/run/artifact.
- Screenshot/video synthetic.
- FE limitations.
- Contract mismatch list.
- Accessibility/performance limitations.
- Same-origin/cross-origin result.

## 8. Handoff cho BE owners

### BE-1

- Auth setup.
- Mailbox guard.
- Role credential mechanism.
- Recovery/PIN prerequisites.

### BE-2

- Account/seed fixtures.
- Operator lookup fixtures.
- DB metric fields.
- Expected balance dataset.

### BE-3

- Transfer dataset.
- Expected balances.
- Timeout/reconciliation hook.
- Invariant query.
- OTP scenario prerequisites.

---

## 9. Definition of Done

- E2E dùng backend thật.
- Backend lỗi không fallback mock âm thầm.
- k6 report ghi kết quả thật.
- CI workflow được BE-4 review.
- FE handoff package delivered.
- Không unapproved contract/backend changes.
- Không secret trong artifacts.
- Không commit/push nếu chưa được yêu cầu.
