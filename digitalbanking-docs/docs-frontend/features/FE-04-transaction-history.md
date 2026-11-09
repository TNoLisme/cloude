# FE-04 — Lịch sử và chi tiết giao dịch

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-04 |
| Phiên bản / ngày | 1.2 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Baseline đã chốt | Nội dung trước phần bổ sung design được người phụ trách FE xác nhận ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, QA |

## 1. Mục tiêu và phạm vi

Customer xem lịch sử của mình, staff tra soát trong phạm vi quyền. Danh sách và chi tiết là read-only, giao dịch có reference liên kết trạng thái/receipt FE-03.

## 2. Nguồn và phụ thuộc

Yêu cầu tại [[digitalbanking-docs/docs-common/03. SRS.md]], API tại [[digitalbanking-docs/docs-common/07. API contracts.md]] và [[digitalbanking-docs/openapi/digital-banking-v1.yaml]].

FR-023–024/027–030/039–040, UC-008–009, AC-014–016/023–024. Contract 07 §8.4, [[digitalbanking-docs/docs-backend/modules/04-transfer-and-ledger.md]].

[[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md#API-PAGE-01 — Filter và phân trang]], [[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md#API-DATA-01 — Kiểu dữ liệu và định dạng]], [[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md#FE-AUTH-02 — CSRF và quyền]].

## 3. Màn hình và điều hướng

CUST-HISTORY, CUST-TX và STAFF-TX theo route map. Customer scope theo session. Staff filter customer/account chỉ khi được quyền. Row link tới detail reference, route state giữ filter khi quay lại.

### Design UI của màn hình

- [[digitalbanking-docs/docs-frontend/design/screens/CUST-HOME.md]] ghi phần giao dịch gần đây dùng chung.
- [[digitalbanking-docs/docs-frontend/design/screens/CUST-HISTORY.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/CUST-TX.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/STAFF-TX.md]].

Trạng thái đã có code và các vùng chờ cập nhật được ghi trong từng file. Quy tắc cập nhật tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 4. Luồng người dùng

1. Parse và validate filter/page/sort từ URL theo allowlist.
2. Gọi listTransactions cho scope hiện hành, hiển thị page/asOf.
3. Đổi filter reset page, render response mới nhất.
4. Mở getTransaction để xem direction/counterparty/amount/status/time/reference masked.
5. Nếu cần xem trạng thái transfer hiện tại, gọi getTransfer theo FE-03 và quyền hiện hành.

## 5. Trạng thái giao diện

Loading/refetch/empty/filter-empty/error theo FE-UI-03. PENDING có nhãn đang xử lý, COMPLETED/FAILED theo server. 403/404 detail có đường về danh sách. Direction DEBIT/CREDIT/INTERNAL trình bày có nhãn chữ.

## 6. Thành phần và dữ liệu

HistoryFilter, TransactionTable/MobileCard, Pagination, TransactionDetail. Query key chứa scope/filter/page/sort. ID/reference string, amount Money, counterparty masked. Filter áp dụng trong URL chỉ gồm giá trị đã phân loại an toàn.

## 7. Tương tác API

| operationId | Method / path |
|---|---|
| listTransactions | GET /transactions |
| getTransaction | GET /transactions/{reference} |
| getTransfer | GET /transfers/{reference} |

Query supported theo YAML gồm account/customer khi được phép, status/direction, from/to, minAmount/maxAmount, page/size/sort. Direction yêu cầu accountId theo contract.

## 8. Validation và thông báo

From trước to, minAmount không lớn hơn maxAmount, size/sort theo API-PAGE-01. UTC range chuyển từ timezone hiển thị. Không chèn raw filter vào thông báo lỗi. Nếu page vượt total sau refetch, về trang cuối hợp lệ và tải lại có giới hạn.

## 9. Trường hợp đặc biệt

Back/forward/reload giữ filter an toàn. Đổi user hủy response/cache cũ. Date range qua ranh giới ngày kiểm chứng UTC. Staff mất quyền giữa phiên hiển thị 403. Export history chưa có operation trong baseline, yêu cầu phát sinh đi qua change control.

## 10. Tiêu chí nghiệm thu

FET-08 chứng minh danh sách/chi tiết, empty/filter-empty, page/reset, date range, direction, amount precision và read-only theo AC-014–016. FET-03/13 hỗ trợ ownership/cache/masking theo AC-023/024. Mobile và keyboard tại FET-14.

## 11. Liên kết triển khai

FE-04-01 tại [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]]. Công việc tiếp theo gồm page 0-based, filter UTC và detail/route theo reference. Evidence gắn FET-08.

## 12. Vấn đề cần chốt

Scope staff/permission theo GAP-07. Trường filter/sort được chốt bởi contract, mọi nhu cầu thêm query cần cập nhật nguồn API. Route là đề xuất FE chờ review.
