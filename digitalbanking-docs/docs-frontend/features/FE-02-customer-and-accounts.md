# FE-02 — Khách hàng và tài khoản

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-02 |
| Phiên bản / ngày | 1.2 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Baseline đã chốt | Nội dung trước phần bổ sung design được người phụ trách FE xác nhận ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, QA |

## 1. Mục tiêu và phạm vi

Customer xem hồ sơ, danh sách tài khoản, số dư và chi tiết masked. Account status được dùng để hướng dẫn lựa chọn nguồn tiền. Các màn staff đọc customer/account thuộc FE-06 và dùng lại component trình bày.

## 2. Nguồn và phụ thuộc

Yêu cầu tại [[digitalbanking-docs/docs-common/03. SRS.md]], API tại [[digitalbanking-docs/docs-common/07. API contracts.md]] và [[digitalbanking-docs/openapi/digital-banking-v1.yaml]].

FR-008–012/039–040/052, UC-004, AC-005/006/023/024/029 trong SRS. Contract 07 §8.2 và [[03-customer-and-account]].

[[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md#API-DATA-01 — Kiểu dữ liệu và định dạng]], [[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md#API-CACHE-01 — Cache và invalidation]], [[digitalbanking-docs/docs-frontend/standards/02-ui-and-design-system.md#FE-UI-03 — Ma trận trạng thái]].

## 3. Màn hình và điều hướng

CUST-HOME, CUST-PROFILE, CUST-ACCOUNT theo route map. Account detail mở từ danh sách hoặc direct link và kiểm tra quyền qua API. Action chuyển tiền đưa accountId sang form FE-03 theo selection đã kiểm tra lại.

### Design UI của màn hình

- [[digitalbanking-docs/docs-frontend/design/screens/CUST-HOME.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/CUST-ACCOUNT.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/CUST-PROFILE.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/CUST-PIN-SETUP.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/CUST-PIN-CHANGE.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/CUST-PIN-RESET.md]].

Trạng thái đã có code và các vùng chờ cập nhật được ghi trong từng file. Quy tắc cập nhật tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 4. Luồng người dùng

1. Sau session load profile và listAccounts, các vùng có trạng thái loading/error độc lập.
2. Hiển thị tên/PII theo field trả về, số dư/available balance đúng field contract.
3. Mở account detail bằng getAccount.
4. Chọn chuyển tiền nếu account ACTIVE, FE-03 refetch/validate trước submit.
5. Refresh sau giao dịch terminal hoặc người dùng yêu cầu, giữ asOf khi có.

## 5. Trạng thái giao diện

Loading/empty/error/refetch theo FE-UI-03. LOCKED/CLOSED hiển thị nhãn và lý do nếu API cho phép, action nguồn tiền disabled. 403 cho ownership, 404 cho account không tồn tại. Null dữ liệu có nhãn chưa có thông tin.

## 6. Thành phần và dữ liệu

AccountSummaryCard, AccountDetailPanel, ProfilePanel và MoneyText/MaskedIdentity dùng shared. Query profile/list/detail phân biệt theo userId/accountId, số dư authoritative từ server. UI dùng DTO account và formatter tập trung.

## 7. Tương tác API

| operationId | Method / path |
|---|---|
| getMyProfile | GET /customers/me |
| listAccounts | GET /accounts |
| getAccount | GET /accounts/{accountId} |

Base /api/v1. DTO Profile/Account theo YAML, giữ accountId string.

## 8. Validation và thông báo

Path ID theo schema, account status dùng enum fallback. Tiền/PII theo shared formatter. Thông báo lỗi theo API-ERR-01, action refresh dùng read retry. Quyền customerId từ session và BE.

## 9. Trường hợp đặc biệt

Đổi tài khoản khi request cũ chưa trả phải bỏ response cũ. Back/reload direct link chạy session bootstrap. Account khóa ở tab khác hiển thị sau refetch và server validation khi tạo transfer. Logout xóa cache account.

## 10. Tiêu chí nghiệm thu

- FET-03 kiểm chứng profile/list/detail, ID lớn và Money string chính xác.
- Customer truy cập account khác bị từ chối theo AC-024, UI hiển thị đúng lỗi.
- Masked account/PII và account status theo AC-005/006/023.
- FET-14 kiểm chứng cards/table ở mobile và keyboard.

## 11. Liên kết triển khai

FE-02-01 trong [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]]. Dữ liệu account/profile dùng DTO hiện hành từ FE-FND-02. Test/evidence mới theo FET-03.

## 12. Vấn đề cần chốt

PIN cũ theo GAP-01. Các field profile chưa có API được giữ theo contract hiện hành. Quyền staff được đối chiếu tại GAP-07.
