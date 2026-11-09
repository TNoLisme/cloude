# FE-07 — Audit và bằng chứng tra soát

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-07 |
| Phiên bản / ngày | 1.2 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Baseline đã chốt | Nội dung trước phần bổ sung design được người phụ trách FE xác nhận ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, QA |

## 1. Mục tiêu và phạm vi

Staff được quyền tra cứu/xem audit theo reference, correlation, actor, action, entity và thời gian. Auditor xuất bằng chứng theo permission đã chốt. UI trình bày dữ liệu read-only và phân loại theo response allowlist.

## 2. Nguồn và phụ thuộc

Yêu cầu tại [[digitalbanking-docs/docs-common/03. SRS.md]], API tại [[digitalbanking-docs/docs-common/07. API contracts.md]] và [[digitalbanking-docs/openapi/digital-banking-v1.yaml]].

FR-034–040/052–053, UC-012, AC-018–024/029. Contract 07 §8.6 và [[digitalbanking-docs/docs-backend/modules/06-audit-and-traceability.md]], [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]].

[[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md#API-PAGE-01 — Filter và phân trang]], [[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md#FE-SEC-03 — Dữ liệu nhạy cảm và browser storage]].

## 3. Màn hình và điều hướng

STAFF-AUDIT với list/filter và detail panel theo auditId. Deep link chi tiết qua route state/query an toàn được FE-FND-03 chốt. Menu/action theo AUDIT_READ/AUDIT_EXPORT, matrix nguồn theo GAP-07.

### Design UI của màn hình

- [[digitalbanking-docs/docs-frontend/design/screens/STAFF-AUDIT.md]].

Trạng thái đã có code và các vùng chờ cập nhật được ghi trong từng file. Quy tắc cập nhật tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 4. Luồng người dùng

1. Mở audit, apply khoảng thời gian/filter đã validate.
2. searchAudit trả page/asOf, hiển thị actor/action/outcome/time/correlation.
3. getAuditRecord theo auditId để đọc chi tiết đã mask.
4. Chọn export với quyền và filter hiện hành, review format/maxRows.
5. exportAudit trả stream/file, giữ trạng thái tải và correlation khi lỗi.
6. Copy reference/correlation đã cho phép để tra soát, link transaction theo quyền.

## 5. Trạng thái giao diện

Search loading/empty/filter-empty/error và detail loading/404/403. Export có trạng thái preparing/downloading/success/error, 413 yêu cầu thu hẹp filter hoặc maxRows. Có thể hủy chờ tải phía browser và giải phóng object URL sau sử dụng.

## 6. Thành phần và dữ liệu

AuditFilter/Table/DetailPanel/ExportDialog và shared download transport. Render detail theo allowlist có label nghiệp vụ. Query cache chỉ dữ liệu được phép trong phiên. File download nhạy cảm được xử lý theo quyền, dataset tổng hợp cho demo/evidence.

## 7. Tương tác API

| operationId | Method / path |
|---|---|
| searchAudit | GET /audit |
| getAuditRecord | GET /audit/{auditId} |
| exportAudit | GET /audit/export |

Export baseline có format CSV/JSONL, maxRows tối đa 10000, response text/csv hoặc application/x-ndjson. Search/filter query theo YAML, Content-Disposition qua transport an toàn.

## 8. Validation và thông báo

From/to và page theo API-PAGE-01, maxRows theo schema. Tên file được làm sạch khi lưu. Lỗi file có thể là Problem JSON, transport kiểm tra status/content-type trước download. Correlation ID hiển thị để hỗ trợ, nội dung raw nhạy cảm theo FE-SEC-03.

## 9. Trường hợp đặc biệt

Permission thay đổi trong lúc export xử lý theo 403. Mạng ngắt giữa tải file báo tải chưa hoàn tất. Range rộng/413 hướng dẫn thu hẹp. Hash-chain/tamper/retention/legal hold cần bằng chứng và công cụ BE, các hành động UI bổ sung theo GAP-10.

## 10. Tiêu chí nghiệm thu

FET-11 chứng minh search/detail/filter/time/page, masking, quyền export, định dạng/file header, lỗi 413 và download failure. AC-021 về append-only có UI read-only và BE tamper evidence đi kèm. AC-022 có correlation xuyên reference. FET-13 kiểm tra direct URL/role.

## 11. Liên kết triển khai

FE-07-01 trong tracker. Source ReadOnlyPages.tsx cũ chỉ là phần tham khảo layout. Evidence gồm sample export đã mask, browser kết quả và BE audit report theo FET-11.

## 12. Vấn đề cần chốt

GAP-07 quyền export khác nhau giữa nguồn, GAP-12 export sync/giới hạn và GAP-10 retention/legal hold. Giá trị 10000 là schema baseline, ngưỡng ngữ nghĩa vẫn được đánh dấu đề xuất tại 07.
