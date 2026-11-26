# CUST-ACCOUNT — Chi tiết tài khoản

> Cập nhật FE-02-01 18/11: preview riêng `/customer/accounts/:accountId?customerPreview=v2` gọi mock getAccount; `CustomerAccountDetailPanel` trình bày owner.fullName, masked number, availableBalance, currency/status/createdAt/asOf, giữ Money decimal string. Có loading/403/404/retry/quay lại, direct URL/reload theo fixture; không có action transfer. [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-02.md]].

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

## Bổ sung preview ngày 13/11/2026 — được duyệt UI/mock ngày 14/11/2026

Source tham chiếu đã được khôi phục tại digitalbanking-frontend. Màn này có thể mở qua thanh Preview UI; APP-BOOTSTRAP dùng trạng thái Khôi phục phiên. Khung preview tại src/mocks/PreviewShell.tsx, adapter tại src/mocks/legacy-preview.ts. Đây là fixture chỉ xem được người phụ trách FE cho phép ngày 13/11/2026; thao tác thiếu contract bị khóa, không thay đặc tả nghiệp vụ đích. Các trạng thái sâu của form register/recovery/PIN chưa được mô phỏng. Task FE-FND-02-MOCK được người phụ trách FE duyệt UI/mock trong chat ngày 14/11/2026; nhiệm vụ đồng bộ contract và tích hợp vẫn mở; bằng chứng và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-13-c1760f1/run-01.md]].

### FE-02-01-V1-API ngày 24/11/2026

Runtime v1 mở `/customer/accounts/:accountId` từ danh sách hoặc direct URL, gọi `getAccount`. `V1CustomerDashboard` hiện số dư, số tài khoản mask, currency, status, ngày mở, asOf và **mã khách hàng** từ `owner.customerId`; không dùng `owner.fullName` của preview v2 vì v1.1.0 không có field đó. 403/404 hiện lỗi an toàn và nút quay lại, không render detail cũ. QA HTTP thật đã kiểm 200/403/404; browser còn chờ INT-10 privileges. [[digitalbanking-docs/docs-frontend/evidence/FE-02-01-V1-API/2026-11-24-eb4e2e7/run-01.md]].


### FE-BE-INT-10 & chốt UI M2 ngày 25/11/2026

Sau sửa `INT-10`, Playwright browser E2E đã kiểm chứng luồng từ trang chủ vào `/customer/accounts/:accountId` (`Mã khách hàng`, số tài khoản mask, trạng thái) kèm screenshot `m2-customer-detail.png`, và người phụ trách FE đã chốt UI M2 ngày 25/11/2026. [[digitalbanking-docs/docs-frontend/evidence/FE-BE-INT-10/2026-11-24-ce0d36e/run-01.md]].
