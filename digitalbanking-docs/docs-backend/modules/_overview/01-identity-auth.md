# Module 01: `identity` / `auth` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-01`
> **Module:** `com.simulatedbank.banking.identity` (`identity` / `auth`)
> **Owner chính:** **Đạt** (Phối hợp: Thắng — FE, Thịnh — `audit`, Vũ — `transfer` step-up)
> **Mốc tập trung:** **Tuần 3 (W3)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-001..007, FR-026, FR-040), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§5.1), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §7.1), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§4, §6, §11.1), [[digitalbanking-docs/docs-common/design/04. security.md]] (§4, §5)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `identity` (`auth`) chịu trách nhiệm quản lý toàn bộ định danh người dùng (`CUSTOMER` và `STAFF`), xác thực đăng nhập, quản lý thiết bị tin cậy (`device`), duy trì phiên đăng nhập phía server (`Opaque Server-Side Session`), cấp phát và xác minh mã xác thực tăng cường (`OTP / PIN Step-up Challenge`) và đánh giá phân quyền RBAC.

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `identity` là chủ sở hữu ghi duy nhất của nhóm bảng định danh và bảo mật phiên (`06. System architecture.md` §6.2):

| Bảng | Mục đích chính | Ràng buộc & Quy tắc cốt lõi |
|---|---|---|
| `app_user` (`users`) | Tài khoản đăng nhập của `CUSTOMER` và `STAFF` | Lưu `password_hash` (`Argon2id`), `failed_login_count`, `locked_until`, `status` (`ACTIVE`, `LOCKED`, `DISABLED`). |
| `role`, `permission`, `user_role`, `role_permission`, `user_privilege` | Mô hình phân quyền RBAC | Tách biệt cứng role của `CUSTOMER` và các role của `STAFF` (`OPERATIONS`, `RISK_COMPLIANCE`, `ADMIN_SECURITY`). |
| `device` (`user_device`) | Quản lý thiết bị đã đăng nhập của người dùng | Lưu `device_fingerprint_hash` (`HMAC-SHA256`) và cờ `trusted` (`true`/`false`) phục vụ kích hoạt MFA khi đăng nhập từ thiết bị lạ. |
| `session` (`sessions`) | Phiên đăng nhập Opaque Server-side | Chỉ lưu `token_hash` (`SHA-256`) và `csrf_token_hash`; kiểm soát `idle_expires_at` (`15 phút`) và `absolute_expires_at` (`8 giờ`). |
| `auth_challenge`, `auth_attempt` | Quản lý OTP/Step-up challenge & lịch sử thử | Dùng cho `LOGIN_MFA`, `TRANSFER_STEP_UP`, `SENSITIVE_ACTION`. Lưu `otp_hash`, `payload_hash`, `attempt_count`, `max_attempts` (`5`), `status` (`PENDING`, `VERIFIED`, `CONSUMED`, `EXPIRED`, `LOCKED`). |
| `password_history` | Lịch sử mật khẩu đã dùng | Ngăn tái sử dụng mật khẩu cũ khi đổi/khôi phục. |

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §11.1)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-AUT-01` | `POST /api/v1/auth/login` | Public | Theo `03` §4.5/`07` §3.7: baseline đề xuất `5 lần sai -> khóa 10 phút` (`423 AUTH_ACCOUNT_LOCKED`). Nếu thiết bị mới $\rightarrow$ tạo challenge `LOGIN_MFA` (`202 Accepted`); nếu đã tin cậy $\rightarrow$ cấp cookie `DB_SESSION` (`HttpOnly, Secure, SameSite=Strict, Path=/`) + `csrfToken` (`200 OK`) theo `07` §3.2. |
| `API-AUT-02` | `POST /api/v1/auth/logout` | Authenticated | Thu hồi phiên hiện tại (`status = REVOKED`, `revoked_at = now()`), xóa cookie phiên. |
| `API-AUT-03` | `GET /api/v1/auth/session` | Authenticated | Kiểm tra tính hợp lệ của phiên, trả về thông tin `userId`, `userType`, `roles`, `permissions` và gia hạn `idle_expires_at`. |
| `API-AUT-04` | `POST /api/v1/auth/step-up` | Authenticated | Khởi tạo challenge xác thực tăng cường (`TRANSFER_STEP_UP` hoặc `SENSITIVE_ACTION`). |
| `API-AUT-05` | `POST /api/v1/auth/step-up/{id}/verify` *(hoặc `/auth/step-up/verify`)* | Authenticated | Xác minh mã OTP/PIN. Nếu đúng $\rightarrow$ chuyển challenge sang `VERIFIED`; nếu sai 5 lần $\rightarrow$ chuyển sang `LOCKED` (`423`). |
| `API-AUT-06` | `POST /api/v1/auth/step-up/{id}/resend` | Authenticated | Gửi lại mã OTP (kiểm tra thời gian chờ `resend_cooldown = 60s` và giới hạn tối đa `3 lần` gửi lại). |

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports`)

### 4.1. Ports cung cấp cho các module khác gọi (`identity.api`)
- **`StepUpChallengePort`** *(Được gọi bởi `transfer` ở pha `T0`/`T1` và `admin` khi Checker duyệt)*:
  - `createTransferStepUpChallenge(userId, idempotencyKey, requestHash, amount)`: Tạo challenge khi giao dịch vượt ngưỡng Step-up (trả về `428 STEP_UP_REQUIRED`).
  - `consumeVerifiedChallenge(challengeId, userId, expectedPurpose, expectedPayloadHash)`: Thực hiện `UPDATE banking.auth_challenge SET status = 'CONSUMED', consumed_at = now() WHERE challenge_id = :id AND status = 'VERIFIED' AND expires_at > now() ...` nguyên tử trong `T1` để đảm bảo mỗi challenge chỉ dùng được đúng 1 lần.
- **`IdentityRoleCommandPort`** *(Được gọi bởi `admin` khi Checker phê duyệt `ROLE_CHANGE`)*:
  - Cập nhật bảng `user_role` / `user_privilege` của nhân viên mục tiêu và **thu hồi ngay lập tức toàn bộ `session` đang mở** của nhân viên đó (`revoked_reason = 'ROLE_CHANGED'`).

### 4.2. Ports của module khác mà `identity` cần gọi
- **`AuditModuleApi`** (của module `audit`): Ghi nhận các sự kiện `AUTH_LOGIN_SUCCESS` (`C2`), `AUTH_LOGIN_FAILED` (`C3`), `AUTH_ACCOUNT_LOCKED` (`C3`), `AUTH_SESSION_LOGOUT` (`C3`), `AUTH_CHALLENGE_CREATED` (`C2`), `AUTH_CHALLENGE_LOCKED` (`C3`), `AUTH_STEP_UP_VERIFIED` (`C2`), `AUTH_STEP_UP_FAILED` (`C3`).
- **`NotificationOutboxPort` / `OtpGatewayAdapter`**: Gửi mã OTP qua kênh mô phỏng.

---

## 5. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

1. [ ] Thiết kế và hiện thực `OpaqueSessionAuthenticationFilter` và `CsrfTokenValidationFilter` (kết hợp cùng module `common`).
2. [ ] Hiện thực luồng đăng nhập với `Argon2id`, chống dò tài khoản (timing-safe dummy hash); bộ đếm khóa theo baseline 10 phút còn Đề xuất của `03`/`07`, không kế thừa 15 phút từ design/04; transaction độc lập (`REQUIRES_NEW`).
3. [ ] Hiện thực nhận diện thiết bị (`device_fingerprint_hash`) và luồng MFA cho thiết bị mới.
4. [ ] Hiện thực máy trạng thái `auth_challenge` (`PENDING -> VERIFIED -> CONSUMED`) gắn chặt với `payload_hash` để chống đánh tráo nội dung giao dịch sau khi verify OTP.
5. [ ] Viết bộ test tích hợp cho phiên đăng nhập, nhiều session theo `07` §3.2 (không áp trần 3/1 từ design/04), khóa OTP sau 5 lần sai và chống dùng lại challenge (`T-STEP-01`, `T-STEP-02`, `CT-API-001..005`).

## Change Log

| Ngày | Thay đổi |
|---|---|
| 12/11/2026 | Theo quyết định owner ưu tiên docs-common/01–11: đồng bộ lockout/cookie/session trong các đoạn liên quan tích hợp common; chưa sửa runtime hoặc rà toàn bộ đặc tả identity |
