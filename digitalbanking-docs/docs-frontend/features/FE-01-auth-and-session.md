# FE-01 — Xác thực và phiên

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-01 |
| Phiên bản / ngày | 1.2 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Baseline đã chốt | Nội dung trước phần bổ sung design được người phụ trách FE xác nhận ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, QA |

## 1. Mục tiêu và phạm vi

Cung cấp password login, challenge thiết bị mới, trusted-device passcode/FIDO, khôi phục phiên và logout cho mọi user class. Challenge primitive dùng lại cho FE-03/05/08 theo purpose. Phạm vi register/recovery/PIN được chốt tại GAP-01.

## 2. Nguồn và phụ thuộc

API tại [[digitalbanking-docs/docs-common/07. API contracts.md]] và [[digitalbanking-docs/openapi/digital-banking-v1.yaml]].

FR-001–007, FR-026, FR-040, FR-042–043, UC-001–003/015, AC-001–004/019/024/033 trong [[digitalbanking-docs/docs-common/03. SRS.md]]. Contract 07 §3/6/8.1 và [[digitalbanking-docs/docs-backend/modules/02-identity-and-auth.md]].

Quy tắc dùng chung tại [[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md#FE-AUTH-01 — Session lifecycle]], [[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md#FE-AUTH-03 — Challenge và step-up dùng chung]], [[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md#API-ERR-01 — Lỗi và thông báo]].

## 3. Màn hình và điều hướng

AUTH-LOGIN và AUTH-CHALLENGE theo [[digitalbanking-docs/docs-frontend/standards/01-architecture.md#FE-ARCH-03 — Route và điều hướng]]. Session hiện có đi tới vùng được phép. Multi-role có menu/workspace chỉ chứa capability đã chốt. Challenge route cần memory context, thiếu context quay lại login với thông báo xác minh lại.

### Design UI của màn hình

- [[digitalbanking-docs/docs-frontend/design/screens/AUTH-LOGIN.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/AUTH-REGISTER.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/AUTH-RECOVERY.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/AUTH-WORKSPACE.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/AUTH-CHALLENGE.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/APP-BOOTSTRAP.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/ERROR-FORBIDDEN.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/ERROR-NOT-FOUND.md]].

Trạng thái đã có code và các vùng chờ cập nhật được ghi trong từng file. Quy tắc cập nhật tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 4. Luồng người dùng

1. Bootstrap gọi getSession. Thành công phục hồi metadata/CSRF, anonymous mở login, network error cho thử lại.
2. Nhập username/password và metadata thiết bị được phép, gọi login.
3. 200 nhận user/session và chuyển vùng được phép. 202 nhận challenge, hiển thị maskedDestination/expiresAt.
4. Gọi verifyOtp. Thành công nhận session, OTP sai cập nhật lỗi/attempts, expired/locked theo FE-AUTH-03.
5. Thiết bị tin cậy có thể gọi loginPasscode hoặc loginFido theo contract.
6. loginFido begin gửi username/deviceRef để nhận 202/options, browser thực hiện WebAuthn rồi finish theo cùng operationId.
7. Logout gọi server, xử lý local và đa tab theo FE-AUTH-01.

## 5. Trạng thái giao diện

| Trạng thái | UI |
|---|---|
| Boot/loading | Logo và trạng thái khôi phục, chờ session |
| Anonymous | Form login |
| Authenticating | Khóa submit, giữ nội dung hợp lệ |
| Challenge | Method được phép, nơi nhận masked, countdown, resend |
| Invalid/locked/expired | Lỗi trong flow và action restart theo contract |
| Session expired | Thông báo và login lại |
| Network error | ErrorPanel, retry có kiểm soát |

## 6. Thành phần và dữ liệu

LoginForm, ChallengeDialog/Page, DeviceMethodPicker, SessionProvider. Password/OTP chỉ ở form memory. Session metadata và CSRF theo FE-AUTH-01, device identifier chỉ lưu theo policy được duyệt tại GAP-04. Challenge context riêng theo purpose.

## 7. Tương tác API

| operationId | Method / path | Sử dụng |
|---|---|---|
| login | POST /auth/login | 200 session hoặc 202 challenge |
| loginPasscode | POST /auth/passcode | Thiết bị trusted |
| loginFido | POST /auth/fido/verify | Begin 202/options, finish 200 |
| verifyOtp | POST /auth/otp/verify | Challenge login |
| verifyStepUp | POST /auth/step-up/verify | Shared primitive cho request nhạy cảm |
| resendChallenge | POST /auth/challenges/{challengeId}/resend | Metadata challenge mới theo server |
| getSession | GET /auth/session | Bootstrap |
| logout | POST /auth/logout | Thu hồi phiên |

Path tương đối với /api/v1. DTO và response theo OpenAPI.

## 8. Validation và thông báo

Username normalize theo 07, password giữ nguyên. OTP/passcode là string theo schema. 401 AUTH_OTP_INVALID ở challenge, 401 session expired ở session. 423/410/429 hiển thị theo API-ERR-01, attempts/TTL từ server.

## 9. Trường hợp đặc biệt

Double submit bị khóa. Challenge context hết khi đổi user/purpose. Resend vô hiệu OTP nhập cũ. Tab sleep tính lại countdown. WebAuthn cancel/unsupported cho fallback được phép. Thay đổi phiên trong lúc request đang chạy loại response cũ. Logout offline báo kết quả server chưa xác nhận.

## 10. Tiêu chí nghiệm thu

- FET-01 chứng minh login 200/202, bootstrap reload, logout và session expiry theo AC-001/003/004.
- FET-02 chứng minh OTP sai/resend/expired/locked và passcode/FIDO fallback theo AC-002/033.
- FET-13 chứng minh CSRF, role redirect, cache isolation/đa tab theo AC-024/030.
- Password/OTP/session secret được kiểm tra theo FE-SEC-03.

## 11. Liên kết triển khai

FE-01-01 và FE-01-02 tại [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]]. Các điều chỉnh client/session được triển khai qua FE-FND-02 và FE-01-01. Evidence mới gắn task và FET ID theo [[digitalbanking-docs/docs-frontend/evidence/README.md]].

## 12. Vấn đề cần chốt

GAP-04 về enrollment/provider, GAP-05 về session/cookie và tên login challenge purpose, GAP-07 về capability. GAP-01 cho register/recovery/PIN. Tracker sở hữu quyết định và trạng thái các GAP.
