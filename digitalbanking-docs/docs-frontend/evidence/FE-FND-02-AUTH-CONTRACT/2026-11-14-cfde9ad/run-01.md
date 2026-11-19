# Phiếu FE-FND-02-AUTH-CONTRACT / run-01

## 1. Đầu vào

Ngày 14/11/2026, Asia/Bangkok; Codex. Baseline frontend `cfde9ad205f4bc341d979f58b28bc2a065438fe2`. Working tree chứa FE-FND-01-TOOLING và FE-FND-02-DATA chưa commit/chưa được ghi nhận review mới. BE runtime: không áp dụng. Contract hiện tại 1.0.0, hash trước sửa `71EFC546FB6645BE128EC83D391706343A6FF6B1FA6991910E42770D62EF796A`.

Nguồn đã đọc: AGENTS.md; FE README/tracker/roadmap/FE-RUN; FE-01 và FE-AUTH-01/03; API Contracts mục 1.4/2.1/3.2/8.1/11; OpenAPI README và YAML; Security mục 4.3; BE identity/auth, REST conventions, DB schema reference; đối chiếu các tên cookie/purpose trong SRS và DB common. Không thay code/schema DB chỉ vì tên mục tiêu được chọn cho FE/mock.

## 2. Quyết định của người dùng

Người phụ trách FE trả lời ngày 14/11/2026: “Theo Security: SESSION_TOKEN, Path=/api, LOGIN_MFA; cần đồng bộ lại API/YAML trước phần phụ thuộc.” Phạm vi câu hỏi/đáp án là baseline FE/mock; BE/Security phải xác nhận trước tích hợp thật.

Sau khi đọc nguồn gốc: API Contracts mục 1.4 quy định breaking phải tạo version mới, mục 2.1 ghi URL version breaking sang /v2. Chuyển cookie và thay enum purpose hiện hữu tác động client. Vì vậy đã hỏi tiếp về cách áp dụng version: sửa baseline /v1 dạng nháp FE/mock với ngoại lệ được ghi, hoặc giữ /v1 và tạo contract /v2. Tại thời điểm mở phiếu chưa có câu trả lời nên phần phụ thuộc được giữ lại. Sau đó người dùng chốt: “Giữ nguyên /api/v1 hiện tại; tạo contract /api/v2 theo đúng quy tắc versioning cho baseline Security.” Đã thực hiện theo lựa chọn này; không sửa v1.

## 3. Phần độc lập đã thực hiện

Soạn [[digitalbanking-docs/docs-frontend/decisions/ADR-002-session-security-contract-alignment.md]] với bản đồ thay đổi và phụ thuộc DB/runtime, phân biệt nội dung đã được người dùng chốt với quyết định version còn mở. Cập nhật tracker/GAP-05 và roadmap để các lần chạy sau dùng quyết định mới, không coi các tài liệu hiện tại đã đồng bộ.

## 4. Kiểm tra hoàn thành

| Đầu ra | Expected | Actual / trạng thái |
|---|---|---|
| Baseline cookie/purpose phía FE | Ghi đúng quyết định người dùng | Đã ghi SESSION_TOKEN, /api, LOGIN_MFA |
| Versioning | Theo API Contracts hoặc ngoại lệ được người dùng chốt rõ | Đã chốt v2 riêng; 2.0.0 Draft FE/mock, giữ /api/v1 |
| Inventory | Chỉ rõ API/YAML/FE/BE/SRS/DB bị ảnh hưởng, không sửa bằng chứng cũ | Đã soạn trong ADR-002 |
| Ngữ nghĩa → YAML → generated types/mock | Đồng bộ theo thứ tự trước implementation phụ thuộc | Đạt local — 07a, YAML v2, types v2 riêng, mock v2 và versioned api:check |
| Tests/codegen/browser | Chỉ ghi chạy thật cho thay đổi đã có | 6 tests mới và full 72 tests/typecheck/lint/API/format/build đạt; chưa Browser/API thật |

## 5. Kết quả và giới hạn

FE-FND-02-AUTH-CONTRACT Chờ review sau khi version được chốt và gate local đạt. GAP-05 giữ mở cho xác nhận BE/Security/runtime/DB. V1 và baseline 1.0.0 giữ nguyên; các thay đổi mới nằm trong v2 riêng. Shared session client/UI chưa chuyển sang v2, OTP verify/resend/FIDO v2 chưa có handler; request chưa hỗ trợ vẫn fail closed. Không tạo approval của owner khác; không commit/push/merge.

## 6. Kết quả sau khi người dùng chốt v2

Ngữ nghĩa tại [[digitalbanking-docs/docs-common/07a. API contracts v2.md]], spec [[digitalbanking-docs/openapi/digital-banking-v2.yaml]], info.version 2.0.0 Draft FE/mock. V2 giữ danh mục 30 operations và chỉ đổi server base/cookie/purpose theo delta đã chọn. API Contracts và YAML v1 không có diff; raw SHA256 v1 còn 71EFC546FB6645BE128EC83D391706343A6FF6B1FA6991910E42770D62EF796A. Types v2 sinh riêng tại src/shared/api/generated/v2/openapi.d.ts, không sửa type v1 bằng tay.

Mock v2 có login 200; username fixture-mfa chọn 202 LOGIN_MFA; session 401/200; logout yêu cầu CSRF fixture và xóa cookie cùng tên/path. Header cookie chỉ là fixture (Node test quan sát header), không chứng minh browser HttpOnly/Secure, cookie jar hay DB session. Mock v1 và v2 dùng state riêng chỉ để kiểm tra kịch bản, không tự chốt coexistence BE. Worker được bổ sung handler v2, UI/adapter v1 không chuyển route/submit behavior.

Kiểm tra thực tế: codegen dùng openapi-typescript 7.6.1 với lintDocument/bundle của Redocly tích hợp; YAML được parse bằng js-yaml 4.3.2 (dependency trực tiếp đã có trong cache); tests đối chiếu toàn bộ cây schema/operations chỉ có delta đã chốt và v1 LF-normalized hash khớp git baseline. Lượt test đầu dùng nhầm raw-hash cho bản đã chuẩn hóa LF nên thất bại; sửa expected theo hash từ git baseline và xác nhận v1 bytes không đổi, không sửa v1 để làm test đạt.

Lệnh targeted: node node_modules/vitest/vitest.mjs run src/mocks/v2-auth.test.ts src/shared/api/contracts.test.mjs — 6 tests đạt sau sửa oracle hash. Lệnh full: npm exec --yes --offline --package=pnpm@10.34.9 -- pnpm check — 10 files, 72 tests và lint/typecheck/API v1+v2/format/build đạt. [Log](test-report/checks.log). Môi trường Windows/PowerShell, Node 22.14.0, pnpm 10.34.9; không chạy lại Browser vì UI không đổi. Build còn cảnh báo bundle/annotation như baseline. CI GitHub và BE thật chưa chạy.

[Manifest](test-report/source-manifest.json) ghi input hashes của code/contract làm việc chưa commit, bao gồm phần tooling/data từ lần trước. Review: chờ người phụ trách FE, không tự gán duyệt toàn bộ v2 hoặc đóng GAP-05. Các mục V2-01..05 ở 07a yêu cầu BE/Security/DB xác nhận.

Kiểm tra cuối: bổ sung X-Correlation-ID thống nhất body/header trong mock v2 và chạy lại toàn bộ gate (log ở trên). 17 file Markdown mới/thay đổi, 153 đích wikilink tồn tại; diff --check đạt, có cảnh báo xuống dòng Git. Manifest đã cập nhật sau lần chạy cuối; chưa commit/push.
