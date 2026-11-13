# OPS-CREATE-CUSTOMER — Tạo khách tại quầy

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | OPS-CREATE-CUSTOMER |
| Trạng thái UI | Có source UI |
| Route trong code | /staff/customers/new |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-06-operations-and-fraud.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/staff/OperatorPages.tsx dòng 453–630](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/staff/OperatorPages.tsx#L453-L630)

Mô tả theo component và CSS của commit trong metadata. Quy tắc đích theo đặc tả tính năng liên kết và công việc trong tracker.

## 2. Bố cục và thứ tự vùng

Heading → Panel narrow Thông tin khách hàng → ErrorPanel/warning unknown → Form dọc, gửi OTP và hồ sơ → nút Kiểm tra và tạo tài khoản → Modal xác nhận. Thành công thay panel bằng Result.

## 3. Component và cấu hình

| ID | Component | Cấu hình / dữ liệu | Hành động |
|---|---|---|---|
| CREATE-PHONE | Input tel | phone, Số điện thoại khách, phoneRules | Đổi phone xóa sentPhone |
| CREATE-SEND | ActionButton | Gửi OTP khách hàng | Validate phone riêng, post send-otp |
| CREATE-SENT | Alert info spaced | OTP đã gửi đến điện thoại khách | Theo sentPhone |
| CREATE-NAME | Input | fullName, Họ và tên, required/max120/whitespace | Nhập hồ sơ |
| CREATE-EMAIL | Input email | email, emailRules | Nhập email |
| CREATE-PASSWORD | Input.Password | initialPassword, autocomplete new-password, 12–128 | Reset sau confirm request |
| CREATE-ADDRESS | TextArea | address tùy chọn, rows2/max300 | Nhập địa chỉ |
| CREATE-OTP | CodeField | otp 6 số | Xác minh tại create |
| CREATE-REVIEW | Button primary block | Kiểm tra và tạo tài khoản, disabled thiếu sentPhone hoặc action.disabled | Validate form rồi mở modal |
| CREATE-MODAL | Modal | Xác nhận tạo tài khoản khách?, maskClosable=false, closable khi không busy, footer=null | Hủy hoặc ActionButton Tạo tài khoản |
| CREATE-RESULT | Result success trong Panel narrow | Đã tạo khách hàng, account masked, Tra cứu và nạp số dư | /staff/customers |

## 4. Style và kích thước

Panel narrow và form theo [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]]. Gửi OTP đặt sau phone, Alert spaced/flow-alert. Name field .spaced margin-top24. Modal width mặc định Ant với global max-width, form-actions justify-between.

## 5. Trạng thái và tương tác

Change phone reset proof-of-send. Submit form mở modal, confirm kiểm tra sentPhone khớp phone. Busy khóa confirm/close. Finally reset OTP/initialPassword và đóng modal. Unknown create alert hướng dẫn tra cứu trước khi gửi lại. Success thay form bằng Result.

## 6. Responsive

Form một cột, panel body24×20 ở mobile, modal max-width theo viewport. Staff layout theo [[digitalbanking-docs/docs-frontend/design/shared/02-layouts-and-navigation.md]].

## 7. Accessibility và nội dung

Field labels/numeric OTP, form scrollToFirstError, modal có title và text giải thích hệ thống xác minh OTP. Cần QA focus modal/return focus, lỗi và disabled explanation.

## 8. Cập nhật khi triển khai

GAP-01 chốt create customer/API trong phạm vi mới, gắn task triển khai qua FE-06. Cập nhật fields, CTA và modal trong file này khi UI đổi.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |

## Bổ sung preview ngày 13/11/2026 — được duyệt UI/mock ngày 14/11/2026

Source tham chiếu đã được khôi phục tại digitalbanking-frontend. Màn này có thể mở qua thanh Preview UI; APP-BOOTSTRAP dùng trạng thái Khôi phục phiên. Khung preview tại src/mocks/PreviewShell.tsx, adapter tại src/mocks/legacy-preview.ts. Đây là fixture chỉ xem được người phụ trách FE cho phép ngày 13/11/2026; thao tác thiếu contract bị khóa, không thay đặc tả nghiệp vụ đích. Các trạng thái sâu của form register/recovery/PIN chưa được mô phỏng. Task FE-FND-02-MOCK được người phụ trách FE duyệt UI/mock trong chat ngày 14/11/2026; nhiệm vụ đồng bộ contract và tích hợp vẫn mở; bằng chứng và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-13-c1760f1/run-01.md]].
