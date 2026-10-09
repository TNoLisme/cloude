# Digital Banking UI — tiến độ và bàn giao

Cập nhật: 09/10/2026 (bắt đầu 08/10/2026). Frontend đã nối API thật; Customer/auth/audit/risk và Operator đã có kiểm chứng. Lỗi response lookup Operator đã được người dùng duyệt sửa; tra cứu/nạp/khóa/mở khóa PASS desktop/tablet, mobile không tràn ngang.

## Nguồn và khảo sát ban đầu

- Bộ thiết kế thực tế: [brief ưu tiên](../../design/digital-banking-codex-handoff/IMPLEMENTATION-BRIEF.md), [nghiệp vụ](../../design/digital-banking-codex-handoff/BUSINESS-SPEC.md), [hướng dẫn](../../design/digital-banking-codex-handoff/HUONG-DAN.md), prototype HTML và tám ảnh `redesign-*.png`. Không có thư mục ở đường dẫn `digital-banking/` ban đầu; người dùng đã thêm bản handoff.
- Đã đọc HTML và xem cả tám ảnh desktop/mobile. Trình duyệt chặn mở prototype bằng `file:`; không chạy prototype tương tác bằng đường vòng. Giao diện sản phẩm localhost đã được kiểm tra tương tác riêng.
- Đã đọc README, workflow backend, roadmap frontend và phases 01–08, OpenAPI, controller/service/repository/tests liên quan. Checkout không có AGENTS.md hoặc frontend để tái sử dụng. Tái sử dụng kiến trúc đã chốt trong [roadmap](00-roadmap.md), không thay framework sẵn có.
- Checkout ban đầu nhánh main; `outputs/` là tệp người dùng, `docs/design/` được người dùng bổ sung. Giữ nguyên nội dung hai nhóm này. Trong giai đoạn triển khai chưa commit/push; sau đó người dùng yêu cầu tạo nhánh `frontend` và PR về `main`. Bộ thiết kế liên quan được đưa vào nhánh, `outputs/` giữ ngoài commit.

## Đối chiếu màn hình, API và quyền

API base `/api/v1`; DTO sinh từ [contract](../../../contracts/openapi.yaml).

| Màn/luồng | Backend hiện có | API tích hợp | Frontend/quyền |
| --- | --- | --- | --- |
| Login/session/workspace | OnboardingController, SessionController, CsrfController | POST auth/login, refresh, logout; GET auth/csrf | AuthPages, session/client; quyền server, refresh single-flight, cookie/CSRF; đổi user xóa cache |
| Đăng ký | OnboardingService + RegistrationVerificationService mới | POST auth/register/send-otp, register/verify-otp, register | Gửi → xác minh → hồ sơ; proof memory 300s; thành công về login, không tự đăng nhập |
| Recovery | RecoveryService, migration V6 | POST auth/recover/initiate, verify, confirm | Yêu cầu → màn OTP → màn mật khẩu và xác nhận; quyền reset server; thông báo chung; OTP/proof hết hạn |
| Hồ sơ/PIN | OnboardingController, PinCredentialService | GET customers/me; POST customers/me/pin/setup, change, forgot/initiate, forgot/confirm | CUSTOMER; code 6 chữ số giữ số 0 đầu; staff không bị ép tạo PIN |
| Tổng quan/tài khoản | AccountController, AccountQueryService | GET accounts, accounts/{id} | Ownership server; số che; tiền string/BigInt; không optimistic balance |
| Chuyển tiền/người nhận | RecipientController, TransferController, TransferService | POST recipients/resolve, transfers, transfers/{id}/confirm-otp; GET transfers/{id} | CUSTOMER + PIN; key ổn định; bước OTP theo status server; lỗi mạng giữ intent/key để đối soát |
| Lịch sử/detail | TransferQueryService | GET transfers, transfers/{id} | Cursor/date UTC, hiển thị giờ Việt Nam; recipient chỉ thấy completed; mobile cards/desktop table |
| Operator | OnboardingController, AccountController | GET operator/customers; POST operator/customers/send-otp, operator/customers; POST operator/accounts/{id}/seed-balance, block, unblock | OPERATOR/ADMIN; exact phone/email; Account DTO che số; seed key + confirm; block/unblock reason + confirm; acceptance PASS |
| Audit | AuditController/ListAuditEventsService | GET audit-events | AUDITOR/ADMIN; chỉ đọc; filter/cursor, drawer tiếng Việt, giờ Việt Nam |
| Risk | RiskFlagController/RiskEvaluationService | GET operator/risk-flags | OPERATOR/AUDITOR/ADMIN; chỉ đọc; cảnh báo không kết luận gian lận; bảng chỉ cuộn trong vùng bảng |

## Quyết định được người dùng duyệt

1. Giữ mobile responsive; quên mật khẩu tách OTP và mật khẩu mới theo brief, dùng API recovery đã có.
2. Bổ sung xác minh đăng ký riêng và chốt hợp đồng proof: [spec](../2026-10-08-registration-verification.md). Phone/OTP → token opaque một lần dùng 300s; OTP mới vô hiệu token cũ; reload xác minh lại; register vẫn hỗ trợ OTP legacy.
3. Cho phép sửa các lỗi tích hợp phát hiện trên PostgreSQL, giữ nghiệp vụ và contract: [spec](../2026-10-08-ui-api-regressions.md). COMPLETED INSERT có completed_at; NULL guard ép kiểu ở history/audit/risk.
4. Người dùng duyệt “sửa giúp tôi theo đề xuất đó luôn”: GET operator/customers đã map sang DTO `Account.accountNumberMasked`, không trả số đầy đủ; [spec và kiểm chứng](../2026-10-09-operator-lookup-response.md). Frontend validation và OpenAPI giữ nguyên; regression MVC/API và acceptance browser đã PASS.

## Các giai đoạn đã triển khai

- Foundation: React/TS/Vite, Ant Design Form, CSS Modules, Query/Zustand memory; tokens navy/blue/background/border theo thiết kế. Auth card, Customer desktop/sidebar và bottom nav mobile, Staff sidebar 76px ở tablet, drawer ở mobile.
- API/session: bearer memory, refresh cookie/CSRF, single-flight, GET 401 thử lại một lần; mutation không tự replay; 204, 429/Retry-After, correlation ID, validation/error mapping; không log/lưu secret trong storage/URL/cache. Không đưa toolbar thiết kế/chọn role giả vào sản phẩm.
- Auth/profile/PIN: API thật cho login, đăng ký, recovery ba bước, hồ sơ đọc và PIN setup/change/reset. Xác nhận mật khẩu/PIN phía form; server quyết định hiệu lực OTP/proof.
- Customer: resolver thật, wizard và receipt, nguồn ACTIVE, kết quả server, OTP pending, COMPLETED/EXPIRED/FAILED, unknown reconciliation, lịch sử cursor và filter, chi tiết ownership.
- Staff: exact lookup, nạp có key/confirmation, khóa/mở khóa có reason/confirmation, tạo khách tại quầy có OTP thực; audit/risk readonly. Staff modules tải theo nhu cầu. Lookup trả DTO đúng contract và acceptance Operator đã PASS.

## Tệp thay đổi

- `frontend/`: package/lock/Vite/TS, README, `scripts/check-api.mjs`, `scripts/api-smoke.mjs`; `src/api/client.ts` và generated types; `src/app/`, `src/stores/`, `src/components/shared.tsx`, `src/layouts/`, `src/styles.css`; các feature AuthPages, AccountPages, TransferPage, HistoryPages, OperatorPages, ReadOnlyPages; bốn tệp test và DOM setup.
- Backend registration: `RegistrationVerificationService`, `RegistrationTokenRepository`, V7; sửa `OnboardingService`, `OnboardingController`, `SecurityConfiguration`, rate-limit factory/interceptor; thêm service/MVC/Postgres tests.
- Backend lỗi tích hợp: `TransferJdbcRepository`, `AuditEventRepository`, `RiskFlagRepository`; `UiApiRegressionPostgresTest`.
- Backend lookup Operator: DTO/mapping trong `OnboardingController`; regression `OnboardingControllerMvcTest`; assertions schema/che số trong API smoke.
- Contract bổ sung verify đăng ký và lựa chọn proof/OTP legacy; không đổi contract chuyển tiền/audit/risk.
- `.gitignore`, README root, ba spec backend đã duyệt và hoàn tất, tài liệu này và `evidence/`. Không sửa thiết kế hoặc outputs của người dùng.

## Kiểm chứng thực tế

Môi trường Java 21, Maven 3.9.11, Node 22/npm 10; PostgreSQL 16 qua Docker Desktop. Tạo database/container QA riêng `cloude-ui-qa-20261008`, chỉ loopback port35437, không volume và không reset database dùng chung. Identities QA đều giả, mailbox guard/token/OTP không in vào output test.

| Kiểm tra | Kết quả |
| --- | --- |
| `mvn -o package` với JWT_SECRET ngẫu nhiên đủ 32 bytes | Final PASS:154 tests,0 failure/error/skip, executable JAR đóng gói; bao gồm ba regression lookup mới. Windows từng khóa JAR đang chạy; đã dừng đúng helper QA trước package |
| `mvn -o -Dtest=OnboardingControllerMvcTest test` | PASS14 tests; nhiều account đều che số, metadata giữ nguyên, empty list/email/ADMIN, lỗi400/403/404 |
| `mvn -o -Dtest=RegistrationTokenPostgresTest test` sau thêm concurrency | PASS: 4 tests; hai request cùng proof tạo đúng một user/account; hash-only, phone-bound, one-use, expiry/invalidation và rollback |
| Regression PostgreSQL trước/sau sửa | Trước: 3 test lỗi do completion constraint/NULL type; sau: 4 test PASS, gồm replay một lần đổi số dư, history visibility/filter/cursor, audit/risk filters, OTP sai 5 lần FAILED và EXPIRED không debit |
| `npm run typecheck`, `npm test` | PASS: 14 tests trong 4 files; gồm separate recovery/registration, xác nhận mismatch không gửi, refresh/401/cache isolation/429, lỗi mạng và double click giữ cùng transfer key/body |
| `npm run api:check` | PASS, generated types khớp OpenAPI |
| `be/scripts/validate-openapi.py` | PASS, 31 operations; dùng bundled Python và đúng requirements repo cài vào target riêng |
| `npm run format:check` | PASS; final build sau sửa nhãn drawer và copy/countdown OTP cũng PASS |
| `npm run build` | Final PASS:5m20s; main1,212.01KB (gzip377.71KB), staff chunks38.85/4.64KB riêng. Có cảnh báo chunk >500KB; chưa tối ưu bundle sâu hơn |
| `npm run api:smoke` | Final PASS12 scenario API thật trên PostgreSQL riêng; lookup phone/email kiểm tra đủ DTO, tất cả accounts che số, không có accountNumber, CUSTOMER/AUDITOR403 |

Mười hai scenario API smoke: role/ownership403; operator lookup/seed + replay; đúng5M completed và5,000,001 pending→OTP sai không debit→OTP đúng + replay chỉ debit một lần; block/unblock; history/cursor/recipient visibility/audit/risk; OTP verify đăng ký + phone-bound/one-use; khách mới login→PIN→seed→transfer→history; resend vô hiệu proof; recovery anti-enumeration + EMAIL verify/reset một lần dùng; tạo khách tại quầy bằng OTP thật và account response che số; đổi PIN rồi khôi phục PIN bằng OTP; SMS recovery verify/confirm.

## Desktop/mobile và bằng chứng

Đã kiểm tra ứng dụng thật trong trình duyệt, chỉ fixture QA; không thao tác các input của người dùng trong tab preview khác.

- Customer1440: số dư và lịch sử từ API thật; 360px: bottom nav và transaction cards, không tràn ngang (`scrollWidth = innerWidth`).
- Auth390/1440: recovery request, card/spacing; ảnh và DOM kiểm chứng. OTP/password separation và mismatch/proof được kiểm thử component với response fixture, API recovery thật được smoke kiểm chứng riêng.
- Staff768: sidebar76px, risk data thật, table client586/scroll1000 nhưng page768/768; 1024 page1024/1024, table670/1000; 1440 audit page1440/1440, table1070/1200. Drawer readonly; audit filter và tải thêm cursor. Auditor không thấy Operator navigation; vào trực tiếp staff/customers →403. Logout và bootstrap refresh khi navigation hoạt động.
- Transfer: resolver API thật, receipt1440, giao dịch5,000,001 qua UI trả AWAITING_OTP và TTL120s; OTP sai thực bị từ chối, form giữ pending. Đã xác nhận sau TTL120s: API chuyển EXPIRED, giao diện không còn form OTP; đối chiếu PostgreSQL số dư vẫn90001999 và completed_at NULL.
- Kiểm tra bổ sung 09/10: Customer đổi PIN qua UI, xác nhận không khớp bị chặn; khôi phục bằng OTP thật về PIN fixture ban đầu thành công. Operator tạo khách tại quầy qua UI: gửi OTP → hồ sơ → modal xác nhận → kết quả thành công với số tài khoản che. Chỉ dữ liệu QA giả trên database riêng; không thay thông tin của người dùng.
- Bằng chứng bổ sung: [Customer sau khôi phục PIN](evidence/customer-pin-restored-1440.jpg), [tạo khách tại quầy thành công](evidence/operator-created-customer-1440.jpg).
- Operator sau sửa lookup: desktop1440 và tablet768 tra cứu/nạp/khóa/mở khóa PASS; reason rỗng bị chặn, số dư chỉ đổi sau xác nhận server, BLOCKED tắt nạp. Đối chiếu DB: ACTIVE,170019342, đúng hai seed UI12345+1000. Mobile360 page345≤360/modal360/360, drawer hoạt động; hủy xác nhận nạp không tạo seed. Backend429 sau smoke được chờ đúng Retry-After rồi login thành công.
- Bằng chứng Operator: [desktop cuối](evidence/operator-final-desktop.jpg), [lookup1440](evidence/operator-lookup-1440.jpg), [seed1440](evidence/operator-seed-confirm-1440.jpg), [blocked1440](evidence/operator-blocked-1440.jpg), [lookup768](evidence/operator-lookup-768.jpg), [seed768](evidence/operator-seed-confirm-768.jpg), [lookup360](evidence/operator-lookup-360.jpg), [seed360](evidence/operator-seed-confirm-360.jpg).
- Ảnh: [customer desktop](evidence/customer-1440.jpg), [customer mobile](evidence/customer-360.jpg), [recovery mobile](evidence/recovery-390.jpg), [recovery desktop](evidence/recovery-1440.jpg), [risk768](evidence/risk-768.jpg), [risk1024](evidence/risk-1024.jpg), [audit1440](evidence/audit-1440.jpg), [transfer review](evidence/transfer-review-1440.jpg), [OTP sai mobile](evidence/transfer-otp-invalid-390.jpg), [EXPIRED mobile](evidence/transfer-expired-390.jpg).

## Phần còn lại và cách tiếp tục

1. Scope thiết kế/API đã triển khai và kiểm chứng theo các viewport/luồng đại diện nêu trên; blocker lookup Operator đã giải quyết và spec hoàn tất.
2. Frontend build/typecheck/tests đã PASS trước đó; scope lookup này không sửa mã UI hoặc contract. Backend package/test154, MVC14, API smoke12 và format/diff checks mới đều PASS. Khi có thay đổi tiếp theo, chạy lại các kiểm tra liên quan.
3. Chưa chạy tất cả trạng thái của mọi màn trong browser: ADMIN đa khu vực chưa E2E browser; đã nối controller/DTO/role guard thực. Đổi/khôi phục PIN và tạo khách tại quầy đã PASS cả browser và API smoke. Không có delivery SMS/email bên ngoài trong môi trường QA, chỉ local mailbox backend. Chưa có performance audit/accessibility audit đầy đủ hoặc pixel diff tự động; đã kiểm tra ảnh/DOM các viewport đại diện.
4. Chưa triển khai/deploy production. Chạy local theo [frontend README](../../../frontend/README.md), backend theo README hiện có. Production cần API cùng origin và SPA fallback.
5. Build có cảnh báo bundle lớn; giữ làm mục tối ưu kế tiếp, không tăng warning limit để che. Không thêm API resend/cancel transfer vì backend không có.

Runtime QA hiện có backend loopback8080 và Vite5173, container35437. Logs/build artifacts ở `be/target` và `frontend/dist` bị ignore. Khi dọn, chỉ dừng đúng helper/container QA đã xác nhận danh tính; không xóa volume hoặc dữ liệu giữ lại. Không ghi credentials QA vào docs/commit.
