# ADR-002 — Đồng bộ session contract theo Security

| Thuộc tính | Giá trị |
|---|---|
| Ngày / task | 14/11/2026 / FE-FND-02-AUTH-CONTRACT |
| Trạng thái | Người phụ trách FE đã chốt Security và v2 riêng; contract/mock được FE duyệt ngày 14/11/2026; runtime chờ BE/Security |
| Căn cứ | Câu trả lời trong chat ngày 14/11/2026, GAP-05 |
| Owner xác nhận runtime | BE + Security; chưa có xác nhận |

## 1. Nội dung đã chốt

Theo Security: cookie `SESSION_TOKEN`, `Path=/api`, login challenge purpose `LOGIN_MFA`. Giữ cờ HttpOnly/Secure/SameSite theo Security và không trả token phiên trong JSON. Đây là baseline mục tiêu FE/mock, chưa chứng minh BE runtime tương thích.

Quyết định không tự đổi TTL, rate limit, quyền, provider/enrollment hoặc step-up binding. Những giá trị và GAP đó vẫn theo nguồn sở hữu hiện tại.

## 2. Quyết định version đã chốt

[[digitalbanking-docs/docs-common/07. API contracts.md]] mục 1.4 và 2.1 yêu cầu thay đổi breaking tạo version URL mới /v2. Đổi tên cookie và thay enum login purpose tác động client hiện hữu. Đã hỏi người dùng chọn:

1. Sửa baseline /v1 chưa tích hợp thành bản nháp FE/mock theo Security, có ngoại lệ versioning được ghi rõ và chờ BE/Security duyệt trước tích hợp.
2. Giữ /v1 hiện tại, tạo contract /v2 theo quy tắc versioning.

Người dùng đã chọn trong chat ngày 14/11/2026: “Giữ nguyên /api/v1 hiện tại; tạo contract /api/v2 theo đúng quy tắc versioning cho baseline Security.” Theo đó giữ nguyên v1, thêm digital-banking-v2.yaml với info.version 2.0.0/base /api/v2, trạng thái Draft FE/mock; ngữ nghĩa tại [[digitalbanking-docs/docs-common/07a. API contracts v2.md]]. Types v2 riêng và api:check kiểm tra cả hai version.

## 3. Bản đồ thay đổi theo version

| Nguồn | Hiện trạng | Thay đổi mục tiêu / điều kiện |
|---|---|---|
| API Contracts | Cookie DB_SESSION, Path=/; purpose LOGIN_NEW_DEVICE trong mô tả/ví dụ/auth flow | Giữ v1; thêm ngữ nghĩa 07a cho v2 với SESSION_TOKEN, /api, LOGIN_MFA; ghi Draft FE/mock, chờ BE/Security |
| OpenAPI | sessionCookie.name DB_SESSION; SetCookie description; ChallengePurpose và ví dụ dùng LOGIN_NEW_DEVICE | Giữ YAML v1; tạo YAML v2 theo 07a, version 2.0.0 và base /api/v2, mô tả cookie issuance/deletion cùng Path=/api |
| OpenAPI README | Baseline 1.0.0 và quy trình thay đổi | Ghi baseline mới, migration và giới hạn duyệt; không tuyên bố runtime đã chạy |
| FE auth standard/feature | Ghi mâu thuẫn GAP-05 và luồng login | Ghi baseline đã chọn và version được chốt, tách runtime integration còn mở |
| Generated types | Enum/login comments của YAML cũ | Sinh lại từ đúng YAML mới bằng api:generate; api:check đạt |
| Mock fixtures/tests | Mock chỉ có login 200, session/logout; chưa mô phỏng cookie thật/challenge 202 | Bổ sung trường hợp theo baseline mới khi contract đã cập nhật; không dùng mock làm bằng chứng HttpOnly/Secure/CSRF runtime |
| BE identity/auth và REST conventions | Cookie DB_SESSION; purpose LOGIN_NEW_DEVICE | Đồng bộ tài liệu mục tiêu sau quyết định; ghi rõ cần đối chiếu triển khai thật, không coi là BE code đã đổi |
| SRS API catalog | Resend mô tả public cho LOGIN_NEW_DEVICE | Giữ SRS catalog v1; v2 kế thừa UC/FR và đổi nhãn purpose trong 07a/YAML, chưa có xác nhận SRS/runtime liên vai trò |
| Common DB và BE DB schema reference | Constraint purpose hiện mô tả LOGIN_NEW_DEVICE | Cần BE/DB chốt migration hoặc mapping. Không tự đổi DDL/seed/constraint bằng thao tác thay chuỗi trong tài liệu |
| Security | SESSION_TOKEN, /api, LOGIN_MFA | Giữ cơ chế đã chọn; không gán phê duyệt của Security từ lựa chọn phía FE |

Không thay docs-draft hoặc evidence các lần trước để làm mất lịch sử baseline cũ.

## 4. Tiêu chí đối chiếu

- Cookie name/purpose trong semantics, YAML, examples, FE standards và types phải thống nhất theo version đã chốt.
- Header cấp và xóa cookie cùng tên/phạm vi; client giữ credentials include và không đọc cookie HttpOnly.
- Login/session trả user + session metadata/CSRF theo schema, không trả accessToken legacy trong JSON.
- Challenge LOGIN_MFA được nhận diện ở login/verify/resend; không đổi purpose của transfer/sensitive action.
- api:generate/check và schema validation phù hợp phải đạt; mock test phân biệt anonymous/authenticated, challenge và OTP error/session expiry khi được triển khai.
- Cookie thực, HTTPS/CSRF/logout/reload và DB constraint cần evidence BE/browser riêng trước tích hợp.

## 5. Bằng chứng và trạng thái

[[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-AUTH-CONTRACT/2026-11-14-cfde9ad/run-01.md]]. Cơ chế FE/mock và versioning đã chọn. Contract/types/mock v2 đang được kiểm chứng riêng; session client/UI migration và tích hợp thật còn mở. Các dòng bảng mô tả v1 là nguồn tham chiếu được giữ nguyên; delta chỉ có trong contract v2.
