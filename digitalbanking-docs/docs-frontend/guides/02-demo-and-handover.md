# Demo và bàn giao frontend

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-HANDOVER |
| Phiên bản / ngày | 1.1 / 08/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt tài liệu | Người phụ trách Frontend xác nhận trong chat ngày 09/11/2026 |
| Owner / reviewer | Frontend / QA, Backend, Cloud, người điều phối |

## 1. Điều kiện chuẩn bị

Theo [[digitalbanking-docs/docs-common/10. Master timelines.md]], M4 xác nhận trước Cloud M5. Scope và GAP được duyệt, feature release có evidence mới, FE/BE/contract commit được ghi rõ. Dataset tổng hợp và tài khoản Customer/Operations/Admin/Auditor, maker/checker riêng được chuẩn bị qua kênh QA.

Chỉ sử dụng chức năng và kênh OTP đã được xác nhận của môi trường demo. Secret cung cấp ngoài tài liệu/bằng chứng.

## 2. Kịch bản trình diễn FE

| Kịch bản | Thao tác | Kết quả quan sát / evidence |
|---|---|---|
| H1 — Session/account | Login/challenge → reload → hồ sơ/account → logout | Metadata phiên, số dư/PII masked, session thu hồi, FET-01/03 |
| H2 — Transfer thường | Chọn nguồn/đích → review → submit → receipt/history | Reference/status/amount đúng server, FET-04/08 |
| H3 — Step-up và retry | Nhận 428 → verify → cùng key/body, mô phỏng timeout/double-click | Intent ổn định, kết quả xác minh, FET-05/06 |
| H4 — Pending/recovery | BE tạo điều kiện pending/audit failure rồi reconcile | UI đang xác minh → terminal, report BE cho D3, FET-07 |
| H5 — Staff/fraud/audit | Tra customer → flag review → audit correlation | Scope/quyền, review decision/audit, FET-10/11 |
| H6 — Maker/checker | Tạo limit/role change → self-approve bị từ chối → checker khác duyệt | PENDING đến APPLIED/REJECTED, audit, FET-09/12 |
| H7 — UI/security | Mobile/keyboard, direct URL sai role, đổi user/đa tab | Responsive/focus, 403/cache isolation, FET-13/14 |

H3/H4 cần BE/QA chuẩn bị failure injection an toàn trên QA riêng. Mỗi kịch bản ghi expected/actual và liên kết task/FET. D1 concurrency và D5 load dùng backend/test plan report, FE cung cấp trạng thái quan sát liên quan.

## 3. Gói bàn giao M4

- Scope FR/AC được duyệt và những mục hoãn có quyết định.
- PR/commit FE, BE, contract version, toolchain và dependency lock.
- Build artifact và lệnh tái lập theo local guide.
- Bảng kết quả FET, browser/viewport, defect và known limitations.
- Evidence cho feature critical và report BE về invariant liên quan.
- Docs, ADR hiện hành và tracker đồng bộ cùng phiên bản release.
- Kịch bản demo, cách lấy dữ liệu fixture và secret qua kênh riêng.

## 4. Smoke sau triển khai M5

Kiểm tra HTTPS, public config, deep link vào customer/staff, asset loading, API proxy và cookie flags. Login/challenge → reload getSession → account → transfer QA theo scope → history → logout. Đối chiếu 403/404 và API Problem response, cache index/assets theo policy. Ghi FE-REL-03/FET-15 với environment, artifact checksum và timestamp.

Nếu smoke thất bại, FE/Cloud/BE xác định config hay artifact, ghi defect và quyết định rollback theo runbook 08. Mốc release được ghi trong tracker sau khi nhóm xác nhận.

## 5. Known limitations và cách tiếp tục

Mỗi giới hạn có phạm vi/AC ảnh hưởng, owner, cách vận hành tạm thời được duyệt và trigger mở lại. Vấn đề hiện tại xem [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]]. Gói bàn giao dẫn tới các nguồn này tại commit release cụ thể.

## 6. Liên kết

[[digitalbanking-docs/docs-frontend/standards/05-testing-and-acceptance.md]] và [[digitalbanking-docs/docs-frontend/evidence/README.md]].
