# CUST-PIN-RESET — Khôi phục PIN

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | CUST-PIN-RESET |
| Trạng thái UI | Có source UI |
| Route trong code | /customer/pin/reset |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-02-customer-and-accounts.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/accounts/AccountPages.tsx dòng 255–398](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/accounts/AccountPages.tsx#L255-L398)

Mô tả theo component và CSS của commit trong metadata. Quy tắc đích theo đặc tả tính năng liên kết và công việc trong tracker.

## 2. Bố cục và thứ tự vùng

Heading theo mode, subtitle PIN gồm 6 chữ số → Panel narrow Xác nhận và đặt PIN mới → ErrorPanel → gửi OTP hoặc Form OTP → PIN mới → xác nhận PIN → CTA.

## 3. Component và cấu hình

| ID | Component | Cấu hình / dữ liệu | Hành động |
|---|---|---|---|
| PIN-SEND | Note + ActionButton | OTP gửi điện thoại đăng ký, Gửi mã OTP | Set sent và ttl từ response |
| PIN-OTP | Alert info + CodeField | OTP đã được gửi, thời hạn ttl giây | Nhập otp khi sent |
| PIN-NEW | CodeField secret | name pin, label PIN mới | Required đúng 6 số |
| PIN-CONFIRM | Form.Item/Input.Password | name confirmPin, numeric/maxLength6/autocomplete off, ẩn toggle, dependency pin | MatchRule và regex 6 số |
| PIN-CTA | ActionButton block | Xác nhận PIN | POST theo mode |
| PIN-RESEND | Button link | Yêu cầu OTP mới | Reset fields và sent=false |


## 4. Style và kích thước

Panel narrow và CodeField theo [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]]. PIN/confirm là ô input liên tục letter-spacing12, font20, width tối đa340. Cả nhóm form dọc.

## 5. Trạng thái và tương tác

Ban đầu Note/Gửi OTP, sau sent hiện Alert ttl và form OTP/new/confirm. TTL hiển thị dạng số giây do server cấp, chưa dùng Countdown cho màn này. Yêu cầu OTP mới reset form/sent. Submit thành công set pinConfigured, refresh data, navigate /customer. Lỗi qua ErrorPanel, form reset trong finally.

## 6. Responsive

Dùng customer layout, panel narrow mobile padding24×20, CodeField max-width100%, form một cột.

## 7. Accessibility và nội dung

CodeField label/extra giải thích paste/leading zero, PIN là secret password input. Confirm field có dependency validation. Form scrollToFirstError, cần QA keyboard/error focus và content của status.

## 8. Cập nhật khi triển khai

Phạm vi/API của PIN được chốt qua GAP-01 và liên kết vào task triển khai phù hợp. Khi thay đổi riêng mode reset cập nhật file này, cùng file shared nếu primitive đổi.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |

## Bổ sung preview ngày 13/11/2026 — được duyệt UI/mock ngày 14/11/2026

Source tham chiếu đã được khôi phục tại digitalbanking-frontend. Màn này có thể mở qua thanh Preview UI; APP-BOOTSTRAP dùng trạng thái Khôi phục phiên. Khung preview tại src/mocks/PreviewShell.tsx, adapter tại src/mocks/legacy-preview.ts. Đây là fixture chỉ xem được người phụ trách FE cho phép ngày 13/11/2026; thao tác thiếu contract bị khóa, không thay đặc tả nghiệp vụ đích. Các trạng thái sâu của form register/recovery/PIN chưa được mô phỏng. Task FE-FND-02-MOCK được người phụ trách FE duyệt UI/mock trong chat ngày 14/11/2026; nhiệm vụ đồng bộ contract và tích hợp vẫn mở; bằng chứng và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-13-c1760f1/run-01.md]].
