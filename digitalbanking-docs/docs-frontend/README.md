# Frontend Documentation

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-DOCS |
| Phiên bản / ngày | 1.2 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Bổ sung 13/11/2026 | Lộ trình/vòng đời task FE-DOC-03 được người phụ trách FE duyệt ngày 14/11/2026; UI/mock được duyệt riêng, không thay nghiệm thu tích hợp |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Baseline đã chốt | Nội dung trước phần bổ sung design được người phụ trách FE xác nhận ngày 09/11/2026 |
| Owner / reviewer | Frontend / FE Lead, Backend, QA, BA |

## 1. Baseline và hướng dẫn đọc

Bộ tài liệu mô tả phạm vi FE, thiết kế triển khai, công việc tiếp theo và kết quả kiểm chứng. Baseline nguồn là main@f6ae7ec và OpenAPI 1.0.0. Những điều chỉnh sau refactor được chuyển thành đầu việc có đầu ra và tiêu chí hoàn thành trong tracker. Bộ design bổ sung mô tả UI có source theo từng màn và giữ file trống cho màn chưa có UI. Owner/reviewer là vai trò phụ trách, người duyệt và ngày duyệt được ghi khi review.

| Người đọc | Thứ tự |
|---|---|
| Thành viên mới | Scope → architecture → feature → tracker → local development |
| Người quản lý | Scope → delivery plan → tracker |
| Người triển khai | Công việc → đặc tả → quy tắc chung được tham chiếu → API → test |
| Backend / QA | API và auth → feature → testing → evidence |

## 2. Bản đồ tài liệu

| Nội dung | Nguồn |
|---|---|
| Phạm vi và truy vết | [[digitalbanking-docs/docs-frontend/management/01-scope-and-feature-map.md]] |
| Đợt triển khai và phụ thuộc | [[digitalbanking-docs/docs-frontend/management/02-delivery-plan.md]] |
| Tiến độ hiện tại và vấn đề mở | [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]] |
| Lộ trình mock trước, duyệt UI và tích hợp BE từng phần | [[digitalbanking-docs/docs-frontend/management/04-mock-first-roadmap.md]] |
| Quy trình kiểm tra mỗi lần chạy task | [[digitalbanking-docs/docs-frontend/guides/03-task-execution-lifecycle.md]] |
| Phiếu chạy task | [[digitalbanking-docs/docs-frontend/templates/task-run-template.md]] |
| Kiến trúc, route, state | [[digitalbanking-docs/docs-frontend/standards/01-architecture.md]] |
| Design UI từng màn hình và shared component | [[digitalbanking-docs/docs-frontend/design/README.md]] |
| UI, form, responsive | [[digitalbanking-docs/docs-frontend/standards/02-ui-and-design-system.md]] |
| API, Money, lỗi, retry, cache | [[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md]] |
| Session, quyền, step-up | [[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md]] |
| Test và nghiệm thu | [[digitalbanking-docs/docs-frontend/standards/05-testing-and-acceptance.md]] |
| FE-01 | [[digitalbanking-docs/docs-frontend/features/FE-01-auth-and-session.md]] |
| FE-02 | [[digitalbanking-docs/docs-frontend/features/FE-02-customer-and-accounts.md]] |
| FE-03 | [[digitalbanking-docs/docs-frontend/features/FE-03-transfers.md]] |
| FE-04 | [[digitalbanking-docs/docs-frontend/features/FE-04-transaction-history.md]] |
| FE-05 | [[digitalbanking-docs/docs-frontend/features/FE-05-limits-and-approvals.md]] |
| FE-06 | [[digitalbanking-docs/docs-frontend/features/FE-06-operations-and-fraud.md]] |
| FE-07 | [[digitalbanking-docs/docs-frontend/features/FE-07-audit.md]] |
| FE-08 | [[digitalbanking-docs/docs-frontend/features/FE-08-administration.md]] |
| Chạy và debug | [[digitalbanking-docs/docs-frontend/guides/01-local-development.md]] |
| Demo và bàn giao | [[digitalbanking-docs/docs-frontend/guides/02-demo-and-handover.md]] |
| Quyết định | [[digitalbanking-docs/docs-frontend/decisions/README.md]] |
| Template design màn hình | [[digitalbanking-docs/docs-frontend/templates/screen-design-template.md]] |
| Template | [[digitalbanking-docs/docs-frontend/templates/feature-spec-template.md]], [[digitalbanking-docs/docs-frontend/templates/decision-template.md]] |
| Bằng chứng | [[digitalbanking-docs/docs-frontend/evidence/README.md]] |

## 3. Nguồn chính thức

| Loại thông tin | Nguồn sở hữu |
|---|---|
| BR / FR / UC / AC / NFR | [[digitalbanking-docs/docs-common/02. BRD.md]], [[digitalbanking-docs/docs-common/03. SRS.md]] |
| Công nghệ / kiến trúc tổng thể | [[digitalbanking-docs/docs-common/05. Technical stack.md]], [[digitalbanking-docs/docs-common/06. System architecture.md]] |
| Ngữ nghĩa / schema API | [[digitalbanking-docs/docs-common/07. API contracts.md]], [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] |
| Thiết kế bảo mật | [[digitalbanking-docs/docs-common/design/04. security.md]] |
| Triển khai BE | [[digitalbanking-docs/docs-backend/README.md]] |
| Phạm vi FE | management/01-scope-and-feature-map.md |
| Quy tắc FE hiện hành | standards/ |
| Hành vi UI riêng | features/ |
| Bố cục/component/style theo từng màn | design/screens/, dùng shared tại design/shared/ |
| Trạng thái triển khai hiện tại | management/03-work-tracker.md |
| Lý do lựa chọn và lịch sử quyết định | decisions/ |
| Kết quả đã kiểm chứng | Evidence và test report tại commit cụ thể |

Nếu schema và ngữ nghĩa khác nhau, chốt ở 07 rồi đồng bộ OpenAPI theo [[digitalbanking-docs/openapi/README.md]]. Nếu thiết kế security/BE khác 07, ghi GAP trong tracker và phối hợp owner nguồn trước khi tích hợp.

## 4. FE-DOC-01 — Quy tắc đồng bộ

1. Feature có mã FE-01 đến FE-08. Công việc dùng FE-03-01, nền tảng dùng FE-FND-01, bàn giao dùng FE-REL-01. Giữ ID sau khi đổi tên file.
2. Quy tắc dùng chung được khai báo ở heading của standards. Feature dẫn tới heading tương ứng và mô tả phần đặc thù.
3. Tracker giữ trạng thái công việc hiện tại, danh sách điều chỉnh cần làm tiếp theo và liên kết bằng chứng. Đặc tả giữ hành vi đích và liên kết công việc.
4. Đổi hành vi cần cập nhật nguồn sở hữu, feature/test bị ảnh hưởng và tracker trong cùng PR. Tìm ID quy tắc và đường dẫn bằng rg để kiểm tra phạm vi.
5. Thay đổi API bắt đầu ở 07 và YAML với BE, sau đó sinh lại DTO, cập nhật client/mock/test.
6. Vấn đề mở có ID, owner, ảnh hưởng và điều kiện đóng tại tracker. Quyết định dài hạn có ADR và được phản ánh vào standards sau khi duyệt.
7. Liên kết nội bộ dùng wikilink từ root vault digitalbanking-docs, có heading khi dẫn quy tắc. Source và bằng chứng được dẫn bằng permalink theo commit.
8. PR ghi mã công việc, tài liệu thay đổi, test và kết quả. Reviewer xác nhận code cùng phiên bản đặc tả.
9. Khi duyệt tài liệu ghi người duyệt, ngày và commit/PR. Khi đổi nguồn cần đánh dấu các phần liên quan chờ review lại.
10. Thay đổi UI của màn nào cập nhật file design của màn đó cùng code/PR theo [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 5. Vòng đời và mức chứng minh

Tài liệu đi từ Đang viết → Draft — chờ review → Hoàn thành. Bộ tài liệu FE v1.2, gồm phần design, được người phụ trách Frontend chốt ngày 09/11/2026. Trạng thái quyết định kỹ thuật được ghi riêng trong ADR và tracker. Hoàn thành tài liệu nghĩa là nội dung được duyệt theo phạm vi.

Công việc đi từ Chưa bắt đầu → Đang làm → Chờ review → Đang kiểm chứng → Hoàn thành. Bị chặn ghi điều kiện mở lại. Hoãn cần người chấp thuận, lý do và mốc xem xét.

Mức chứng minh ghi riêng thành đọc source, mock, tích hợp API thật hoặc trình duyệt. Mỗi kết quả có commit/môi trường. Điều kiện Hoàn thành tại [[digitalbanking-docs/docs-frontend/standards/05-testing-and-acceptance.md#FE-TEST-03 — Điều kiện hoàn thành]].

## 6. Change log

| Phiên bản | Ngày | Nội dung |
|---|---|---|
| 1.0 | 08/11/2026 | Viết phạm vi, kế hoạch/tracker, standards, 8 đặc tả, guides, ADR và templates |
| 1.1 | 08/11/2026 | Tập trung các điều chỉnh sau refactor vào công việc tiếp theo trong tracker, đồng bộ liên kết và hướng dẫn đọc |
| 1.2 | 09/11/2026 | Bổ sung design 28 màn, shared foundation/layout, file chờ UI và quy tắc cập nhật design cùng code |

## 7. Phê duyệt baseline tài liệu

Ngày 09/11/2026, người phụ trách Frontend xác nhận chốt bộ tài liệu FE v1.1 trong chat và yêu cầu commit lên main. Các đầu việc implementation, GAP và quyết định kỹ thuật được theo dõi theo trạng thái riêng trong tracker/ADR.

## 8. Phần bổ sung design v1.2

Người phụ trách Frontend đã chốt phần design v1.2 và yêu cầu commit/push main ngày 09/11/2026. File có source ghi rõ commit và mức đọc code/CSS, file chưa có UI giữ phần mô tả trống. Phê duyệt baseline v1.1 ở mục 7 áp dụng cho nội dung đã chốt trước phần bổ sung này.

## 9. Contract session v2 theo Security

Ngày 14/11/2026 người phụ trách FE chọn giữ /api/v1 và tạo /api/v2 riêng dùng SESSION_TOKEN/Path=/api/LOGIN_MFA. [[digitalbanking-docs/docs-common/07a. API contracts v2.md]] và [[digitalbanking-docs/openapi/digital-banking-v2.yaml]] là bản Draft FE/mock, chưa có xác nhận runtime. V1/preview cũ vẫn giữ nguyên; client/UI migration v2 theo task tiếp theo, không đổi baseline v1 đã duyệt.

Contract/types/mock v2 được người phụ trách FE duyệt ngày 14/11/2026 trong phạm vi FE/mock. Trạng thái Draft BE/Security/runtime giữ nguyên; xem [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-AUTH-CONTRACT/2026-11-14-cfde9ad/run-02.md]].
