# Sổ vấn đề tích hợp FE–BE v1

Đối chiếu [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]] và [[digitalbanking-docs/docs-frontend/management/09-v1-fe-be-merge-plan.md]]. Trạng thái dưới đây là trạng thái theo evidence đã có ngày 23/11/2026, không thay phê duyệt BE/BA/Security. Quyết định người dùng/owner cần ghi ngày, nguồn và SHA tài liệu được cập nhật.

| ID | Vấn đề và bằng chứng | Ảnh hưởng / owner | Trạng thái và điều kiện mở lại |
|---|---|---|---|
| INT-01 / GAP-05 | User chốt FE tích hợp `/api/v1`, `DB_SESSION; Path=/`, `LOGIN_NEW_DEVICE` ngày 23/11. Common 07/OpenAPI/DB và BE v1 khớp tên/path/purpose; Security common design/04 cũng ghi DB_SESSION/Path=/ nhưng còn gọi OTP login `LOGIN_MFA`. QA local profile HTTP dùng `Secure=false`, demo profile `Secure=true`. HTTP thật 24/11 với BE `740ab5f` đạt 202/OTP/cookie/CSRF rotate/403/logout/401; Vite/proxy HTTP đạt. | FE auth v1; BE + Security | Mở phần owner review/sửa tên purpose trong Security design, browser cookie jar/critical flow và HTTPS/demo `Secure` verification. IAB/Chrome localhost không truy cập được; Computer Use dừng do không xác định URL đủ chắc. Không gán FE choice thành phê duyệt BE/Security hoặc đóng GAP từ HTTP local. |
| INT-02 / GAP-07 | `07`/BE exact-ID single-bank và SRS/Security còn khác quyền Auditor; phiên chưa cung cấp capability thống nhất. | Staff routes/action; BE + BA + Security, FE | Mở: matrix và cách refresh quyền, FET-10/13. |
| INT-03 / schema v1 | M0 generated types cũ lệch v1.1.0 `AccountOwner`, replay/headers; M1 đã sinh types theo YAML và test pass. | FE contract/types; FE | Đã xử lý **lát schema/mock** ở `371ea10`; đã chuẩn bị adapter ba GET customer/account độc lập [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-V1-READ/2026-11-23-9832fa7/run-01.md]]. Client/UI/API thật chưa đồng bộ toàn bộ. |
| INT-04 / transfer GAP-02/08/09 | Recipient lookup, step-up/response vectors đúng version và recovery khi chưa có reference còn mở; replay/TTL đã có quyết định dev2. | FE-03; BE + BA + Security rồi FE | Mở: owner xác nhận từng phần và FET-04–07 với BE invariant; không tạo lại lệnh khi kết quả chưa rõ. |
| INT-05 / GAP-03 | Không có phone search/user lookup trong v1; exact-ID read có căn cứ riêng. | FE-06/08; BA + BE + Security | Mở: method/version/PII/scope và targetUserId; [[digitalbanking-docs/docs-frontend/management/06-gap03-phone-search-handoff.md]]. |
| INT-06 / GAP-11/12 | Fraud release/action và audit export sync/async/giới hạn, sample còn cần xác nhận. | FE-06/07; BA + BE + Audit | Mở: owner chốt scope, file/permission/evidence; read-only không tự đóng mutation/export. |
| INT-07 / source checks | M0 `git diff --cached --check` báo whitespace trong artifact/log/Markdown từ dev2; FE build và BE compile đạt. | Source merge/review; FE + BE | Mở để phân loại theo file; không tự viết lại artifact lịch sử. Kiểm diff từng task; ghi kết quả và ngoại lệ có owner. |
| INT-08 / UI review | Các màn mock mới chưa được duyệt thiết kế cuối; user muốn chốt sau khi hoàn thành demo. Ngày 23/11 user yêu cầu nối riêng auth v1 trên bố cục có sẵn; đây không phải duyệt thiết kế cuối. | M2–M6; người phụ trách FE | Mở: duyệt UI cuối từng phần trước mốc merge; ghi người/ngày/nguồn. |
| INT-09 / GAP-01/04/10 | Legacy scope, enrollment FIDO/passcode và UI notification/retention chưa đủ contract; top-up ngoài MVP common. | FE auth/ops; BA + BE + Security/Audit | Mở theo [[digitalbanking-docs/docs-frontend/management/08-common-dev2-gap-final-review.md]]; không tự thêm API/mutation. |

Kiểm unit transport với examples YAML v1 cho 422/428/409 được ghi tại [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-V1-TRANSPORT/2026-11-23-44c0a77/run-01.md]]; không đóng INT-04 hay GAP-08/09.

## Mẫu ghi vấn đề mới

Ghi ID, task bị ảnh hưởng, bước tái lập và expected/actual, contract/FE/BE SHA, môi trường và bằng chứng, owner/câu hỏi, trạng thái, cách xử lý và test xác minh. Không ghi secret, token, OTP, raw idempotency key hoặc PII. Giữ lịch sử khi đóng; chỉ đóng sau quyết định đúng owner và kiểm chứng thực tế.
