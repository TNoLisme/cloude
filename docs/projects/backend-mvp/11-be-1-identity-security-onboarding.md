# BE-1 — Kế hoạch Identity, Security và Onboarding

**Người phụ trách:** BE-1  
**Reviewer:** BE-4  
**Phối hợp:** BE-2, BE-3, FE/BE  
**Trạng thái:** Kế hoạch thực thi; chưa đánh dấu nghiệm thu trước khi có output thật.  
**Mục tiêu:** Kiểm tra và hoàn thiện lớp identity/security/onboarding, sau đó bàn giao contract và evidence đủ để FE chạy E2E không phải đoán nghiệp vụ.

---

## 1. Phạm vi công việc

### 1.1 Bao gồm

- Đăng ký Customer bằng phone OTP và registration verification token.
- Operator tạo Customer tại quầy bằng OTP của khách.
- Đăng nhập bằng phone + password.
- JWT access token.
- Refresh token rotation và logout.
- CSRF cho refresh/logout.
- Recovery bằng SMS và Email.
- PIN setup/change/reset và lockout.
- Role authorization và ownership denial.
- Rate limit theo policy đã duyệt.
- Local OTP mailbox guard.
- Audit auth/onboarding.
- Dependency scan, secret scan và sensitive-log review.

### 1.2 Không bao gồm

- Debit/credit transfer hoặc transfer concurrency.
- Account balance và seed implementation.
- k6 script.
- Cloud provisioning.
- Real SMS/Email provider.
- ML fraud detection.
- Public endpoint mới ngoài OpenAPI.
- Sửa migration đã applied.

---

## 2. File và vùng code được phép sửa

```text
be/src/main/java/com/bank/simulator/identity/**
be/src/main/java/com/bank/simulator/customer/**
be/src/main/java/com/bank/simulator/shared/ratelimit/**
be/src/test/java/com/bank/simulator/identity/**
be/src/test/java/com/bank/simulator/customer/**
```

File dùng chung:

| File/vùng | Quy tắc |
|---|---|
| `SecurityConfiguration` | BE-1 sửa; BE-4 review |
| Global error/correlation | BE-4 sở hữu; BE-1 gửi yêu cầu auth |
| `contracts/openapi.yaml` | Không sửa trực tiếp; gửi proposal cho BE-4 |
| Flyway migration | Chỉ Integration Owner cấp version; không sửa V1–V7 |
| `OnboardingController` | Có thể chung BE-2; chốt owner PR trước khi sửa |

Trước khi sửa:

1. Đọc `git status`.
2. Ghi file đang có thay đổi không thuộc task.
3. Không reset/discard thay đổi của người khác.
4. Không thêm password/OTP/PIN/token vào test fixture committed.

---

## 3. Kết quả phải bàn giao

BE-1 phải tạo hoặc cập nhật:

```text
- Implementation note của task.
- Test output thực tế.
- Security checklist.
- Auth error matrix.
- Role/ownership matrix.
- FE handoff note.
- Known gaps và skipped checks.
```

Mỗi handoff ghi:

```text
Task:
File thay đổi:
API impact:
DB/migration impact:
Security impact:
Command:
Kết quả thực tế:
Evidence path:
Known limitation:
Owner tiếp theo:
```

---

## 4. Kế hoạch thực thi theo bước

### Bước 1 — Kiểm tra baseline và contract

Đọc:

- `docs/baseline/api-and-team-contract.md`.
- `docs/baseline/quality-security-and-cloud.md`.
- `contracts/openapi.yaml`.
- `docs/projects/backend-mvp/02-shared-security.md`.
- `docs/projects/backend-mvp/03-identity-onboarding.md`.

Lập bảng:

| Flow | Endpoint | Role | Success | Error | Test hiện có | Gap |
|---|---|---|---|---|---|---|
| Login | `/auth/login` | Public | 200 | 400/401/429 | ... | ... |
| Refresh | `/auth/refresh` | Cookie + CSRF | 200 | 401/403 | ... | ... |
| Registration | `/auth/register/*` | Public | 200/201 | 400/409/429 | ... | ... |
| Recovery | `/auth/recover/*` | Public | 200 | 400/429 | ... | ... |
| PIN | `/customers/me/pin/*` | Customer | 200 | 400/403 | ... | ... |
| Counter | `/operator/customers/*` | Operator/Admin | 200/201 | 400/403/409 | ... | ... |

Không đổi contract chỉ vì test hoặc FE đang tiện hơn.

### Bước 2 — Auth/session regression

Kiểm tra:

1. Phone/password đúng trả `200`.
2. Email không được dùng làm login.
3. Phone không tồn tại và password sai có cùng lớp lỗi `401 CREDENTIALS_INVALID`.
4. JWT hết hạn, sai chữ ký, sai key đều bị từ chối.
5. Role lấy từ token đã verify, không lấy từ body/header client.
6. Customer gọi Operator endpoint nhận `403`.
7. Auditor không seed/block/create customer.
8. Refresh rotation chỉ có tối đa một winner khi gọi đồng thời.
9. Refresh token cũ không dùng lại được sau rotation.
10. Logout revoke session.
11. CSRF thiếu/sai chặn refresh/logout.
12. Recovery thành công revoke toàn bộ refresh sessions.

### Bước 3 — OTP/PIN regression

Kiểm tra:

- OTP có leading zero.
- OTP lưu hash, không lưu plaintext.
- OTP bind đúng `identifier + channel + purpose`.
- OTP sai lần 1–4 giữ trạng thái pending.
- Sai lần 5 invalidate theo contract.
- OTP hết hạn/đã consume/đã invalidate không dùng lại.
- Password hash và PIN hash dùng policy riêng.
- PIN setup không ghi đè PIN đã cấu hình.
- PIN change cần current PIN.
- Sai PIN 5 lần lock 15 phút.
- PIN failure commit độc lập với transaction tiền.

### Bước 4 — Registration/recovery atomicity

Kiểm tra:

1. Registration hợp lệ tạo đúng một user, role, customer, PIN row và default account.
2. OTP/proof sai tạo không tạo bản ghi.
3. Proof bound phone, one-use, expiry đúng.
4. Resend vô hiệu proof cũ.
5. Duplicate phone/email trả đúng `409`, không tạo partial record.
6. Hai registration đồng thời cùng phone/email chỉ một request commit.
7. Recovery initiate trả body/status generic cho identifier tồn tại và không tồn tại.
8. Recovery verify chỉ trả reset token sau OTP đúng.
9. Recovery confirm nhận reset token, không nhận raw OTP.
10. Reset token one-use và expiry-bound.
11. Password reset revoke refresh sessions.

### Bước 5 — Security/log review

Kiểm tra bằng test/log capture:

- Không có password, password hash, PIN, OTP, token, JWT, refresh cookie.
- Không có full account number.
- Không có raw phone/email nếu không cần.
- Audit summary không chứa request body nhạy cảm.
- Local mailbox bị chặn ngoài `local/demo`.
- HTTPS profile bật `Secure` cookie.
- Không có wildcard CORS với credentials.
- Error public không lộ SQL/stack trace/credential.

### Bước 6 — Chuẩn bị FE handoff

Tạo handoff theo mục 8. Gửi cho FE/BE và BE-3 trước khi họ chạy E2E.

---

## 5. Lệnh kiểm thử

Chạy targeted trước:

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*Security*Test,*Onboarding*Test,*Recovery*Test,*Registration*Test,*Otp*Test,*Refresh*Test" test
```

Nếu pattern không chọn đúng test:

```powershell
mvn -q -Dtest=DemoDataSeederTest,RegistrationTokenPostgresTest,OtpChallengePostgresTest,RefreshSessionPostgresTest test
```

Build:

```powershell
mvn -q -DskipTests package
```

OpenAPI:

```powershell
python scripts/validate-openapi.py
```

Dùng Testcontainers PostgreSQL cho uniqueness, persistence và concurrency. Mock chỉ dùng cho `OtpSender` hoặc external adapter.

Không ghi credential thật vào output. Không chạy test trên database giữ lại.

---

## 6. Tiêu chí nghiệm thu BE-1

### Bắt buộc pass

- Auth test đúng status và error code.
- Registration/recovery atomicity pass.
- Refresh rotation concurrent có một winner.
- Rate limit dùng bucket IP và identifier độc lập.
- Mailbox không có route ngoài local/demo.
- Secret không xuất hiện trong API/log/audit/fixture/evidence.
- OpenAPI không drift.
- Không sửa migration cũ.

### Evidence phải có

```text
- Test command và exit code.
- Số test pass/fail/skip.
- Profile dùng khi test.
- PostgreSQL/Testcontainers status.
- Security scan output.
- Log redaction result.
- Known limitation.
```

Không đánh dấu `VERIFIED` chỉ dựa trên docs thiết kế.

---

## 7. Handoff cho FE/BE

Gửi các nội dung sau, không gửi secret:

1. Danh sách auth endpoints từ OpenAPI.
2. Sequence login → CSRF → refresh → logout.
3. Cookie path, `HttpOnly`, `SameSite`, `Secure`.
4. `UserSummary`: `userId`, `customerId`, roles, `isPinSet`.
5. Cảnh báo staff `customerId=userId` chỉ là alias.
6. Route guard matrix.
7. OTP TTL, purpose, channel, max attempts.
8. Registration proof lifecycle.
9. Recovery anti-enumeration.
10. Error codes:

```text
CREDENTIALS_INVALID
OTP_INVALID
REGISTRATION_TOKEN_INVALID
RECOVERY_TOKEN_INVALID
PIN_INVALID
PIN_LOCKED
RATE_LIMITED
FORBIDDEN
```

11. Mailbox guard procedure.
12. Synthetic credential setup qua environment.
13. Browser E2E prerequisites.

---

## 8. Handoff cho owner khác

### BE-2

- Customer/profile lookup interface.
- Customer ID ownership semantics.
- Staff role behavior.
- Quy tắc không inject identity repository trực tiếp.

### BE-3

- PIN verification API/result.
- OTP challenge consume API.
- PIN verification nằm trước money transaction.
- Transfer OTP confirmation dùng transaction transfer.
- Failure/lock semantics.

### BE-4

- Auth audit event list.
- Rate-limit policy/metrics.
- Sensitive-data scan result.
- Security test output.
- Known gaps.

---

## 9. Definition of Done

- Test và scan có output thật.
- Implementation note cập nhật.
- FE handoff hoàn tất.
- BE-4 review security/contract impact.
- Không commit/push nếu chưa được yêu cầu.
