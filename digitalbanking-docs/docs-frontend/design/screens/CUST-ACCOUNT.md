# CUST-ACCOUNT — Chi tiết tài khoản

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | CUST-ACCOUNT |
| Trạng thái UI | Có source UI |
| Route trong code | /customer/accounts/:accountId |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-02-customer-and-accounts.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/accounts/AccountPages.tsx dòng 58–93](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/accounts/AccountPages.tsx#L58-L93)

[Source frontend/src/features/accounts/AccountPages.tsx dòng 156–193](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/accounts/AccountPages.tsx#L156-L193)

Mô tả theo component và CSS của commit trong metadata. Quy tắc đích theo đặc tả tính năng liên kết và công việc trong tracker.

## 2. Bố cục và thứ tự vùng

BankingLayout customer → Heading Chi tiết tài khoản/subtitle ownership → ErrorPanel → Loading hoặc Panel narrow Tài khoản VND.

## 3. Component và cấu hình

| ID | Component | Cấu hình / dữ liệu | Hành động |
|---|---|---|---|
| ACCOUNT-TITLE | Heading | Chi tiết tài khoản | Tĩnh |
| ACCOUNT-ERROR | ErrorPanel | query.error | Refetch detail |
| ACCOUNT-PANEL | Panel narrow | Tài khoản VND | Khối dữ liệu |
| ACCOUNT-BALANCE | Money.balance-value | query.data.balance | Hiển thị tiền |
| ACCOUNT-INFO | AccountInfo/Descriptions column1 colon=false | Chủ tài khoản, masked number, loại tiền, Status, ngày mở | Đọc dữ liệu |
| ACCOUNT-TRANSFER | Button primary trong Link | Chuyển tiền | /customer/transfers/new |

## 4. Style và kích thước

Panel narrow max-width644/margin auto/body28×32, balance36px, Descriptions content wrap theo global. Heading/card/theme tại [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]].

## 5. Trạng thái và tương tác

Pending Loading. Query error có retry và không có card nếu query.data chưa tồn tại. Đã có row render account info; trạng thái account hiện bằng Status. Source không có action sửa account trên màn này.

## 6. Responsive

Panel narrow mobile body24×20, balance32px, Descriptions label max-width140 ở tối đa767. Customer layout có bottom nav và padding-bottom100.

## 7. Accessibility và nội dung

Title và retry text rõ, Descriptions trình bày nhãn/giá trị, chuyển tiền là link/Button. Kiểm chứng long masked account/text wrapping và keyboard.

## 8. Cập nhật khi triển khai

FE-02-01 giữ accountId string, cập nhật status/DTO mới. Nếu bổ sung điều kiện disabled chuyển tiền hoặc show masked owner theo quyền, cập nhật ACCOUNT-* trong file này.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |
