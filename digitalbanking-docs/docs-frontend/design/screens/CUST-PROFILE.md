# CUST-PROFILE — Hồ sơ cá nhân

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | CUST-PROFILE |
| Trạng thái UI | Có source UI |
| Route trong code | /customer/profile |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-02-customer-and-accounts.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/accounts/AccountPages.tsx dòng 194–254](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/accounts/AccountPages.tsx#L194-L254)

Mô tả theo component và CSS của commit trong metadata. Quy tắc đích theo đặc tả tính năng liên kết và công việc trong tracker.

## 2. Bố cục và thứ tự vùng

BankingLayout customer → Heading Hồ sơ/Thông tin cá nhân chỉ đọc → ErrorPanel → Loading hoặc Panel narrow Thông tin của bạn → Descriptions → actions PIN.

## 3. Component và cấu hình

| ID | Component | Cấu hình / dữ liệu | Hành động |
|---|---|---|---|
| PROFILE-INFO | Descriptions column1 | Họ và tên, điện thoại, email, ngày tham gia, PIN giao dịch | Giá trị từ query profile |
| PROFILE-PIN-STATUS | Descriptions item | Đã thiết lập / Chưa thiết lập theo isPinSet | Đọc |
| PROFILE-PIN-CHANGE | Button primary | Đổi PIN khi đã thiết lập | /customer/pin/change |
| PROFILE-PIN-RESET | Button | Quên PIN khi đã thiết lập | /customer/pin/reset |
| PROFILE-PIN-SETUP | Button primary | Thiết lập PIN khi chưa có | /customer/pin/setup |
| PROFILE-ERROR | ErrorPanel | query.error | Retry refetch |

## 4. Style và kích thước

Panel narrow và .actions flex-wrap/gap12 theo [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]]. Ngày format theo shared date, label/value Descriptions Ant Design.

## 5. Trạng thái và tương tác

Pending Loading, error ErrorPanel. Query.data render panel. isPinSet quyết định hai action hoặc setup. Đây là hồ sơ đọc theo component hiện có.

## 6. Responsive

Một cột dữ liệu, panel body24×20 trên mobile, action wrap tự nhiên. Main layout theo [[digitalbanking-docs/docs-frontend/design/shared/02-layouts-and-navigation.md]].

## 7. Accessibility và nội dung

Các field là Descriptions read-only, actions có text rõ. Source trả phone/email được render trực tiếp, FE-FND-02 phải đồng bộ field masked/permission trước phát hành.

## 8. Cập nhật khi triển khai

FE-02-01 và GAP-01 chốt phạm vi PIN, cập nhật link/field theo API đích. Bất kỳ đổi masking/copy/action phải cập nhật PROFILE-*.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |

## Bổ sung preview ngày 13/11/2026 — được duyệt UI/mock ngày 14/11/2026

Source tham chiếu đã được khôi phục tại digitalbanking-frontend. Màn này có thể mở qua thanh Preview UI; APP-BOOTSTRAP dùng trạng thái Khôi phục phiên. Khung preview tại src/mocks/PreviewShell.tsx, adapter tại src/mocks/legacy-preview.ts. Đây là fixture chỉ xem được người phụ trách FE cho phép ngày 13/11/2026; thao tác thiếu contract bị khóa, không thay đặc tả nghiệp vụ đích. Các trạng thái sâu của form register/recovery/PIN chưa được mô phỏng. Task FE-FND-02-MOCK được người phụ trách FE duyệt UI/mock trong chat ngày 14/11/2026; nhiệm vụ đồng bộ contract và tích hợp vẫn mở; bằng chứng và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-13-c1760f1/run-01.md]].
