# FE-06 — Tra soát vận hành và cảnh báo rủi ro

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-06 |
| Phiên bản / ngày | 1.2 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Baseline đã chốt | Nội dung trước phần bổ sung design được người phụ trách FE xác nhận ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, QA |

## 1. Mục tiêu và phạm vi

Staff đọc customer/account và giao dịch trong scope, xem fraud flags và ghi quyết định review khi được quyền. UI ghi rõ đây là dấu hiệu cần rà soát và quyết định review. Tác động BLOCK lên giao dịch/khách hàng phải theo ngữ nghĩa BE đã chốt.

## 2. Nguồn và phụ thuộc

Yêu cầu tại [[digitalbanking-docs/docs-common/03. SRS.md]], API tại [[digitalbanking-docs/docs-common/07. API contracts.md]] và [[digitalbanking-docs/openapi/digital-banking-v1.yaml]].

FR-039–040/044–049, UC-010, AC-023–024/026–027. Contract 07 §8.2/8.7, [[03-customer-and-account]], [[07-fraud-and-notification]] và [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]].

[[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md#API-PAGE-01 — Filter và phân trang]], [[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md#FE-AUTH-02 — CSRF và quyền]].

## 3. Màn hình và điều hướng

OPS-CUSTOMER, OPS-FRAUD và link STAFF-TX. Read theo permission matrix, review flag theo 07 hiện dành OPERATIONS. Admin/Auditor đọc flags theo quyền nguồn. Tìm customer/user theo GAP-03, tra cứu theo ID đã có API.

### Design UI của màn hình

- [[digitalbanking-docs/docs-frontend/design/screens/OPS-CUSTOMER.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/OPS-CREATE-CUSTOMER.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/OPS-FRAUD.md]].

Trạng thái đã có code và các vùng chờ cập nhật được ghi trong từng file. Quy tắc cập nhật tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 4. Luồng người dùng

1. Nhận customerId từ nguồn được phép, gọi getAdminCustomer/getAdminCustomerAccounts.
2. Xem account masked và link history FE-04.
3. listFraudFlags theo filter, hiển thị severity/rule/version/status/reference/time.
4. Mở chi tiết từ item đang có, vì baseline chưa có get flag detail operation riêng. Reload danh sách theo filter để đồng bộ.
5. Người có quyền chọn decision ALLOW/BLOCK/ESCALATE/CLOSE và notes, đọc xác nhận.
6. reviewFraudFlag, hiển thị flag cập nhật, refetch list, giữ correlation.
7. Link reference tới getTransfer/FE-04 theo quyền để đối chiếu trạng thái tiền.

## 5. Trạng thái giao diện

Customer loading/not-found/forbidden. Flags list loading/empty/filter-empty/error và trạng thái OPEN/UNDER_REVIEW/ALLOWED/BLOCKED/CLOSED. Review submitting hoặc conflict làm refresh. Quyền chỉ đọc giữ đầy đủ thông tin và action được phép.

## 6. Thành phần và dữ liệu

CustomerReadPanel dùng lại AccountDetail của FE-02 theo public interface, FraudFilter/Table/DetailDrawer/ReviewDialog. Query theo user/scope/filter, drawer giữ selected flagId và DTO mới nhất. MatchedValue chỉ render field allowlist để bảo vệ dữ liệu nội bộ.

## 7. Tương tác API

| operationId | Method / path |
|---|---|
| getAdminCustomer | GET /admin/customers/{customerId} |
| getAdminCustomerAccounts | GET /admin/customers/{customerId}/accounts |
| listFraudFlags | GET /fraud-flags |
| reviewFraudFlag | POST /fraud-flags/{id}/review |
| getTransfer | GET /transfers/{reference} |

FraudReviewRequest gồm decision enum và notes tối đa 500. Filter status/severity/ruleCode/reference/customerId/from/to/page/size/sort theo YAML.

## 8. Validation và thông báo

ID/ref/time/filter theo shared. Notes có giới hạn, decision thuộc enum và quyền hiện tại. Label quyết định cần BA review tác động thực tế, đặc biệt BLOCK sau khi tiền đã hoàn tất. Error mapping tập trung, 409 refetch flag rồi yêu cầu người dùng xem trạng thái mới.

## 9. Trường hợp đặc biệt

Hai người review đồng thời dựa response server, giữ conflict rõ ràng. Lỗi mạng lúc review refetch trước gửi lại. Rule mới/unknown enum có fallback và khóa action chưa hỗ trợ. Flag late-arriving có thể xuất hiện sau khi transfer đã hoàn tất, trạng thái tiền được tra riêng.

## 10. Tiêu chí nghiệm thu

FET-10 chứng minh staff read theo scope, masking, fraud filter/page và review decision, 403 cho read-only role gọi write, 409 concurrent review. Report BE xác minh audit decision và chính sách fraud/fail-open theo AC-026/027. FET-14 cho bảng mobile.

## 11. Liên kết triển khai

FE-06-01 và FE-06-02 tại [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]]. Nội dung thực hiện gồm customer/account scope và fraud review theo endpoint hiện hành. Evidence ghi scope/role/flag reference và FET-10.

## 12. Vấn đề cần chốt

GAP-01 với lookup phone/email, tạo khách, seed/block/unblock legacy. GAP-03 search ID nguồn. GAP-07 role matrix, GAP-11 Fraud Option B. Ngữ nghĩa quyết định review phải được đối chiếu với 07 và thiết kế fraud trước review UI.
